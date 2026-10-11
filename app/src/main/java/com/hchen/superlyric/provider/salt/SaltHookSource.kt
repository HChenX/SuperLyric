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
package com.hchen.superlyric.provider.salt

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.HookLyricSource

import java.util.concurrent.Executors

/**
 * 椒盐音乐 Hook 取词来源：解析宿主给出的原始歌词文本。
 *
 * <p>只需一次解析、一个 Hook 点。宿主的行/词模型完全不需要触碰。
 *
 * @author 焕晨HChen
 */
class SaltHookSource : HookLyricSource {

    override val sourceName: String = "SaltHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-SaltHook").apply { isDaemon = true }
    }

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    @Volatile
    private var capturedSongId: String? = null

    private var lastSourceText: String? = null

    override fun install() {
        SaltAnchors.install { sourceText, songId -> onLyrics(sourceText, songId) }
    }

    override fun currentStableId(): String? = capturedSongId

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        this.session = session
        this.pendingIdentity = identity
        lastSourceText = null
    }

    override fun cancel() {
        session = null
        pendingIdentity = null
    }

    private fun onLyrics(sourceText: String, songId: String) {
        if (songId.isNotEmpty()) capturedSongId = songId

        val activeSession = session ?: return
        val base = pendingIdentity ?: return
        if (sourceText == lastSourceText) return
        lastSourceText = sourceText

        val identity = base.copy(
            stableId = TrackIdentity.stableId(NAMESPACE, songId) ?: base.stableId,
        )

        executor.execute {
            val outcome = try {
                val doc = LyricPipeline.parse(
                    LyricRequest(
                        format = LyricFormat.AUTO,
                        text = sourceText,
                        title = identity.title,
                        artist = identity.artist,
                        durationMs = identity.durationMs,
                        lyricId = identity.stableId,
                    )
                )
                if (doc == null || doc.isEmpty) {
                    AndroidLog.logW(sourceName, "Parsed no lines from sourceText")
                    LyricOutcome.Unsupported("salt: parsed empty")
                } else {
                    AndroidLog.logI(sourceName, "Parsed sourceText, lines=${doc.lines.size}")
                    LyricOutcome.Success(doc)
                }
            } catch (t: Throwable) {
                AndroidLog.logW(sourceName, "Failed to parse sourceText: ${t.message}")
                LyricOutcome.TransientFailure(t)
            }
            activeSession.deliver(outcome)
        }
    }

    private companion object {
        const val NAMESPACE = "salt"
    }
}
