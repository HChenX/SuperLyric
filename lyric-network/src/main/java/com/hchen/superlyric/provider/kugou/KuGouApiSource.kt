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
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.match.LyricScorer
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.kernel.decode.PayloadBytes
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.network.HttpClients
import com.hchen.superlyric.network.readTextBounded
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

import org.json.JSONObject

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

import okhttp3.Request
import okhttp3.Response

/**
 * 酷狗开放接口取词来源：官方 KRC 接口直查与检索
 *
 * 接口：
 * - 检索：`mobileservice.kugou.com/api/v3/search/song`（歌名 + 歌手 → 候选 Hash）
 * - 取歌词 id / accesskey：`krcs.kugou.com/search?hash=…`
 * - 下载：`krcs.kugou.com/download?id=…&accesskey=…&fmt=krc`
 */
class KuGouApiSource : LyricSource {

    override val sourceName: String = "KugouApi"

    private val requests = FetchSlot()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.launch(sourceName, session) { resolve(identity) }
    }

    override fun cancel() {
        requests.invalidate()
    }

    private fun resolve(identity: TrackIdentity): LyricOutcome {
        var lastFailure: Throwable? = null

        // 直查：宿主上报的哈希不一定是歌词哈希（同一曲目可能出现多个不同哈希），
        // 落空或解不开时必须回退到检索；否则有歌词的曲目会被误判为无歌词并下发纯音乐占位。
        val direct = hashOf(identity)
        if (direct != null) {
            when (val outcome = attempt(direct, identity)) {
                is LyricOutcome.Success -> return outcome
                is LyricOutcome.TransientFailure -> outcome.cause?.let { lastFailure = it }
                else -> Unit
            }
            AndroidLog.logD(
                sourceName,
                "Direct hash lookup yielded no lyric: $direct; falling back to keyword search",
            )
        }

        // 检索落空不等于平台确定没有歌词：可能只是未匹配上，或报文结构变化。
        // NoLyrics 允许下发纯音乐占位，一次误判就会把有歌词的曲目永久标记。
        val searchedHashes = searchBestHashes(identity)
        // 达到采纳线的候选都可能没有 KRC（平台只对部分曲目提供逐字歌词），
        // 单个候选失败不代表整首没有；按分数依次尝试，全部失败才返回可重试失败
        for (hash in searchedHashes) {
            when (val outcome = attempt(hash, identity)) {
                is LyricOutcome.Success -> return outcome
                is LyricOutcome.TransientFailure -> outcome.cause?.let { lastFailure = it }
                else -> Unit
            }
        }
        // 全部候选都拿不到可用歌词（下载失败、解码失败，或平台确实没有），
        // 不足以判定“这首歌没有歌词”
        return LyricOutcome.TransientFailure(lastFailure)
    }

    /**
     * 对单个哈希取词
     *
     * 返回 `null` 表示该哈希没有歌词；下载、解码或解析失败返回
     * [LyricOutcome.TransientFailure]，调用方继续尝试其它哈希
     */
    private fun attempt(hash: String, identity: TrackIdentity): LyricOutcome? {
        val cached = readCachedKrc(hash)
        return try {
            val krcPayload = cached ?: downloadKrc(hash, identity.durationMs)
            if (krcPayload == null) {
                AndroidLog.logD(sourceName, "No KRC payload for hash=$hash")
                return null
            }

            val bytes = PayloadBytes.decode(krcPayload)
            if (bytes.isEmpty()) {
                AndroidLog.logD(sourceName, "Empty KRC payload for hash=$hash")
                return null
            }

            val doc = LyricDiag.parse(
                sourceName,
                LyricRequest(
                    format = LyricFormat.KRC,
                    bytes = bytes,
                    title = identity.title,
                    artist = identity.artist,
                    durationMs = identity.durationMs,
                    lyricId = hash,
                )
            )
            if (doc == null || doc.isEmpty) {
                AndroidLog.logD(sourceName, "Parsed no lines for hash=$hash")
                return null
            }

            // 只缓存本次从网络取回且已验证可解析的载荷。命中缓存时不再回写，
            // 避免日志同时出现 HIT 与已缓存。
            if (cached == null) {
                LyricCacheStore.putAsync(null, LyricCacheStore.PROVIDER_KUGOU, hash, krcPayload)
            }
            AndroidLog.logI(sourceName, "Fetched KRC for hash=$hash, lines=${doc.lines.size}")
            LyricOutcome.Success(doc)
        } catch (e: Exception) {
            // 单个候选的下载、解码或解析失败只否定该哈希，不能终止整个候选遍历
            AndroidLog.logW(sourceName, "KRC candidate failed for hash=$hash: ${e.message}")
            // 损坏的缓存条目会让该哈希每次都失败：删除后下次回退到网络重取
            if (cached != null) LyricCacheStore.delete(null, LyricCacheStore.PROVIDER_KUGOU, hash)
            LyricOutcome.TransientFailure(e)
        }
    }

    private fun hashOf(identity: TrackIdentity): String? =
        CatalogIds.lyricId(identity, LyricCatalog.KUGOU, CatalogIds::kugouHash)

    /**
     * 按歌名 + 歌手检索候选，返回达到采纳线的 Hash（按分数降序）
     *
     * 采纳门槛见 [LyricScorer.MIN_ACCEPT_SCORE]。同分时保留检索顺序：
     * 酷狗曲库中同一首歌的不同版本可能登记为完全相同的歌名、歌手与时长，
     * 打分器无法区分；取最后一个会随机配上错误语言的歌词。
     */
    private fun searchBestHashes(identity: TrackIdentity): List<String> {
        if (identity.title.isBlank()) return emptyList()

        val keyword = if (identity.artist.isBlank()) identity.title else "${identity.title} ${identity.artist}"
        // 宿主可能按 targetSdk 禁用明文 HTTP，检索与取词都必须走 HTTPS
        val url = "https://mobileservice.kugou.com/api/v3/search/song?format=json&page=1&pagesize=5&keyword=" +
                URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())

        val json = get(url)
        val info = JSONObject(json).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()

        val accepted = ArrayList<Pair<String, Double>>(info.length())
        val candidates = ArrayList<String>(info.length())

        for (index in 0 until info.length()) {
            val item = info.optJSONObject(index) ?: continue
            // 该哈希来自响应而非本模块，必须与身份中的哈希使用同一正则约束：
            // 它会未经校验拼进请求 URL（`downloadKrc`）。该过滤同时排除形态不符的候选，
            // 减少必然失败的往返
            val hash = CatalogIds.kugouHash(item.optString("hash", "")) ?: continue

            val candidateTitle = item.optString("songname", "")
            val candidateArtist = item.optString("singername", "")
            val candidateDurationMs = item.optLong("duration", 0L) * 1000L
            val score = LyricScorer.scoreCandidate(
                identity.title,
                identity.artist,
                identity.durationMs,
                candidateTitle,
                candidateArtist,
                candidateDurationMs,
            )
            candidates += "\"$candidateTitle\"/\"$candidateArtist\"/${candidateDurationMs / 1000}s=" +
                    "${score.roundTo1()} hash=$hash"
            if (score > LyricScorer.MIN_ACCEPT_SCORE) accepted += hash to score
        }

        // 无论是否命中都要输出候选明细：仅看“通过/未通过”无法判断是检索未找对歌、
        // 正确版本不在候选内，还是打分将其淘汰；这三种情况的处理不同。
        AndroidLog.logD(
            sourceName,
            "Candidates for \"$keyword\" (target \"${identity.title}\"/\"${identity.artist}\"/" +
                    "${identity.durationMs / 1000}s): ${candidates.joinToString(" | ").ifEmpty { "none" }}",
        )

        if (accepted.isEmpty()) {
            AndroidLog.logD(sourceName, "No KuGou candidate reached the acceptance threshold for \"$keyword\"")
        }
        // 稳定排序：同分候选保持检索顺序，首位优先
        return accepted.sortedByDescending { it.second }.map { it.first }
    }

    private fun Double.roundTo1(): String = String.format(Locale.US, "%.1f", this)

    private fun downloadKrc(hash: String, durationMs: Long): String? {
        val searchUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=$durationMs&hash=$hash"
        val searchJson = get(searchUrl)
        val candidates = JSONObject(searchJson).optJSONArray("candidates")
        val first = candidates?.takeIf { it.length() > 0 }?.optJSONObject(0) ?: return null

        val id = first.optString("id", "")
        val accessKey = first.optString("accesskey", "")
        if (id.isEmpty() || accessKey.isEmpty()) return null

        val downloadUrl = "https://krcs.kugou.com/download?ver=1&client=mobi" +
                "&id=${URLEncoder.encode(id, StandardCharsets.UTF_8.name())}" +
                "&accesskey=${URLEncoder.encode(accessKey, StandardCharsets.UTF_8.name())}" +
                "&fmt=krc&charset=utf8"
        val downloadJson = get(downloadUrl)

        return JSONObject(downloadJson).optString("content", "").ifEmpty { null }
    }

    /**
     * 读磁盘缓存
     *
     * 只有成功解析出歌词的载荷才会写盘（见 [attempt]），因此这里直接采信；
     * 文件损坏导致解密失败时，[attempt] 会删除该条目，下次回退到网络。
     */
    private fun readCachedKrc(hash: String): String? {
        val bytes = LyricCacheStore.getBytes(null, LyricCacheStore.PROVIDER_KUGOU, hash) ?: return null
        AndroidLog.logI(sourceName, "Lyric disk cache hit for hash=$hash")
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * 发起一次 GET
     *
     * 传输层失败必须抛出，不能返回 null：调用方用 null 表示平台没有该歌词；
     * 若把网络异常压成 null，断网会被判为确定性无歌词并下发纯音乐占位。
     * 抛出后由 FetchSlot 归类为 [LyricOutcome.TransientFailure]，
     * 只重试、不下发占位。
     */
    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val call = HttpClients.standard.newCall(request)
        requests.withCall(call) { call.execute() }.use { response: Response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body.readTextBounded()
        }
    }

    private companion object {
        const val USER_AGENT = "KuGou2012-7093-Android601"
    }
}
