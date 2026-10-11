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
package com.hchen.superlyric.provider.qq

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.match.LyricScorer
import com.hchen.superlyric.kernel.decode.DecodeException
import com.hchen.superlyric.kernel.decode.PayloadBytes
import com.hchen.superlyric.kernel.decode.QrcCipher
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.readTextBounded
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * QQ 音乐官方接口访问层，供 Hook 路径与 API 路径共用
 *
 * ## 三路载荷
 * `GetPlayLyricInfo` 同时返回三个**各自独立加密**的字段：
 * - `lyric`——原生 QRC 逐字报文（主歌词）
 * - `trans`——译文，解密后是 `[start,dur]译文` 形态
 * - `roma`——罗马音，解密后同样是 `[start,dur]` 形态
 *
 * 三者都经 [QrcCipher] 解密（腾讯改造版 3DES + ZLib）。
 *
 * <p>译文与罗马音分别送入翻译通道与音译通道，两者互不覆盖。
 *
 * @author 焕晨HChen
 */
internal object QQNetwork {

    private const val TAG = "QQNetwork"

    private const val URL_PRIMARY = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val URL_FALLBACK = "https://shu6.y.qq.com/cgi-bin/musicu.fcg"

    /** 磁盘缓存命名空间，须与 `LyricCacheStore.ONLINE_PROVIDERS` 一致 */
    private const val PROVIDER_NAME = "QQ"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 取词的整体时限预算
     *
     * connect/read 都只约束**单次操作**：read 超时在每次读到数据后都会重置，
     * 一个"永远挤一点数据"的服务端能把请求拖到无限久。callTimeout 给整次调用封顶——
     * 取词跑在来源自己的线程上，拖住它就是拖住后面的每一次切歌
     *
     * 与 Spotify 的 15/15/30 不同是**有意**的：那家要跑 protobuf 分析且服务端更慢，
     * 这里的三家只取 JSON
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** (ct, cv, User-Agent) */
    private val clientProfiles = listOf(
        Triple("19", "1873", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) QQMusic/18.73"),
        Triple("11", "120000", "QQMusic/12.0.0.0 (Android)"),
    )

    /**
     * 按 songMID / songID 精准拉取并解析
     *
     * 依次尝试"移动端 + 桌面端"两套入口与两套客户端标识，任一命中即返回。
     */
    fun fetchByMid(songMid: String, songId: Long, identity: TrackIdentity): LyricOutcome {
        // 没有标识就压根没查询过，谈不上"平台说没有歌词"：它是"我们查不了"。
        // 判成 NoLyrics 会为一次查不到下发纯音乐占位
        if (songMid.isBlank() && songId <= 0L) return LyricOutcome.Unsupported("qq: no identifier")

        val cacheKey = songMid.trim().ifBlank { songId.toString() }
        readCached(cacheKey, identity)?.let { return it }

        var lastFailure: Throwable? = null
        var reachedServer = false

        // 是否见过**非空**的 lyric 字段。它与"服务端没有 lyric 字段"必须分开：
        // 前者说明服务端有歌词而我们没解开（解密/解析失败），后者才是"平台确定没有歌词"
        var sawLyricPayload = false

        for (url in listOf(URL_PRIMARY, URL_FALLBACK)) {
            for ((ct, cv, userAgent) in clientProfiles) {
                try {
                    val body = buildLyricRequest(ct, cv, songMid, songId)
                    val json = post(url, body, userAgent)
                    reachedServer = true

                    val data = JSONObject(json).optJSONObject("req_0")?.optJSONObject("data")
                        ?: continue

                    val lyricRaw = data.optString("lyric", "")
                    if (lyricRaw.isEmpty()) continue
                    sawLyricPayload = true

                    val outcome = parsePayloads(lyricRaw, data, identity)
                    if (outcome != null) {
                        // 只缓存**已成功解析**的响应（含 lyric / trans / roma 三路密文），
                        // 命中后连鉴权与两次端点尝试都省掉
                        LyricCacheStore.putAsync(null, PROVIDER_NAME, cacheKey, data.toString())
                        return outcome
                    }
                } catch (t: Throwable) {
                    lastFailure = t
                    AndroidLog.logW(TAG, "fetchByMid ($url) failed: ${t.message}")
                }
            }
        }

        return when {
            lastFailure != null -> LyricOutcome.TransientFailure(lastFailure)

            // 服务端**给了** lyric 字段，是我们没解开（解密失败或解析不出行）。
            // 这不是"平台没有歌词"：把它当无歌词，会把一首有歌词的曲目钉成纯音乐占位。
            // 也不要返回 null——调用方用 null 表达另一种含义
            sawLyricPayload -> {
                AndroidLog.logW(
                    TAG,
                    "Platform returned a lyric payload that could not be decoded; treating it as retryable",
                )
                LyricOutcome.TransientFailure(null)
            }

            // 依次试完所有入口都到达了服务端，且都不是"A 有 B 无"或"解不开"：
            // 这才是平台确定没有歌词
            reachedServer -> LyricOutcome.NoLyrics("qq: platform returned no lyric")

            else -> LyricOutcome.TransientFailure(null)
        }
    }

    /**
     * 读磁盘缓存并复核
     *
     * 命中后仍重新走一遍解密 + 解析：缓存损坏、或解析器变更导致旧响应不再可解析时，
     * 删掉该条目并回落网络。**绝不**把"缓存不可用"当成"平台没有歌词"——
     * 后者是终态，会把无歌词占位钉死。
     */
    private fun readCached(key: String, identity: TrackIdentity): LyricOutcome? {
        val bytes = LyricCacheStore.getBytes(null, PROVIDER_NAME, key) ?: return null

        val data = runCatching { JSONObject(String(bytes, StandardCharsets.UTF_8)) }.getOrNull()
        val outcome = data?.let {
            runCatching { parsePayloads(it.optString("lyric", ""), it, identity) }
                .onFailure { error -> AndroidLog.logW(TAG, "Cached payload unusable: ${error.message}") }
                .getOrNull()
        }
        if (outcome != null) {
            AndroidLog.logI(TAG, "Lyric disk cache hit for mid=$key")
            return outcome
        }

        AndroidLog.logW(TAG, "Discarding unusable cache entry for mid=$key")
        LyricCacheStore.delete(null, PROVIDER_NAME, key)
        return null
    }

    /**
     * 按歌名 + 歌手检索，返回打分最高的 `mid to id`
     *
     * @return 未达到 [LyricScorer.MIN_ACCEPT_SCORE] 时返回 null
     */
    fun searchBest(identity: TrackIdentity): Pair<String, Long>? {
        if (identity.title.isBlank()) return null

        val keyword = if (identity.artist.isBlank()) identity.title else "${identity.title} ${identity.artist}"

        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("ct", "11")
                put("cv", "1003006")
                put("v", "1003006")
                put("tmeAppID", "qqmusiclight")
                put("nettype", "NETWORK_WIFI")
            })
            put("req_0", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicLite")
                put("param", JSONObject().apply {
                    put("query", keyword)
                    put("search_type", 0)
                    put("num_per_page", 5)
                    put("page_num", 1)
                    put("grp", 1)
                })
            })
        }

        val json = post(URL_PRIMARY, payload.toString(), ANDROID_UA)
        val body = JSONObject(json).optJSONObject("req_0")?.optJSONObject("data")
            ?.optJSONObject("body") ?: return null

        val songs: JSONArray = body.optJSONArray("item_song")
            ?: body.optJSONObject("song")?.optJSONArray("list")
            ?: return null

        var best: Pair<String, Long>? = null
        var bestScore = LyricScorer.MIN_ACCEPT_SCORE

        for (index in 0 until songs.length()) {
            val item = songs.optJSONObject(index) ?: continue
            val mid = item.optString("mid", item.optString("songmid", ""))
            if (mid.isEmpty()) continue

            val score = LyricScorer.scoreCandidate(
                identity.title,
                identity.artist,
                identity.durationMs,
                item.optString("title", item.optString("songname", "")),
                readSingers(item),
                item.optLong("interval", 0L) * 1000L,
            )
            // 用 `>` 而不是 `>=`：同分时采纳检索的排序首位。
            // 同一首歌的不同版本可能登记成完全相同的歌名/歌手/时长，打分器无从区分，
            // 平局只能靠顺序决定——取最后一个会随机配上错误版本的歌词。
            if (score > bestScore) {
                bestScore = score
                best = mid to item.optLong("id", item.optLong("songid", 0L))
            }
        }

        if (best == null) {
            AndroidLog.logD(TAG, "No QQMusic candidate reached the acceptance threshold for \"$keyword\"")
        }
        return best
    }

    // ------------------------------------------------------------------ 内部

    private fun buildLyricRequest(ct: String, cv: String, songMid: String, songId: Long): String =
        JSONObject().apply {
            put("comm", JSONObject().apply {
                put("ct", ct)
                put("cv", cv)
                put("uin", "0")
            })
            put("req_0", JSONObject().apply {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                put("param", JSONObject().apply {
                    put("songMID", songMid.trim())
                    if (songId > 0L) put("songID", songId)
                    put("musicType", 0)
                    put("crypt", 1)
                    put("qrc", 1)
                    put("trans", 1)
                    put("roma", 1)
                })
            })
        }.toString()

    /**
     * 解密三路载荷并组装文档
     *
     * @return 成功时返回结果；当前 profile 未命中时返回 null 以便继续尝试其它入口
     */
    private fun parsePayloads(lyricRaw: String, data: JSONObject, identity: TrackIdentity): LyricOutcome? {
        LyricDiag.dumpRawPreview(TAG, "ENCRYPTED-lyric", lyricRaw)
        LyricDiag.dumpRawPreview(TAG, "ENCRYPTED-trans", data.optString("trans", ""))
        LyricDiag.dumpRawPreview(TAG, "ENCRYPTED-roma", data.optString("roma", ""))

        val mainText = decryptField(lyricRaw) ?: return null

        val translationText = decryptField(data.optString("trans", ""))
        val phoneticText = decryptField(data.optString("roma", ""))

        val doc = LyricDiag.parse(
            TAG,
            LyricRequest(
                format = LyricFormat.QRC,
                text = mainText,
                translationText = translationText,
                phoneticText = phoneticText,
                title = identity.title,
                artist = identity.artist,
                durationMs = identity.durationMs,
                lyricId = identity.stableId,
            )
        ) ?: return null

        AndroidLog.logI(
            TAG,
            "Fetched QRC: lines=${doc.lines.size}, " +
                "translation=${doc.lines.any { it.hasTranslation }}, " +
                "phonetic=${doc.lines.any { it.hasPhonetic }}"
        )
        return LyricOutcome.Success(doc)
    }

    /**
     * 解密单个字段
     *
     * @return 解密后的明文；字段为空时返回 null；解密失败时抛 [DecodeException]
     */
    @Throws(DecodeException::class)
    private fun decryptField(raw: String): String? {
        if (raw.isEmpty()) return null
        val bytes = PayloadBytes.decode(raw)
        if (bytes.isEmpty()) return null
        return QrcCipher.decrypt(bytes)
    }

    /**
     * 取候选的歌手名
     *
     * 与 `title` 同样做多键兜底：只认一种键的形状，平台换一次 schema 就会让歌手分
     * 静默退化成常量，而歌手是 35% 的权重
     */
    private fun readSingers(item: JSONObject): String {
        item.optString("singer", "").takeIf { it.isNotBlank() }?.let { return it }
        item.optString("singername", "").takeIf { it.isNotBlank() }?.let { return it }

        val array = item.optJSONArray("singer") ?: item.optJSONArray("singers") ?: return ""
        val names = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val element = array.opt(index)
            val name = when (element) {
                is JSONObject -> element.optString("name", "")
                is String -> element
                else -> ""
            }
            if (name.isNotBlank()) names += name
        }
        return names.joinToString("/")
    }

    /**
     * 发起一次 POST
     *
     * **传输层失败必须抛出**，不能返回 null：`null` 在本模块的含义是
     * "服务端明确没有这条歌词"，把网络异常也压成 null 会让断网被误判为无歌词
     * 并下发纯音乐占位。抛出后由调用方归类为可重试失败。
     */
    private fun post(url: String, body: String, userAgent: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body.readTextBounded()
        }
    }

    private const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
}
