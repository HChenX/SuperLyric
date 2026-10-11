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
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.provider.base.readTextBounded
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

import org.json.JSONObject

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 网易云音乐官方接口取词来源
 *
 * ## 四路载荷
 * `/api/song/lyric/v1` 一次返回四份歌词，都是**明文**（无需解密）：
 * - `yrc`——逐字（YRC，绝对毫秒），优先；`lyric` 为 LRC，兜底
 * - `ytlrc` / `tlyric`——译文
 * - `yromalrc` / `romalrc`——罗马音
 *
 * <p>解析全部交给 `lyrics-core`：YRC 走其内置的 `NeteaseYrcParser`，
 * LRC 走 `EnhancedLrcParser`，译文与罗马音作为伴随载荷统一对齐。
 *
 * ## VIP 与逐字
 * 从宿主 WebView 的 CookieManager 中取真实登录凭证，用于解锁 VIP 曲目的完整逐字歌词；
 * 取不到时降级为普通歌词，不影响主流程。
 *
 * @author 焕晨HChen
 */
class NeteaseApiSource : LyricSource {

    override val sourceName: String = "NeteaseApi"

    private val requests = FetchSlot()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        val token = requests.open()
        requests.execute {
            val outcome = runCatching { resolve(identity) }
                .getOrElse { error ->
                    AndroidLog.logW(sourceName, "Fetch failed: ${error.message}")
                    LyricOutcome.TransientFailure(error)
                }
            if (requests.isCurrent(token)) session.deliver(outcome)
        }
    }

    override fun cancel() {
        requests.invalidate()
    }

    private fun resolve(identity: TrackIdentity): LyricOutcome {
        val songId = songIdOf(identity) ?: when (val outcome = searchSongId(identity)) {
            is SearchOutcome.Found -> outcome.songId
            // 检索空集**不是**平台在说"这首没有歌词"：它说的是"我们没搜到这首歌"，而宿主
            // 上报的曲名/歌手并不总是干净（同名版本、修饰后缀、被改写的字段都够让它搜空）。
            // 判成 NoLyrics 会给一首有歌词的曲目下发纯音乐占位
            SearchOutcome.NoCandidate -> return LyricOutcome.TransientFailure(null)
            // 与上一条同理：报文读不出来/"这次没查成"同样不是曲目结论
            SearchOutcome.Failed -> return LyricOutcome.TransientFailure(null)
        }

        val payload = fetchLyricPayload(songId)
            ?: return LyricOutcome.TransientFailure(null)

        // 平台明确标记"无歌词"
        if (payload.optBoolean("nolyric", false)) {
            return LyricOutcome.NoLyrics("netease: platform reports nolyric")
        }

        val main = lyricText(payload, "yrc").ifEmpty { lyricText(payload, "lrc") }
        if (main.isBlank()) {
            // 平台给的歌词体是空的：这才是确定结论
            return LyricOutcome.NoLyrics("netease: empty lyric")
        }

        val translation = lyricText(payload, "ytlrc").ifEmpty { lyricText(payload, "tlyric") }
        val phonetic = lyricText(payload, "yromalrc").ifEmpty { lyricText(payload, "romalrc") }

        val doc = LyricDiag.parse(
            sourceName,
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
        ) ?: return LyricOutcome.TransientFailure(null)
        // 上面那格已经覆盖了"平台给的是空歌词体"这条确定结论；走到这里说明歌词体**非空**
        // 而我们没解析出行——那是"这次没读出来"，不是"平台没有歌词"。
        // 判成 NoLyrics 会下发纯音乐占位，把一首有歌词的曲目钉死

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
        readCached(songId)?.let { return it }

        // 首选 EAPI 端点：字段最全（含 yrc / yromalrc）
        //
        // ⚠️ 这里的 lv/tv/rv/yv/ytv/yrv 取 **0 表示"要这一路"**，不是"关掉"。
        // 曾经有人（包括本项目的重构）看到一串 0 就以为是全部禁用、打算改成 1——
        // 实测证伪：0 与 1 与 -1 的响应**逐字节相同**，而 0 确实能拿到
        // yrc / ytlrc / yromalrc 三路逐字载荷。**不要动这几个参数。**
        // 若某首歌没有 yrc，是那首歌本身没有逐字歌词，不是参数写错。
        val primary = post(
            url = URL_V1,
            body = "id=$songId&cp=false&tv=0&lv=0&rv=0&kv=0&yv=0&ytv=0&yrv=0",
        )
        primary?.let {
            LyricDiag.dumpNeteaseChannels("NeteaseApi/V1", URL_V1, it)
            if (hasAnyLyricChannel(it)) return cache(songId, it)
        }

        // 退回老端点
        val legacy = get("$URL_LEGACY?id=$songId&lv=1&kv=1&tv=-1&rv=1")
        legacy?.let {
            LyricDiag.dumpNeteaseChannels("NeteaseApi/Legacy", URL_LEGACY, it)
            if (hasAnyLyricChannel(it)) return cache(songId, it)
        }
        return legacy ?: primary
    }

    /**
     * 读磁盘缓存
     *
     * 只有**真正带了歌词通道**的响应才会被写盘（见 [cache]），因此命中后仍需复核一遍：
     * 缓存文件损坏、或写入的只是空壳时，删掉该条目并回落网络，绝不把
     * "缓存不可用"当成"平台没有歌词"——后者是终态，会把纯音乐占位钉死。
     */
    private fun readCached(songId: Long): JSONObject? {
        val key = songId.toString()
        val bytes = LyricCacheStore.getBytes(null, PROVIDER_NAME, key) ?: return null

        val payload = runCatching { JSONObject(String(bytes, StandardCharsets.UTF_8)) }.getOrNull()
        if (payload != null && hasAnyLyricChannel(payload)) {
            AndroidLog.logI(sourceName, "Lyric disk cache hit for id=$key")
            LyricDiag.dumpNeteaseChannels("NeteaseApi/Disk", key, payload)
            return payload
        }

        AndroidLog.logW(sourceName, "Discarding unusable cached payload for id=$key")
        LyricCacheStore.delete(null, PROVIDER_NAME, key)
        return null
    }

    /** 写盘并原样返回，便于在取词路径上内联调用 */
    private fun cache(songId: Long, payload: JSONObject): JSONObject {
        LyricCacheStore.putAsync(null, PROVIDER_NAME, songId.toString(), payload.toString())
        return payload
    }

    /**
     * 判断响应里是否真的带了任何一路歌词
     *
     * V1 端点若版本参数全为 0，服务端会返回结构完整但**内容全空**的 JSON
     */
    private fun hasAnyLyricChannel(payload: JSONObject): Boolean =
        NET_LYRIC_KEYS.any { key -> lyricText(payload, key).isNotEmpty() }

    /**
     * 检索结果
     *
     * 三者都不构成"这首没有歌词"这一结论：平台从未就**曲目**表态，它只回答了"这次检索的
     * 结果"。因此 [NoCandidate] 与 [Failed] 都归为可重试失败——把检索落空当终态，等于用
     * 一次搜不到替平台断言曲目无歌词
     */
    private sealed interface SearchOutcome {
        data class Found(val songId: Long) : SearchOutcome

        /** 平台检索返回空集：**我们**没搜到，不是平台说这首没有歌词 */
        data object NoCandidate : SearchOutcome

        /** 这次没查成：没标题、报文畸形、或候选里没有一个能采信 */
        data object Failed : SearchOutcome
    }

    private fun searchSongId(identity: TrackIdentity): SearchOutcome {
        if (identity.title.isBlank()) return SearchOutcome.Failed

        val keyword = if (identity.artist.isBlank()) identity.title else "${identity.title} ${identity.artist}"
        val json = get("$URL_SEARCH?s=${URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())}&type=1&limit=5")
            ?: return SearchOutcome.Failed

        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return SearchOutcome.Failed
        if (songs.length() == 0) return SearchOutcome.NoCandidate

        // 网易云接口不返回时长，无法做时长校验；只接受歌名完全一致的候选，
        // 且必须**同时比对歌手**——同名不同曲配上别人的歌词是本项目最严重的可见缺陷。
        // 目标歌手未知时 artistCompatible 恒真，不会因此收窄
        val normalizedTitle = identity.title.trim()
        for (index in 0 until songs.length()) {
            val song = songs.optJSONObject(index) ?: continue
            if (song.optString("name", "").trim() != normalizedTitle) continue
            if (!TrackIdentity.artistCompatible(identity.artist, songArtists(song))) continue

            val id = song.optLong("id", 0L)
            if (id > 0L) return SearchOutcome.Found(id)
        }
        AndroidLog.logD(sourceName, "No credible NetEase candidate for \"$keyword\"")
        return SearchOutcome.Failed
    }

    /** 候选的歌手名；接口未给出 artists 数组时返回空串（此时不做歌手约束） */
    private fun songArtists(song: JSONObject): String {
        val artists = song.optJSONArray("artists") ?: return ""
        val names = ArrayList<String>(artists.length())
        for (index in 0 until artists.length()) {
            artists.optJSONObject(index)?.optString("name", "")
                ?.takeIf { it.isNotBlank() }?.let { names += it }
        }
        return names.joinToString("/")
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
            if (!response.isSuccessful) null else response.body.readTextBounded().let(::JSONObject)
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
            if (!response.isSuccessful) null else response.body.readTextBounded().let(::JSONObject)
        }
    } catch (t: Throwable) {
        AndroidLog.logW(sourceName, "GET failed: ${t.message}")
        null
    }

    /**
     * 从宿主 WebView 取登录凭证以解锁 VIP 曲目的完整逐字，取不到不影响主流程
     */
    private fun hostCookie(): String? = runCatching {
        CookieManager.getInstance().getCookie("https://music.163.com")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private companion object {
        /** 磁盘缓存命名空间，须与 `LyricCacheStore.ONLINE_PROVIDERS` 一致 */
        const val PROVIDER_NAME = "Netease"

        const val URL_V1 = "https://interface3.music.163.com/api/song/lyric/v1"
        const val URL_LEGACY = "https://music.163.com/api/song/lyric"
        const val URL_SEARCH = "https://music.163.com/api/search/get"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

        val NUMERIC_ID = Regex("^\\d{1,15}$")

        /** 接口可能返回的全部歌词通道，用于判断响应是否为空壳 */
        val NET_LYRIC_KEYS = listOf("yrc", "lrc", "ytlrc", "tlyric", "yromalrc", "romalrc")

        /**
         * 取词的整体时限预算
         *
         * read 超时只约束单次读，每次读到数据就重置，挡不住"永远挤一点数据"的服务端；
         * callTimeout 给整次调用封顶。与 QQ / 酷狗的 4/6/12 一致，Spotify 另有一套更大的值
         */
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
