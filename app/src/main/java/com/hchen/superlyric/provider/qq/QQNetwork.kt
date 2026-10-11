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
import com.hchen.superlyric.utils.LyricDiag

import org.json.JSONArray
import org.json.JSONObject

import java.util.concurrent.TimeUnit

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * QQ 音乐官方接口访问层，供 Hook 路径与 API 路径共用。
 *
 * <h3>三路载荷</h3>
 * {@code GetPlayLyricInfo} 同时返回三个**各自独立加密**的字段：
 * <ul>
 *   <li>{@code lyric}——原生 QRC 逐字报文（主歌词）</li>
 *   <li>{@code trans}——译文，解密后是 {@code [start,dur]译文} 形态</li>
 *   <li>{@code roma}——罗马音，解密后同样是 {@code [start,dur]} 形态</li>
 * </ul>
 * 三者都经 {@link QrcCipher} 解密（腾讯改造版 3DES + ZLib）。
 *
 * <p><b>旧实现请求了 {@code roma} 却从未读取它</b>，罗马音被整条丢弃。
 * 本实现把译文与罗马音分别送入翻译通道与音译通道，两者互不覆盖。
 *
 * @author 焕晨HChen
 */
internal object QQNetwork {

    private const val TAG = "QQNetwork"

    private const val URL_PRIMARY = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val URL_FALLBACK = "https://shu6.y.qq.com/cgi-bin/musicu.fcg"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 采纳门槛；旧实现为 60.0，按"多源检索收紧"的要求提高。 */
    private const val MIN_ACCEPT_SCORE = 85.0

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** (ct, cv, User-Agent) */
    private val clientProfiles = listOf(
        Triple("19", "1873", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) QQMusic/18.73"),
        Triple("11", "120000", "QQMusic/12.0.0.0 (Android)"),
    )

    /**
     * 按 songMID / songID 精准拉取并解析。
     *
     * 依次尝试"移动端 + 桌面端"两套入口与两套客户端标识，任一命中即返回。
     */
    fun fetchByMid(songMid: String, songId: Long, identity: TrackIdentity): LyricOutcome {
        if (songMid.isBlank() && songId <= 0L) return LyricOutcome.NoLyrics("qq: no identifier")

        var lastFailure: Throwable? = null
        var reachedServer = false

        for (url in listOf(URL_PRIMARY, URL_FALLBACK)) {
            for ((ct, cv, userAgent) in clientProfiles) {
                try {
                    val body = buildLyricRequest(ct, cv, songMid, songId)
                    val json = post(url, body, userAgent) ?: continue
                    reachedServer = true

                    val data = JSONObject(json).optJSONObject("req_0")?.optJSONObject("data")
                        ?: continue

                    val lyricRaw = data.optString("lyric", "")
                    if (lyricRaw.isEmpty()) continue

                    val outcome = parsePayloads(lyricRaw, data, identity)
                    if (outcome != null) return outcome
                } catch (t: Throwable) {
                    lastFailure = t
                    AndroidLog.logW(TAG, "fetchByMid ($url) failed: ${t.message}")
                }
            }
        }

        return when {
            lastFailure != null -> LyricOutcome.TransientFailure(lastFailure)
            reachedServer -> LyricOutcome.NoLyrics("qq: platform returned no lyric")
            else -> LyricOutcome.TransientFailure(null)
        }
    }

    /**
     * 按歌名 + 歌手检索，返回打分最高的 {@code mid to id}。
     *
     * @return 未达到 [MIN_ACCEPT_SCORE] 时返回 null
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

        val json = post(URL_PRIMARY, payload.toString(), ANDROID_UA) ?: return null
        val body = JSONObject(json).optJSONObject("req_0")?.optJSONObject("data")
            ?.optJSONObject("body") ?: return null

        val songs: JSONArray = body.optJSONArray("item_song")
            ?: body.optJSONObject("song")?.optJSONArray("list")
            ?: return null

        var best: Pair<String, Long>? = null
        var bestScore = MIN_ACCEPT_SCORE

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
                true,
            )
            if (score >= bestScore) {
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
     * 解密三路载荷并组装文档。
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
     * 解密单个字段。
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

    private fun readSingers(item: JSONObject): String {
        val singers = item.optJSONArray("singer") ?: return ""
        val names = ArrayList<String>(singers.length())
        for (index in 0 until singers.length()) {
            val name = singers.optJSONObject(index)?.optString("name", "") ?: continue
            if (name.isNotEmpty()) names += name
        }
        return names.joinToString("/")
    }

    private fun post(url: String, body: String, userAgent: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    } catch (t: Throwable) {
        AndroidLog.logW(TAG, "HTTP failed: ${t.message}")
        null
    }

    private const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
}
