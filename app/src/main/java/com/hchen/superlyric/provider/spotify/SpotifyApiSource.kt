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
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.provider.spotify.SpotifyLyricAnalysis.SpotifyLine
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.Executors

/**
 * Spotify 官方接口取词来源。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>等 [SpotifyAnchors] 从宿主的 {@code okhttp3.Headers} 旁路捕获到会话头；</li>
 *   <li>以该会话头拉取 {@code color-lyrics/v2}（protobuf）；</li>
 *   <li>解析结果（含逐字与音译）映射为内核模型。</li>
 * </ol>
 *
 * <p>本模块**不持有任何 Spotify 凭证**，只是复用宿主已经发出的请求头；
 * 会话头失效时返回可重试失败，由编排器退避重试。
 *
 * <h3>结果语义</h3>
 * <ul>
 *   <li>{@code LyricNotFoundException} → [LyricOutcome.NoLyrics]——平台明确表示无歌词；</li>
 *   <li>鉴权失败 / 网络异常 / 会话头尚未就绪 → [LyricOutcome.TransientFailure]。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
class SpotifyApiSource : LyricSource {

    override val sourceName: String = "SpotifyApi"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-SpotifyApi").apply { isDaemon = true }
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
        SpotifyLyricAnalysis.cancelExcept(null)
    }

    private fun resolve(identity: TrackIdentity): LyricOutcome {
        val trackId = TrackIdentity.normalizeStableId(identity.stableId)
            ?: return LyricOutcome.Unsupported("spotify: no track id")

        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (cancelled) return@repeat

            val headers = SpotifyLyricAnalysis.currentHeaders()
            if (headers == null) {
                // 宿主尚未发出任何请求，会话头还没被捕获到
                sleep(HEADER_WAIT_MS)
                return@repeat
            }
            try {
                val data = SpotifyLyricAnalysis.fetchLyric(trackId, headers)
                val lines = SpotifyLyricAnalysis.parseLyrics(data).orEmpty()
                if (lines.isEmpty()) {
                    return LyricOutcome.NoLyrics("spotify: empty lyric")
                }
                SpotifyLyricAnalysis.invalidate(headers)

                val doc = toDoc(lines, identity)
                LyricDiag.dumpDoc(sourceName, doc)
                AndroidLog.logI(
                    sourceName,
                    "Fetched lyric for $trackId, lines=${doc.lines.size}, " +
                        "wordTimed=${doc.lines.any { it.hasWords }}, " +
                        "phonetic=${doc.lines.any { it.hasPhonetic }}"
                )
                return LyricOutcome.Success(doc)
            } catch (e: SpotifyLyricAnalysis.LyricNotFoundException) {
                return LyricOutcome.NoLyrics("spotify: platform reports no lyric")
            } catch (e: SpotifyLyricAnalysis.AuthenticationException) {
                // 会话头可能尚未捕获到；短暂等待后重试
                lastError = e
                sleep(HEADER_WAIT_MS)
            } catch (e: Throwable) {
                lastError = e
                AndroidLog.logW(sourceName, "Attempt ${attempt + 1} failed: ${e.message}")
                sleep(HEADER_WAIT_MS)
            }
        }

        return if (lastError is SpotifyLyricAnalysis.AuthenticationException) {
            AndroidLog.logW(sourceName, "Session headers were never captured; retry later")
            LyricOutcome.TransientFailure(lastError)
        } else {
            LyricOutcome.TransientFailure(lastError)
        }
    }

    private fun toDoc(lines: List<SpotifyLine>, identity: TrackIdentity): LyricDoc {
        val mapped = lines.mapNotNull { line ->
            if (line.text.isBlank()) return@mapNotNull null
            LyricLine(
                startMs = line.startTimeMs,
                endMs = line.endTimeMs,
                text = line.text,
                phonetic = line.transliteratedWords?.takeIf { it.isNotBlank() },
                words = line.words?.map { LyricWord(it.word, it.startTime, it.endTime) }.orEmpty(),
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
