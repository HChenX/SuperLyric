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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.spotify

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.parse.SpotifyLyricParser
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

/**
 * Spotify 官方接口取词来源
 *
 * 流程
 * - 等 [SpotifyAnchors] 从宿主的 `okhttp3.Headers` 旁路捕获到会话头
 * - 以该会话头拉取 `color-lyrics/v2`（protobuf）
 * - 解析结果（含逐字与音译）映射为内核模型
 */
class SpotifyApiSource : LyricSource {

    override val sourceName: String = "SpotifyApi"

    private val requests = FetchSlot()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.launch(sourceName, session) { token -> resolve(identity, token) }
    }

    override fun cancel() {
        requests.invalidate()
        SpotifyLyricAnalysis.cancelExcept(null)
    }

    private fun resolve(identity: TrackIdentity, token: Long): LyricOutcome {
        if (TrackIdentity.normalizeStableId(identity.stableId) == null) {
            return LyricOutcome.Unsupported("spotify: no track id")
        }
        // 这个 id 来自宿主上报的 MEDIA_ID，会被直接拼进请求 URL 的路径段，因此必须做
        // 格式校验：含 `/`、`..` 的值能改变请求路径（同域路径注入）。Spotify 的 track id
        // 是 base62，这里按字符集与长度区间收紧，与酷狗/网易的 HASH/NUMERIC 校验同一目的
        val trackId = CatalogIds.lyricId(identity, LyricCatalog.SPOTIFY, CatalogIds::spotifyTrackId)
        if (trackId == null) {
            AndroidLog.logW(sourceName, "Rejecting malformed track id")
            return LyricOutcome.Unsupported("spotify: malformed track id")
        }

        val cacheKey = "${LyricCacheStore.currentLocaleTag()}/$trackId"
        readCached(cacheKey, identity)?.let { return it }

        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (!requests.isCurrent(token)) {
                // 已切歌或取消：后续尝试不会被接收，直接返回
                return LyricOutcome.TransientFailure(null)
            }

            val headers = SpotifyLyricAnalysis.currentHeaders()
            if (headers == null) {
                // 宿主尚未发出任何请求，会话头还没被捕获到
                sleep(HEADER_WAIT_MS)
                return@repeat
            }
            try {
                val data = SpotifyLyricAnalysis.fetchLyric(trackId, headers)
                val lines = SpotifyLyricParser.parseLyrics(data).orEmpty()
                if (!SpotifyLyricParser.hasUsableTimeline(lines)) {
                    // 未同步歌词：所有行都是 0 起点，发布后索引无法随播放推进。
                    // 这是来源服务不了定时歌词，不是读取失败，也不能下发占位
                    AndroidLog.logW(sourceName, "Rejecting unsynced lyric for $trackId")
                    return LyricOutcome.Unsupported("spotify: lyric has no timeline")
                }
                if (lines.isEmpty()) {
                    // 404 已按无歌词处理（LyricNotFoundException）；此处是拿到了响应体却
                    // 读不出行，属读取失败，不得当作确定无歌词
                    return LyricOutcome.TransientFailure(null)
                }
                SpotifyLyricAnalysis.invalidate(headers)
                // 只缓存已成功解析的 protobuf 原始响应；命中后连会话头都不再需要，
                // 于是 VIP 过期 / 断网时仍能拿到已缓存曲目的歌词
                LyricCacheStore.putAsync(null, LyricCacheStore.PROVIDER_SPOTIFY, cacheKey, data)

                val doc = toDoc(lines, identity)
                LyricDiag.dumpDoc(sourceName, doc)
                AndroidLog.logI(
                    sourceName,
                    "Fetched lyric for $trackId, lines=${doc.lines.size}, " +
                            "wordTimed=${doc.lines.any { it.hasWords }}, " +
                            "phonetic=${doc.lines.any { it.hasPhonetic }}"
                )
                return LyricOutcome.Success(doc)
            } catch (e: LyricNotFoundException) {
                return LyricOutcome.NoLyrics("spotify: platform reports no lyric")
            } catch (e: AuthenticationException) {
                // 会话头可能尚未捕获到；短暂等待后重试
                lastError = e
                sleep(HEADER_WAIT_MS)
            } catch (e: Throwable) {
                lastError = e
                AndroidLog.logW(sourceName, "Attempt ${attempt + 1} failed: ${e.message}")
                sleep(HEADER_WAIT_MS)
            }
        }

        // 会话头未捕获或网络、解析异常都属读取失败，返回可重试失败
        if (lastError is AuthenticationException) {
            AndroidLog.logW(sourceName, "Session headers were never captured; retry later")
        }
        return LyricOutcome.TransientFailure(lastError)
    }

    /**
     * 读磁盘缓存并复核
     *
     * 命中后重新解析一遍：缓存损坏或解析器变更导致既有响应无法解析时，删掉该条目并
     * 回落网络。缓存不可用不构成平台无歌词的结论，后者是终态。
     *
     * 缓存键带语言标签：`color-lyrics/v2` 的译文随 `clientLanguage` 变化，
     * 不带语言会在切换应用语言后继续返回切换前语言的歌词。
     */
    private fun readCached(key: String, identity: TrackIdentity): LyricOutcome? {
        val bytes = LyricCacheStore.getBytes(null, LyricCacheStore.PROVIDER_SPOTIFY, key) ?: return null

        val lines = runCatching { SpotifyLyricParser.parseLyrics(bytes) }.getOrNull().orEmpty()
        if (lines.isNotEmpty() && SpotifyLyricParser.hasUsableTimeline(lines)) {
            AndroidLog.logI(sourceName, "Lyric disk cache hit for $key")
            val doc = toDoc(lines, identity)
            LyricDiag.dumpDoc(sourceName, doc)
            return LyricOutcome.Success(doc)
        }

        AndroidLog.logW(sourceName, "Discarding unusable cache entry for $key")
        LyricCacheStore.delete(null, LyricCacheStore.PROVIDER_SPOTIFY, key)
        return null
    }

    private fun toDoc(lines: List<SpotifyLyricParser.Line>, identity: TrackIdentity): LyricDoc {
        val mapped = lines.mapNotNull { line ->
            if (line.text.isBlank()) return@mapNotNull null
            LyricLine(
                startMs = line.startTimeMs,
                endMs = line.endTimeMs,
                text = line.text,
                phonetic = line.transliteratedWords?.takeIf { it.isNotBlank() },
                words = line.words.orEmpty(),
            )
        }.sortedBy { it.startMs }

        return LyricDoc(
            lines = mapped,
            title = identity.title,
            artist = identity.artist,
            durationMs = identity.durationMs,
            lyricId = identity.stableId,
        )
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val HEADER_WAIT_MS = 400L

    }
}
