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
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.utils.LyricDiag
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 截获宿主原始歌词载荷，由内置解析管线在后台处理。 */
class CiderHookSource @JvmOverloads constructor(
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-CiderHook").apply { isDaemon = true }
    },
    private val requestLyrics: (String) -> Unit = CiderAnchors::request,
    private val onSourceChanged: Runnable = Runnable {},
) : InstallableLyricSource {

    constructor(onSourceChanged: Runnable) : this(
        requestLyrics = CiderAnchors::request,
        onSourceChanged = onSourceChanged,
    )

    override val sourceName: String = "CiderHook"
    private val requests = SourceRequestGate()

    override fun install() {
        CiderAnchors.subscribe(::onCaptured)
        CiderAnchors.subscribeSelection(::onSelectionChanged)
        CiderAnchors.install()
    }

    internal fun onSelectionChanged() {
        requests.invalidate()
        onSourceChanged.run()
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
            val outcome = if (capture.instrumental()) {
                LyricOutcome.NoLyrics("cider: host reports instrumental audio")
            } else {
                var failure: Throwable? = null
                val doc = capture.texts().asSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { text ->
                        runCatching {
                            LyricDiag.parse(
                                sourceName,
                                LyricRequest(
                                    format = LyricFormat.AUTO,
                                    text = text,
                                    title = request.identity.title,
                                    artist = request.identity.artist,
                                    album = capture.album(),
                                    durationMs = request.identity.durationMs,
                                    lyricId = request.identity.stableId,
                                ),
                            )
                        }.onFailure { failure = it }.getOrNull()
                    }
                    .firstOrNull { !it.isEmpty }
                doc?.let { LyricOutcome.Success(it.withMetadata(request.identity)) }
                    ?: failure?.let { LyricOutcome.TransientFailure(it) }
                    ?: LyricOutcome.Unsupported("cider: no usable lyric payload")
            }
            // 相同曲目重新开始也属于新请求，不能把旧解析结果交给新会话。
            if (requests.isCurrent(request)) request.session.deliver(outcome)
        }
    }

}
