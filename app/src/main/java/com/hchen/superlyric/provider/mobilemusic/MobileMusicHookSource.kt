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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.provider.mobilemusic

import com.hchen.hooktool.ModuleData
import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.parse.MobileMusicLyricParser
import com.hchen.superlyric.provider.base.HookLyricSource
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 咪咕整首原生歌词来源
 *
 * 歌词回调可能早于媒体会话，快照保留到同曲目的会话建立后补投。
 * 每次任务捕获自己的会话，切歌或取消后只会回到旧代际，绝不借用新曲目的通道
 */
class MobileMusicHookSource internal constructor(
    private val anchors: MobileMusicAnchors,
    private val executor: Executor,
) : HookLyricSource {

    constructor() : this(
        MobileMusicAnchors(),
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SuperLyric-MiguHook").apply { isDaemon = true }
        },
    )

    override val sourceName: String = "MobileMusicHook"

    private class Request(val identity: TrackIdentity, val session: LyricSource.Session) {
        var delivered = false

        fun deliver(outcome: LyricOutcome.Success) {
            if (delivered) return
            delivered = true
            session.deliver(outcome)
        }
    }

    @Volatile
    private var request: Request? = null

    @Volatile
    private var captured: MobileMusicAnchors.Capture? = null

    override fun install() {
        retryInstall(ModuleData.getClassLoader())
    }

    fun retryInstall(loader: ClassLoader) {
        executor.execute { install(loader, 0) }
    }

    private fun install(loader: ClassLoader, attempt: Int) {
        if (anchors.install(loader, ::onCaptured)) return
        if (attempt + 1 < MAX_INSTALL_ATTEMPTS && executor is ScheduledExecutorService) {
            executor.schedule({ install(loader, attempt + 1) }, INSTALL_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
        } else {
            val message = if (anchors.isInstalled) {
                "Live offset anchors unavailable"
            } else {
                "Native anchors unavailable; waiting for host lyrics"
            }
            AndroidLog.logW(sourceName, message)
        }
    }

    fun currentTrackIdentity(): TrackIdentity? = anchors.currentIdentity()

    override fun currentStableId(): String? = currentTrackIdentity()?.stableId

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        val target = Request(identity, session)
        request = target
        executor.execute {
            if (request !== target) return@execute
            try {
                val snapshot = anchors.readCurrent() ?: captured
                if (snapshot != null) deliver(target, snapshot)
            } catch (error: Throwable) {
                AndroidLog.logE(sourceName, "Cannot read native lyric snapshot", error)
            }
        }
    }

    override fun cancel() {
        request = null
        captured = null
    }

    internal fun onCaptured(snapshot: MobileMusicAnchors.Capture) {
        captured = snapshot
        val target = request ?: return
        executor.execute {
            if (request === target) deliver(target, snapshot)
        }
    }

    private fun deliver(target: Request, snapshot: MobileMusicAnchors.Capture) {
        if (target.delivered) return
        val current = snapshot.identity()
        if ((!target.identity.hasStableId || !current.hasStableId) &&
            (target.identity.artist.isBlank() || current.artist.isBlank() ||
                target.identity.durationMs <= 0L || current.durationMs <= 0L)
        ) {
            AndroidLog.logD(sourceName, "Discarding a provisional snapshot without artist and duration evidence")
            return
        }
        if (!target.identity.matches(current)) {
            AndroidLog.logD(
                sourceName,
                "Discarding snapshot for ${current.stableId}; waiting for ${target.identity.stableId}",
            )
            return
        }
        AndroidLog.logD(sourceName, "Accepting native snapshot for ${current.stableId}")
        if (snapshot.unavailableReason() != null) {
            // 下载中的占位数据不能抢占首次结果，后续原生回调仍可在等待窗口内完成取词。
            AndroidLog.logD(sourceName, "Waiting for native lyrics: ${snapshot.unavailableReason()}")
            return
        }

        val doc = MobileMusicLyricParser.parse(
            snapshot.lines(),
            snapshot.translations(),
            current.durationMs,
            snapshot.offsetMs(),
        )?.copy(
            title = current.title,
            artist = current.artist,
            album = snapshot.album(),
            lyricId = current.stableId,
        )
        if (request !== target) return
        if (doc == null || doc.isEmpty) {
            AndroidLog.logD(sourceName, "Waiting for readable native lyrics")
            return
        }
        if (LyricDiag.enabled) LyricDiag.dumpPayload(sourceName, "RAW-HOST-MODEL", snapshot.toString())
        LyricDiag.dumpDoc(sourceName, doc)
        target.deliver(LyricOutcome.Success(doc))
    }

    private companion object {
        const val MAX_INSTALL_ATTEMPTS = 10
        const val INSTALL_RETRY_DELAY_MS = 500L
    }
}
