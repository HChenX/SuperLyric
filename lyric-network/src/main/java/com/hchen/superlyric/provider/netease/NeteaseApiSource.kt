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

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.network.HttpClients
import com.hchen.superlyric.network.readTextBounded
import com.hchen.superlyric.publisher.AbsPublisher
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

import org.json.JSONObject

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 网易云音乐官方接口取词来源
 *
 * 四路载荷
 * `/api/song/lyric/v1` 一次返回四份歌词，都是明文（无需解密）：
 * - `yrc`：逐字（YRC，绝对毫秒），优先；`lyric` 为 LRC，兜底
 * - `ytlrc` / `tlyric`：译文
 * - `yromalrc` / `romalrc`：罗马音
 */
class NeteaseApiSource : LyricSource {

    override val sourceName: String = "NeteaseApi"

    private val requests = FetchSlot()

    @Volatile
    private var cachedCookie: String? = null

    @Volatile
    private var cookieLoaded = false

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.launch(sourceName, session) { resolve(identity) }
    }

    override fun cancel() {
        requests.invalidate()
    }

    private fun resolve(identity: TrackIdentity): LyricOutcome {
        // 检索落空不等于平台确定没有歌词：同名版本、修饰后缀或被改写的字段都会导致
        // 检索失败。判成 NoLyrics 会给有歌词的曲目下发纯音乐占位
        val songId = songIdOf(identity) ?: searchSongId(identity)
        ?: return LyricOutcome.TransientFailure(null)

        val payload = fetchLyricPayload(songId)
            ?: return LyricOutcome.TransientFailure(null)

        // 平台明确标记无歌词
        if (payload.optBoolean("nolyric", false)) {
            return LyricOutcome.NoLyrics("netease: platform reports nolyric")
        }

        // `ifBlank` 而不是 `ifEmpty`：接口在无逐字歌词时会返回空白的 yrc，
        // `ifEmpty` 只认空串，"yrc 空白 + lrc 有效"的组合会丢掉有效的 lrc 并误判无歌词
        val main = lyricText(payload, "yrc").ifBlank { lyricText(payload, "lrc") }
        if (main.isBlank()) {
            // 不构成确定结论。平台对确实没有歌词的曲目会带 `nolyric` 标记，该情形已在上方返回；
            // 走到这里只说明两个端点的歌词通道都空，可能是空壳响应（参数、区域或鉴权导致
            // 本模块没取到），而不是平台就该曲目表态。判成 NoLyrics 会把有歌词的曲目
            // 标记为无歌词；此处按可重试失败处理
            AndroidLog.logW(sourceName, "No lyric channel in the primary or legacy response; treating as retryable")
            return LyricOutcome.TransientFailure(null)
        }

        val translation = lyricText(payload, "ytlrc").ifBlank { lyricText(payload, "tlyric") }
        val phonetic = lyricText(payload, "yromalrc").ifBlank { lyricText(payload, "romalrc") }

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
        // 歌词体非空而没有解析出行，说明本次没有读出来，不是平台没有歌词。
        // 判成 NoLyrics 会下发纯音乐占位，把有歌词的曲目钉死

        AndroidLog.logI(
            sourceName,
            "Fetched lyric for id=$songId, lines=${doc.lines.size}, " +
                    "wordTimed=${doc.lines.any { it.hasWords }}, " +
                    "translation=${doc.lines.any { it.hasTranslation }}, " +
                    "phonetic=${doc.lines.any { it.hasPhonetic }}"
        )
        return LyricOutcome.Success(doc)
    }

    private fun fetchLyricPayload(songId: Long): JSONObject? {
        readCached(songId)?.let { return it }

        // 首选 EAPI 端点：字段最全（含 yrc / yromalrc）
        //
        // lv/tv/rv/yv/ytv/yrv 取 0 表示请求该路载荷，不是关闭：实测 0、1、-1 三种取值
        // 的响应逐字节相同，而 0 确实能拿到 yrc / ytlrc / yromalrc 三路逐字载荷，
        // 因此这几个参数保持为 0。某首曲目没有 yrc 是该曲目本身没有逐字歌词，与参数无关。
        val primary = post(
            url = URL_V1,
            body = "id=$songId&cp=false&tv=0&lv=0&rv=0&kv=0&yv=0&ytv=0&yrv=0",
        )
        primary?.let {
            LyricDiag.dumpNeteaseChannels("NeteaseApi/V1", URL_V1, it)
            // nolyric 必须原样回给 resolve 才能形成 NoLyrics；翻译通道单独存在不算
            // 有歌词，缓存它会让该 id 在淘汰前每次都命中并失败。
            if (it.optBoolean("nolyric", false)) return it
            if (hasMainLyricChannel(it)) return cache(songId, it)
        }

        // 退回 legacy 端点
        val legacy = get("$URL_LEGACY?id=$songId&lv=1&kv=1&tv=-1&rv=1")
        legacy?.let {
            LyricDiag.dumpNeteaseChannels("NeteaseApi/Legacy", URL_LEGACY, it)
            if (it.optBoolean("nolyric", false)) return it
            if (hasMainLyricChannel(it)) return cache(songId, it)
        }
        return legacy ?: primary
    }

    /**
     * 读磁盘缓存
     *
     * 只有真正带了歌词通道的响应才会被写盘（见 [cache]），因此命中后仍需复核：
     * 缓存文件损坏或写入的只是空壳时，删掉该条目并回落网络。缓存不可用不构成
     * 平台没有歌词的结论：后者是终态，会把纯音乐占位钉死。
     */
    private fun readCached(songId: Long): JSONObject? {
        val key = songId.toString()
        val bytes = LyricCacheStore.getBytes(null, LyricCacheStore.PROVIDER_NETEASE, key) ?: return null

        val payload = runCatching { JSONObject(String(bytes, StandardCharsets.UTF_8)) }.getOrNull()
        if (payload != null && hasMainLyricChannel(payload)) {
            AndroidLog.logI(sourceName, "Lyric disk cache hit for id=$key")
            LyricDiag.dumpNeteaseChannels("NeteaseApi/Disk", key, payload)
            return payload
        }

        AndroidLog.logW(sourceName, "Discarding unusable cached payload for id=$key")
        LyricCacheStore.delete(null, LyricCacheStore.PROVIDER_NETEASE, key)
        return null
    }

    /** 写盘并原样返回，便于在取词路径上内联调用 */
    private fun cache(songId: Long, payload: JSONObject): JSONObject {
        LyricCacheStore.putAsync(null, LyricCacheStore.PROVIDER_NETEASE, songId.toString(), payload.toString())
        return payload
    }

    /**
     * 判断响应里是否真的带了任何一路歌词
     *
     * V1 端点若版本参数全为 0，服务端会返回结构完整但内容全空的 JSON。
     * 判据必须是 `isNotBlank` 而非 `isNotEmpty`：接口在无逐字歌词时会返回空白的
     * yrc，用 `isNotEmpty` 会把空白算作带歌词，于是不再回退 legacy 端点，
     * 最终把有歌词的曲目判成无歌词
     */
    private fun hasMainLyricChannel(payload: JSONObject): Boolean =
        lyricText(payload, "yrc").isNotBlank() || lyricText(payload, "lrc").isNotBlank()

    /** 按标题与歌手检索 songId；未命中或读取失败时返回 null，均按可重试失败处理 */
    private fun searchSongId(identity: TrackIdentity): Long? {
        if (identity.title.isBlank()) return null

        val keyword = if (identity.artist.isBlank()) identity.title else "${identity.title} ${identity.artist}"
        val json = get("$URL_SEARCH?s=${URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())}&type=1&limit=5")
            ?: run {
                AndroidLog.logW(sourceName, "Search request failed for \"$keyword\"")
                return null
            }

        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: run {
            AndroidLog.logW(sourceName, "Search response has no result.songs for \"$keyword\"")
            return null
        }
        if (songs.length() == 0) {
            AndroidLog.logD(sourceName, "No NetEase search candidate for \"$keyword\"")
            return null
        }

        // 网易云接口不返回时长，无法做时长校验；只接受歌名完全一致的候选，
        // 且必须同时比对歌手，避免同名不同曲配上他人的歌词。
        // 目标歌手未知时 artistCompatible 恒真，不会因此收窄
        val normalizedTitle = identity.title.trim()
        for (index in 0 until songs.length()) {
            val song = songs.optJSONObject(index) ?: continue
            if (song.optString("name", "").trim() != normalizedTitle) continue
            if (!TrackIdentity.artistCompatible(identity.artist, songArtists(song))) continue

            val id = song.optLong("id", 0L)
            if (id > 0L) return id
        }
        AndroidLog.logD(sourceName, "No credible NetEase candidate for \"$keyword\"")
        return null
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
        CatalogIds.lyricId(identity, LyricCatalog.NETEASE, CatalogIds::neteaseSongId)

    private fun lyricText(payload: JSONObject, key: String): String =
        payload.optJSONObject(key)?.optString("lyric", "").orEmpty()

    private fun post(url: String, body: String): JSONObject? = try {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(FORM_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com")
            .apply { hostCookie()?.let { header("Cookie", it) } }
            .build()

        val call = HttpClients.standard.newCall(request)
        requests.withCall(call) { call.execute() }.use { response ->
            if (!response.isSuccessful) {
                AndroidLog.logW(sourceName, "HTTP ${response.code} from ${request.url.host}")
                null
            } else {
                response.body.readTextBounded().let(::JSONObject)
            }
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

        val call = HttpClients.standard.newCall(request)
        requests.withCall(call) { call.execute() }.use { response ->
            if (!response.isSuccessful) {
                AndroidLog.logW(sourceName, "HTTP ${response.code} from ${request.url.host}")
                null
            } else {
                response.body.readTextBounded().let(::JSONObject)
            }
        }
    } catch (t: Throwable) {
        AndroidLog.logW(sourceName, "GET failed: ${t.message}")
        null
    }

    /**
     * 从宿主 WebView 取登录凭证以解锁 VIP 曲目的完整逐字，取不到不影响主流程
     */
    private fun hostCookie(): String? {
        cachedCookie?.let { return it }
        if (cookieLoaded) return null

        // CookieManager 首次初始化可能触发 WebView 副作用，不能在 FetchSlot 池线程直接触碰。
        val read = {
            runCatching {
                CookieManager.getInstance().getCookie("https://music.163.com")
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        val value = if (Looper.myLooper() == Looper.getMainLooper()) {
            read()
        } else {
            val context = AbsPublisher.getAppContext()
            if (context == null) {
                AndroidLog.logD(sourceName, "No app context; skipping host cookie")
                return null
            }
            val latch = CountDownLatch(1)
            var result: String? = null
            Handler(Looper.getMainLooper()).post {
                result = read()
                latch.countDown()
            }
            if (!latch.await(1, TimeUnit.SECONDS)) {
                AndroidLog.logD(sourceName, "Host cookie lookup timed out; continuing without it")
                return null
            }
            result
        }
        cookieLoaded = true
        cachedCookie = value
        return value
    }

    private companion object {
        const val URL_V1 = "https://interface3.music.163.com/api/song/lyric/v1"
        const val URL_LEGACY = "https://music.163.com/api/song/lyric"
        const val URL_SEARCH = "https://music.163.com/api/search/get"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    }
}
