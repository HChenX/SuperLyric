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
package com.hchen.superlyric.provider.cider

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.parse.CiderLyricParser
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.utils.LyricDiag
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 主动加载宿主当前曲目的完整歌词，解析在后台执行。 */
class CiderHookSource @JvmOverloads constructor(
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-CiderHook").apply { isDaemon = true }
    },
    private val requestLyrics: (String) -> Unit = CiderAnchors::request,
) : InstallableLyricSource {

    override val sourceName: String = "CiderHook"
    private val requests = SourceRequestGate()

    override fun install() {
        CiderAnchors.subscribe(::onCaptured)
        CiderAnchors.install()
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.open(identity, session)
        TrackIdentity.usableId(TrackIdentity.normalizeStableId(identity.stableId))
            ?.let(requestLyrics)
            ?: session.deliver(LyricOutcome.Unsupported("cider: no catalog id"))
    }

    override fun cancel() {
        requests.invalidate()
    }

    internal fun onCaptured(capture: CiderAnchors.Capture) {
        val request = requests.current() ?: return
        val catalogId = TrackIdentity.usableId(capture.catalogId()) ?: return
        if (catalogId != TrackIdentity.normalizeStableId(request.identity.stableId)) return

        executor.execute {
            if (!requests.isCurrent(request)) return@execute
            val outcome = runCatching {
                if (LyricDiag.enabled) {
                    LyricDiag.dumpPayload(sourceName, "RAW-HOST-MODEL", capture.toString())
                }
                if (capture.instrumental()) {
                    LyricOutcome.NoLyrics("cider: host reports instrumental audio")
                } else if (capture.timingType() == "None") {
                    LyricOutcome.Unsupported("cider: untimed lyrics")
                } else {
                    val doc = CiderLyricParser.parse(
                        capture.lines().map { it.toParserLine() },
                        capture.durationMs(),
                    )
                    if (doc == null) {
                        LyricOutcome.Unsupported("cider: no usable timed line")
                    } else {
                        val result = doc.copy(album = capture.album())
                            .withMetadata(request.identity, capture.title(), capture.artist())
                        LyricDiag.dumpDocSummary(sourceName, result)
                        LyricOutcome.Success(result)
                    }
                }
            }.getOrElse { LyricOutcome.TransientFailure(it) }
            // 相同曲目重新开始也属于新请求，不能把旧解析结果交给新会话。
            if (requests.isCurrent(request)) request.session.deliver(outcome)
        }
    }

    private fun CiderAnchors.Line.toParserLine(includeBackground: Boolean = true): CiderLyricParser.Line =
        CiderLyricParser.Line(
            text = text(),
            translation = translation().orEmpty(),
            startMs = startMs(),
            endMs = endMs(),
            words = words().map { CiderLyricParser.Word(it.text(), it.startMs(), it.endMs()) },
            empty = empty(),
            background = if (includeBackground) background()?.toParserLine(false) else null,
        )
}
