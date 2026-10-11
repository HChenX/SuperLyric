/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.netease

import android.webkit.CookieManager

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest

import org.json.JSONObject

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 网易云音乐官方接口取词来源。
 *
 * <h3>四路载荷</h3>
 * {@code /api/song/lyric/v1} 一次返回四份歌词，都是**明文**（无需解密）：
 * <ul>
 *   <li>{@code yrc}——逐字（YRC，绝对毫秒），优先；{@code lyric} 为 LRC，兜底；</li>
 *   <li>{@code ytlrc} / {@code tlyric}——译文</li>
 *   <li>{@code yromalrc} / {@code romalrc}——罗马音</li>
 * </ul>
 *
 * <p>解析全部交给 {@code lyrics-core}：YRC 走其内置的 {@code NeteaseYrcParser}，
 * LRC 走 {@code EnhancedLrcParser}，译文与罗马音作为伴随载荷统一对齐。
 * <b>旧实现自带一套内联的 YRC/LRC 解析器</b>，属于重复实现，此处已废弃。
 *
 * <h3>VIP 与逐字</h3>
 * 从宿主 WebView 的 CookieManager 中取真实登录凭证，用于解锁 VIP 曲目的完整逐字歌词；
 * 取不到时降级为普通歌词，不影响主流程。
 *
 * @author 焕晨HChen
 */
class NeteaseApiSource : LyricSource {

    override val sourceName: String = "NeteaseApi"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-NeteaseApi").apply { isDaemon = true }
    }

    @Volatile
    private var cancelled = false

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        cancelled = false
        executor.execute {
            val outcome = runCatching { resolve(identity) }
                .getOrElse { error ->
                    AndroidLog.logW(sourceName, "Fetch failed: ${error.message}")
                    LyricOutcome.TransientFailure(error)
                }
            if (!cancelled) session.deliver(outcome)
        }
    }

    override fun cancel() {
        cancelled = true
    }

    private fun resolve(identity: TrackIdentity): LyricOutcome {
        val songId = songIdOf(identity) ?: searchSongId(identity)
            ?: return LyricOutcome.NoLyrics("netease: no song id")

        val payload = fetchLyricPayload(songId)
            ?: return LyricOutcome.TransientFailure(null)

        // 平台明确标记"无歌词"
        if (payload.optBoolean("nolyric", false)) {
            return LyricOutcome.NoLyrics("netease: platform reports nolyric")
        }

        val main = lyricText(payload, "yrc").ifEmpty { lyricText(payload, "lrc") }
        if (main.isEmpty()) {
            return LyricOutcome.NoLyrics("netease: empty lyric")
        }

        val translation = lyricText(payload, "ytlrc").ifEmpty { lyricText(payload, "tlyric") }
        val phonetic = lyricText(payload, "yromalrc").ifEmpty { lyricText(payload, "romalrc") }

        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = main,
                translationText = translation.ifEmpty { null },
                phoneticText = phonetic.ifEmpty { null },
                title = identity.title,
                artist = identity.artist,
                durationMs = identity.durationMs,
                lyricId = identity.stableId,
            )
        ) ?: return LyricOutcome.NoLyrics("netease: parsed empty")

        AndroidLog.logI(
            sourceName,
            "Fetched lyric for id=$songId, lines=${doc.lines.size}, " +
                "wordTimed=${doc.lines.any { it.hasWords }}, " +
                "translation=${doc.lines.any { it.hasTranslation }}, " +
                "phonetic=${doc.lines.any { it.hasPhonetic }}"
        )
        return LyricOutcome.Success(doc)
    }

    // ------------------------------------------------------------------ 接口

    private fun fetchLyricPayload(songId: Long): JSONObject? {
        // 首选 EAPI 端点：字段最全（含 yrc / yromalrc）
        post(
            url = URL_V1,
            body = "id=$songId&cp=false&tv=0&lv=0&rv=0&kv=0&yv=0&ytv=0&yrv=0",
        )?.let { return it }

        // 退回老端点
        return get("$URL_LEGACY?id=$songId&lv=1&kv=1&tv=-1&rv=1")
    }

    private fun searchSongId(identity: TrackIdentity): Long? {
        if (identity.title.isBlank()) return null

        val keyword = if (identity.artist.isBlank()) identity.title else "${identity.title} ${identity.artist}"
        val json = get("$URL_SEARCH?s=${URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())}&type=1&limit=5")
            ?: return null

        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return null
        if (songs.length() == 0) return null

        // 网易云接口不返回时长，无法做时长校验；只接受歌名完全一致的候选
        val normalizedTitle = identity.title.trim()
        for (index in 0 until songs.length()) {
            val song = songs.optJSONObject(index) ?: continue
            if (song.optString("name", "").trim() == normalizedTitle) {
                val id = song.optLong("id", 0L)
                if (id > 0L) return id
            }
        }
        AndroidLog.logD(sourceName, "No exactly-matching NetEase candidate for \"$keyword\"")
        return null
    }

    private fun songIdOf(identity: TrackIdentity): Long? =
        TrackIdentity.normalizeStableId(identity.stableId)
            ?.takeIf { it.matches(NUMERIC_ID) }
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    private fun lyricText(payload: JSONObject, key: String): String =
        payload.optJSONObject(key)?.optString("lyric", "").orEmpty()

    // ------------------------------------------------------------------ HTTP

    private fun post(url: String, body: String): JSONObject? = try {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(FORM_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com")
            .apply { hostCookie()?.let { header("Cookie", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let(::JSONObject)
        }
    } catch (t: Throwable) {
        AndroidLog.logW(sourceName, "POST failed: ${t.message}")
        null
    }

    private fun get(url: String): JSONObject? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com")
            .apply { hostCookie()?.let { header("Cookie", it) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let(::JSONObject)
        }
    } catch (t: Throwable) {
        AndroidLog.logW(sourceName, "GET failed: ${t.message}")
        null
    }

    /**
     * 从宿主 WebView 取登录凭证以解锁 VIP 曲目的完整逐字。取不到不影响主流程。
     */
    private fun hostCookie(): String? = runCatching {
        CookieManager.getInstance().getCookie("https://music.163.com")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val URL_V1 = "https://interface3.music.163.com/api/song/lyric/v1"
        const val URL_LEGACY = "https://music.163.com/api/song/lyric"
        const val URL_SEARCH = "https://music.163.com/api/search/get"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

        val NUMERIC_ID = Regex("^\\d{1,15}$")

        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
