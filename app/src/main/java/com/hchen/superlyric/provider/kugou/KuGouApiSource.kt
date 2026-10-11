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
package com.hchen.superlyric.provider.kugou

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.match.LyricScorer
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.kernel.decode.DecodeException
import com.hchen.superlyric.kernel.decode.PayloadBytes

import org.json.JSONArray
import org.json.JSONObject

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 酷狗开放接口取词来源：官方 KRC 接口的直查与检索。
 *
 * <h3>接口</h3>
 * <ul>
 *   <li>检索：{@code mobilecdn.kugou.com/api/v3/search/song}（歌名 + 歌手 → 候选 Hash）</li>
 *   <li>取歌词 id / accesskey：{@code krcs.kugou.com/search?hash=…}</li>
 *   <li>下载：{@code krcs.kugou.com/download?id=…&accesskey=…&fmt=krc}</li>
 * </ul>
 * 三个接口均无需鉴权，长期稳定。
 *
 * <h3>结果语义（决定是否允许下发纯音乐占位）</h3>
 * <ul>
 *   <li>拿到并解析成功 → [LyricOutcome.Success]；</li>
 *   <li>请求成功但平台明确没有这条曲目的歌词 → [LyricOutcome.NoLyrics]（终态）；</li>
 *   <li>网络异常或报文解码失败 → [LyricOutcome.TransientFailure]（可重试，<b>绝不</b>下发占位）。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
class KuGouApiSource : LyricSource {

    override val sourceName: String = "KugouApi"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-KugouApi").apply { isDaemon = true }
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
        val hash = hashOf(identity) ?: searchBestHash(identity) ?: return LyricOutcome.NoLyrics("kugou: no candidate")

        val krcPayload = downloadKrc(hash, identity.durationMs)
            ?: return LyricOutcome.NoLyrics("kugou: no lyric for hash $hash")

        val bytes = PayloadBytes.decode(krcPayload)
        if (bytes.isEmpty()) return LyricOutcome.NoLyrics("kugou: empty payload")

        val doc = try {
            LyricPipeline.parse(
                LyricRequest(
                    format = LyricFormat.KRC,
                    bytes = bytes,
                    title = identity.title,
                    artist = identity.artist,
                    durationMs = identity.durationMs,
                    lyricId = hash,
                )
            )
        } catch (e: DecodeException) {
            // 解不开是"这次没拿到"，不是"没有歌词"
            AndroidLog.logW(sourceName, "KRC decode failed: ${e.message}")
            return LyricOutcome.TransientFailure(e)
        }

        if (doc == null || doc.isEmpty) return LyricOutcome.NoLyrics("kugou: parsed empty")

        AndroidLog.logI(sourceName, "Fetched KRC for hash=$hash, lines=${doc.lines.size}")
        return LyricOutcome.Success(doc)
    }

    private fun hashOf(identity: TrackIdentity): String? =
        TrackIdentity.normalizeStableId(identity.stableId)?.takeIf { HASH_PATTERN.matches(it) }

    /**
     * 按歌名 + 歌手检索候选，返回打分最高的 Hash。
     *
     * 采纳门槛 [MIN_ACCEPT_SCORE] 已按"多源检索保留但收紧"的要求，从原先的 80 分提高到 85 分。
     */
    private fun searchBestHash(identity: TrackIdentity): String? {
        if (identity.title.isBlank()) return null

        val keyword = if (identity.artist.isBlank()) identity.title else "${identity.title} ${identity.artist}"
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&page=1&pagesize=5&keyword=" +
            URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())

        val json = get(url) ?: return null
        val info = JSONObject(json).optJSONObject("data")?.optJSONArray("info") ?: return null

        var bestHash: String? = null
        var bestScore = MIN_ACCEPT_SCORE

        for (index in 0 until info.length()) {
            val item = info.optJSONObject(index) ?: continue
            val hash = item.optString("hash", "")
            if (hash.isEmpty()) continue

            val score = LyricScorer.scoreCandidate(
                identity.title,
                identity.artist,
                identity.durationMs,
                item.optString("songname", ""),
                item.optString("singername", ""),
                item.optLong("duration", 0L) * 1000L,
                true,
            )
            if (score >= bestScore) {
                bestScore = score
                bestHash = hash
            }
        }

        if (bestHash == null) {
            AndroidLog.logD(sourceName, "No KuGou candidate reached the acceptance threshold for \"$keyword\"")
        }
        return bestHash
    }

    private fun downloadKrc(hash: String, durationMs: Long): String? {
        val searchUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=$durationMs&hash=$hash"
        val searchJson = get(searchUrl) ?: return null
        val candidates = JSONObject(searchJson).optJSONArray("candidates")
        val first = firstObject(candidates) ?: return null

        val id = first.optString("id", "")
        val accessKey = first.optString("accesskey", "")
        if (id.isEmpty() || accessKey.isEmpty()) return null

        val downloadUrl = "http://krcs.kugou.com/download?ver=1&client=mobi" +
            "&id=$id&accesskey=$accessKey&fmt=krc&charset=utf8"
        val downloadJson = get(downloadUrl) ?: return null

        return JSONObject(downloadJson).optString("content", "").ifEmpty { null }
    }

    private fun firstObject(array: JSONArray?): JSONObject? =
        if (array == null || array.length() == 0) null else array.optJSONObject(0)

    private fun get(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        httpClient.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    } catch (t: Throwable) {
        AndroidLog.logW(sourceName, "HTTP failed: ${t.message}")
        null
    }

    private companion object {
        const val USER_AGENT = "KuGou2012-7093-Android601"

        /** 收紧后的采纳门槛；旧实现为 80.0。 */
        const val MIN_ACCEPT_SCORE = 85.0

        val HASH_PATTERN = Regex("^[0-9a-fA-F]{32}$")

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
