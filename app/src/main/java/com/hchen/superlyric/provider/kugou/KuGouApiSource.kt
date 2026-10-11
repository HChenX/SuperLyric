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
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.kernel.decode.DecodeException
import com.hchen.superlyric.kernel.decode.PayloadBytes
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.provider.base.readTextBounded
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 酷狗开放接口取词来源：官方 KRC 接口的直查与检索
 *
 * ## 接口
 * - 检索：`mobilecdn.kugou.com/api/v3/search/song`（歌名 + 歌手 → 候选 Hash）
 * - 取歌词 id / accesskey：`krcs.kugou.com/search?hash=…`
 * - 下载：`krcs.kugou.com/download?id=…&accesskey=…&fmt=krc`
 *
 * 三个接口均无需鉴权，长期稳定。
 *
 * ## 结果语义（决定是否允许下发纯音乐占位）
 * - 拿到并解析成功 → [LyricOutcome.Success]
 * - 请求成功但平台明确没有这条曲目的歌词 → [LyricOutcome.NoLyrics]（终态）
 * - 网络异常或报文解码失败 → [LyricOutcome.TransientFailure]（可重试，**绝不**下发占位）
 *
 * @author 焕晨HChen
 */
class KuGouApiSource : LyricSource {

    override val sourceName: String = "KugouApi"

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
        // 直查：宿主上报的哈希并不总是歌词哈希（同一首曲目实测会出现多个不同哈希），
        // 因此落空时**必须**回退到检索。否则会把有歌词的歌误判为无歌词、下发纯音乐占位。
        hashOf(identity)?.let { direct ->
            attempt(direct, identity)?.let { return it }
            AndroidLog.logD(
                sourceName,
                "Direct hash lookup yielded no lyric: $direct; falling back to keyword search",
            )
        }

        val searchedHash = searchBestHash(identity)
            // 检索落空 ≠ 平台确定没有歌词：可能只是没匹配上，或报文结构变了。
            // NoLyrics 允许下发纯音乐占位，误判一次就把有歌词的曲目钉死了
            ?: return LyricOutcome.TransientFailure(null)

        attempt(searchedHash, identity)?.let { return it }
        // attempt 返回 null 只说明**这个哈希没拿到可用歌词**（下载失败、解码失败、
        // 或平台确实没有），不足以判定"这首歌没有歌词"
        return LyricOutcome.TransientFailure(null)
    }

    /**
     * 对单个哈希取词
     *
     * @return [LyricOutcome.Success] 或 [LyricOutcome.TransientFailure]；
     *         返回 `null` 表示**这个哈希没有歌词**，调用方可继续尝试其它哈希
     */
    private fun attempt(hash: String, identity: TrackIdentity): LyricOutcome? {
        val cached = readCachedKrc(hash)
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

        val doc = try {
            LyricDiag.parse(
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
        } catch (e: DecodeException) {
            // 解不开是"这次没拿到"，不是"没有歌词"
            AndroidLog.logW(sourceName, "KRC decode failed: ${e.message}")
            // 失败也可能来自损坏的缓存条目；删掉它，下次自然回落网络重取
            LyricCacheStore.delete(null, PROVIDER_NAME, hash)
            return LyricOutcome.TransientFailure(e)
        }

        if (doc == null || doc.isEmpty) {
            AndroidLog.logD(sourceName, "Parsed no lines for hash=$hash")
            return null
        }

        // 只缓存**本次从网络取回且已验证可解析**的载荷。
        // 命中缓存时不再回写——否则日志会先报 HIT 再报"已缓存"，自相矛盾。
        if (cached == null) {
            LyricCacheStore.putAsync(null, PROVIDER_NAME, hash, krcPayload)
        }
        AndroidLog.logI(sourceName, "Fetched KRC for hash=$hash, lines=${doc.lines.size}")
        return LyricOutcome.Success(doc)
    }

    private fun hashOf(identity: TrackIdentity): String? =
        TrackIdentity.normalizeStableId(identity.stableId)?.takeIf { HASH_PATTERN.matches(it) }

    /**
     * 按歌名 + 歌手检索候选，返回打分最高的 Hash
     *
     * 采纳门槛见 [LyricScorer.MIN_ACCEPT_SCORE]
     */
    private fun searchBestHash(identity: TrackIdentity): String? {
        if (identity.title.isBlank()) return null

        val keyword = if (identity.artist.isBlank()) identity.title else "${identity.title} ${identity.artist}"
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&page=1&pagesize=5&keyword=" +
            URLEncoder.encode(keyword, StandardCharsets.UTF_8.name())

        val json = get(url)
        val info = JSONObject(json).optJSONObject("data")?.optJSONArray("info") ?: return null

        var bestHash: String? = null
        var bestScore = LyricScorer.MIN_ACCEPT_SCORE
        val candidates = ArrayList<String>(info.length())

        for (index in 0 until info.length()) {
            val item = info.optJSONObject(index) ?: continue
            // 这个哈希来自**响应**而不是本模块，必须与身份里那个同样受正则约束：
            // 它会不经校验地拼进请求 URL（`downloadKrc`）。顺带也过滤掉形态不对的候选，
            // 省掉几次注定失败的往返
            val hash = item.optString("hash", "")
            if (!HASH_PATTERN.matches(hash)) continue

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
            candidates += "\"$candidateTitle\"/\"$candidateArtist\"/${candidateDurationMs / 1000}s" +
                "=${score.roundTo1()} hash=$hash"
            // 用 `>` 而不是 `>=`：同分时**采纳检索的排序首位**。
            // 酷狗曲库里同一首歌的不同版本会登记成完全相同的歌名/歌手/时长
            // （实测 NIGHT DANCER 的韩语版与日语原版三项逐字相同），打分器无从区分，
            // 平局只能靠顺序决定——取最后一个会随机配上错误语言的歌词。
            if (score > bestScore) {
                bestScore = score
                bestHash = hash
            }
        }

        // 无论是否命中都要打出候选明细：只看"通过了/没通过"无法判断
        // 是**检索没找对歌**、**正确的那版根本不在候选里**，还是**打分把它判掉了**。
        // 这三种情况（尤其是"取到了同一首歌的另一个版本"）修法完全不同。
        AndroidLog.logD(
            sourceName,
            "Candidates for \"$keyword\" (target \"${identity.title}\"/\"${identity.artist}\"/" +
                "${identity.durationMs / 1000}s): ${candidates.joinToString(" | ").ifEmpty { "none" }}",
        )

        if (bestHash == null) {
            AndroidLog.logD(sourceName, "No KuGou candidate reached the acceptance threshold for \"$keyword\"")
        }
        return bestHash
    }

    private fun Double.roundTo1(): String = String.format(Locale.US, "%.1f", this)

    private fun downloadKrc(hash: String, durationMs: Long): String? {
        val searchUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=$durationMs&hash=$hash"
        val searchJson = get(searchUrl)
        val candidates = JSONObject(searchJson).optJSONArray("candidates")
        val first = firstObject(candidates) ?: return null

        val id = first.optString("id", "")
        val accessKey = first.optString("accesskey", "")
        if (id.isEmpty() || accessKey.isEmpty()) return null

        val downloadUrl = "http://krcs.kugou.com/download?ver=1&client=mobi" +
            "&id=${URLEncoder.encode(id, StandardCharsets.UTF_8.name())}" +
            "&accesskey=${URLEncoder.encode(accessKey, StandardCharsets.UTF_8.name())}" +
            "&fmt=krc&charset=utf8"
        val downloadJson = get(downloadUrl)

        return JSONObject(downloadJson).optString("content", "").ifEmpty { null }
    }

    /**
     * 读磁盘缓存
     *
     * 只有**成功解析出歌词**的载荷才会被写盘（见 [attempt]），因此这里直接采信；
     * 万一文件损坏导致解密失败，[attempt] 会删除该条目，下次自然回落网络。
     */
    private fun readCachedKrc(hash: String): String? {
        val bytes = LyricCacheStore.getBytes(null, PROVIDER_NAME, hash) ?: return null
        AndroidLog.logI(sourceName, "Lyric disk cache hit for hash=$hash")
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun firstObject(array: JSONArray?): JSONObject? =
        if (array == null || array.length() == 0) null else array.optJSONObject(0)

    /**
     * 发起一次 GET
     *
     * **传输层失败必须抛出**，绝不能返回 null：调用方用 null 表达"平台没有这条歌词"，
     * 若把网络异常也压成 null，断网就会被判成「确定性无歌词」并下发纯音乐占位。
     * 抛出后由 [start] 的外层 `runCatching` 归类为 [LyricOutcome.TransientFailure]，
     * 只重试、不下发占位。
     */
    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        httpClient.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body.readTextBounded()
        }
    }

    private companion object {
        /** 磁盘缓存命名空间，须与 `LyricCacheStore.ONLINE_PROVIDERS` 一致 */
        const val PROVIDER_NAME = "Kugou"

        const val USER_AGENT = "KuGou2012-7093-Android601"

        val HASH_PATTERN = Regex("^[0-9a-fA-F]{32}$")

        /**
         * 取词的整体时限预算
         *
         * read 超时只约束单次读，每次读到数据就重置，挡不住"永远挤一点数据"的服务端；
         * callTimeout 给整次调用封顶。与 QQ / 网易云的 4/6/12 一致，Spotify 另有一套更大的值
         */
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
