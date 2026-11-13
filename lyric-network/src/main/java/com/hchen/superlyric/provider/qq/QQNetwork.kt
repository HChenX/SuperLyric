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
import com.hchen.superlyric.network.HttpClients
import com.hchen.superlyric.network.readTextBounded
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.nio.charset.StandardCharsets

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * QQ 音乐官方接口访问层，供 Hook 路径与 API 路径共用
 *
 * 三路载荷
 * `GetPlayLyricInfo` 同时返回三个各自独立加密的字段：
 * - `lyric`：原生 QRC 逐字报文（主歌词）
 * - `trans`：译文，解密后是 `[start,dur]译文` 形态
 * - `roma`：罗马音，解密后同样是 `[start,dur]` 形态
 */
object QQNetwork {

    private const val TAG = "QQNetwork"

    private const val URL_PRIMARY = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val URL_FALLBACK = "https://shu6.y.qq.com/cgi-bin/musicu.fcg"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
    fun fetchByMid(
        songMid: String,
        songId: Long,
        identity: TrackIdentity,
        isCurrent: () -> Boolean = { true },
        requests: FetchSlot? = null,
    ): LyricOutcome {
        // 没有标识时未发出任何查询，不构成平台结论。判成 NoLyrics 会为一次查询失败
        // 下发纯音乐占位
        if (songMid.isBlank() && songId <= 0L) return LyricOutcome.Unsupported("qq: no identifier")

        val cacheKey = songMid.trim().ifBlank { songId.toString() }
        readCached(cacheKey, identity)?.let { return it }

        var lastFailure: Throwable? = null
        var reachedServer = false

        // 是否见过非空的 lyric 字段。它与服务端没有 lyric 字段必须分开：
        // 前者说明服务端有歌词而本模块没解开（解密/解析失败），后者才是平台确定没有歌词
        var sawLyricPayload = false

        for (url in listOf(URL_PRIMARY, URL_FALLBACK)) {
            for ((ct, cv, userAgent) in clientProfiles) {
                if (!isCurrent()) return LyricOutcome.TransientFailure(null)
                try {
                    val body = buildLyricRequest(ct, cv, songMid, songId)
                    val json = post(url, body, userAgent, requests)
                    reachedServer = true

                    val root = JSONObject(json)
                    val req0 = root.optJSONObject("req_0")
                    val code = req0?.optInt("code", 0) ?: root.optInt("code", 0)
                    val data = req0?.optJSONObject("data") ?: root.optJSONObject("data")
                    if (code != 0) {
                        // HTTP 200 不等于业务成功：风控、登录、参数错误都走这里。
                        // 这属于本次查询失败，不是平台没有歌词，必须可重试。
                        lastFailure = IllegalStateException("qq code=$code")
                        continue
                    }
                    if (data == null) {
                        lastFailure = IllegalStateException("qq: response has no data")
                        continue
                    }

                    val lyricRaw = data.optString("lyric", "")
                    if (lyricRaw.isEmpty()) continue
                    sawLyricPayload = true

                    if (!isCurrent()) return LyricOutcome.TransientFailure(null)
                    val outcome = parsePayloads(lyricRaw, data, identity)
                    if (outcome != null) {
                        // 只缓存已成功解析的响应（含 lyric / trans / roma 三路密文），
                        // 命中后连鉴权与两次端点尝试都省掉
                        LyricCacheStore.putAsync(null, LyricCacheStore.PROVIDER_QQ, cacheKey, data.toString())
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

            // 服务端给了 lyric 字段而本模块没有解开（解密失败或解析不出行）。
            // 这不构成平台无歌词的结论：判成无歌词会把有歌词的曲目钉成纯音乐占位。
            // 也不返回 null：调用方用 null 表达另一种含义
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
     * 命中后仍重新走一遍解密与解析：缓存损坏、或解析器变更导致既有响应无法解析时，
     * 删掉该条目并回落网络。缓存不可用不构成平台无歌词的结论：后者是终态，
     * 会把纯音乐占位钉死。
     */
    private fun readCached(key: String, identity: TrackIdentity): LyricOutcome? {
        val bytes = LyricCacheStore.getBytes(null, LyricCacheStore.PROVIDER_QQ, key) ?: return null

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
        LyricCacheStore.delete(null, LyricCacheStore.PROVIDER_QQ, key)
        return null
    }

    /**
     * 一条检索候选；打分与排序只依赖本结构，可在无 JSON / 无 HTTP 时单测
     */
    data class CandidateItem(
        val mid: String,
        val songId: Long,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    /**
     * 按歌名 + 歌手检索，返回达到采纳线的候选，按分数降序、同分保持检索顺序
     *
     * 同一首歌在平台曲库可能有多个 mid，歌词可用性各不相同；只取首位会在首位无歌词时
     * 把有歌词的曲目判成无歌词，因此返回全部过线候选由调用方逐个尝试。
     */
    fun searchCandidates(
        identity: TrackIdentity,
        requests: FetchSlot? = null,
        isCurrent: () -> Boolean = { true },
    ): List<CandidateItem> {
        if (identity.title.isBlank()) return emptyList()

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

        var json: String? = null
        for (url in listOf(URL_PRIMARY, URL_FALLBACK)) {
            if (!isCurrent()) return emptyList()
            json = runCatching { post(url, payload.toString(), ANDROID_UA, requests) }.getOrNull()
            if (json != null) break
        }
        val body = json?.let {
            JSONObject(it).optJSONObject("req_0")?.optJSONObject("data")
                ?.optJSONObject("body")
        } ?: return emptyList()

        val songs: JSONArray = body.optJSONArray("item_song")
            ?: body.optJSONObject("song")?.optJSONArray("list")
            ?: return emptyList()

        val ranked = rankCandidates(identity, parseCandidates(songs))
        if (ranked.isEmpty()) {
            AndroidLog.logD(TAG, "No QQMusic candidate reached the acceptance threshold for \"$keyword\"")
        }
        return ranked
    }

    /**
     * 打分并排序候选：只保留高于采纳线的，分数相同时保持传入顺序
     *
     * 同分取检索顺序而不是最后一个：同一曲目的不同版本可能登记为完全相同的歌名、歌手与时长，
     * 打分器无从区分，取最后一个会随机配上错误版本的歌词。
     */
    internal fun rankCandidates(
        identity: TrackIdentity,
        candidates: List<CandidateItem>,
    ): List<CandidateItem> =
        candidates
            .map { candidate ->
                candidate to LyricScorer.scoreCandidate(
                    identity.title,
                    identity.artist,
                    identity.durationMs,
                    candidate.title,
                    candidate.artist,
                    candidate.durationMs,
                )
            }
            .filter { it.second > LyricScorer.MIN_ACCEPT_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun parseCandidates(songs: JSONArray): List<CandidateItem> {
        val candidates = ArrayList<CandidateItem>(songs.length())
        for (index in 0 until songs.length()) {
            val item = songs.optJSONObject(index) ?: continue
            val mid = item.optString("mid", item.optString("songmid", ""))
            if (mid.isEmpty()) continue
            candidates += CandidateItem(
                mid = mid,
                songId = item.optLong("id", item.optLong("songid", 0L)),
                title = item.optString("title", item.optString("songname", "")),
                artist = readSingers(item),
                durationMs = item.optLong("interval", 0L) * 1000L,
            )
        }
        return candidates
    }

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

        // 译文/罗马音是可选通道：任一路解不开不应拖垮已经成功解出的主歌词。
        val translationText = runCatching { decryptField(data.optString("trans", "")) }
            .onFailure { AndroidLog.logW(TAG, "qq: trans channel undecodable: ${it.message}") }
            .getOrNull()
        val phoneticText = runCatching { decryptField(data.optString("roma", "")) }
            .onFailure { AndroidLog.logW(TAG, "qq: roma channel undecodable: ${it.message}") }
            .getOrNull()

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
     * 与 `title` 一样做多键兜底：只认一种键的形状，平台换一次 schema 就会让歌手分
     * 静默退化成常量，而歌手占 30% 的权重。
     *
     * 判定按值的类型分派，不按取出的串是否为空分派：`optString` 只在值本身是 String
     * 时返回它，遇到数组或对象会走 `JSON.toString`，返回一段非空白的 JSON 文本
     * （`[{"name":"周杰伦"}]`）。若按空串判定，数组形状的候选会被 JSON 文本截住，
     * 歌手分恒为 0，满分从 100 掉到 70、低于采纳线，合法匹配全部不采纳
     */
    private fun readSingers(item: JSONObject): String {
        val value = item.opt("singer")
        if (value is JSONArray) return joinSingerArray(value)

        val fallback = item.optJSONArray("singers")
        if (fallback != null) return joinSingerArray(fallback)

        // 到这里值只可能是字符串（或压根没有）。平台也可能直接给一个歌手名
        val single = if (value is String) value else item.optString("singername", "")
        return single.takeIf { it.isNotBlank() } ?: ""
    }

    /** 数组形状的歌手：元素可能是 `{"name":…}` 对象，也可能是裸字符串 */
    private fun joinSingerArray(array: JSONArray): String {
        val names = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val name = when (val element = array.opt(index)) {
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
     * 传输层失败必须抛出：网络异常不是"服务端没有歌词"。抛出后由调用方归类为
     * 可重试失败；只有业务响应里确实没有 lyric 字段，才由 `parsePayloads` 返回 null。
     */
    private fun post(
        url: String,
        body: String,
        userAgent: String,
        requests: FetchSlot?,
    ): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", userAgent)
            .build()
        val call: Call = HttpClients.standard.newCall(request)
        val execute: () -> String = {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
                response.body.readTextBounded()
            }
        }
        return requests?.withCall(call, execute) ?: execute()
    }

    private const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
}
