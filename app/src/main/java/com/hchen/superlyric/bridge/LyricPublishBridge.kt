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
package com.hchen.superlyric.bridge

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.PureMusicDoc
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.publisher.AbsPublisher
import com.hchen.superlyric.utils.LyricDiag

/**
 * 把内核编排结果发布到系统框架
 *
 * 宿主侧只发布**三种包**：
 * - **切歌** —— 曲目刚切换、歌词尚未就绪时下发，令客户端立刻作废旧歌词
 * - **全量歌词** —— 曲目解析完成、或确认无歌词时下发
 * - **停止** —— 播放真正停止时下发
 *
 * 进度推进完全由系统框架侧的时钟负责，宿主**不发布任何进度包**
 *
 * 切歌后到新歌词就绪之间有几十毫秒到数百毫秒的空窗期。这段空窗期该显示什么是
 * **客户端的决定**，不是本模块的：模块不知道客户端的 UI 意图，也不该替它保留上一首的歌词，
 * 那会让界面显示与当前播放**无关的内容**
 *
 * @author 焕晨HChen
 */
class LyricPublishBridge : LyricOrchestrator.Listener {

    override fun onTrackChanged(identity: TrackIdentity) {
        // 生命周期事件，用 logI 而非 logD：必须始终可见
        AndroidLog.logI(
            TAG,
            "Track changed: id=${identity.stableId}, title=\"${identity.title}\", " +
                "artist=\"${identity.artist}\", duration=${identity.durationMs}ms"
        )
        // 旧歌词立刻作废，空窗期由客户端自行呈现
        AndroidLog.logI(TAG, "Clearing previous lyric for the new track")
        AbsPublisher.publishStop()
    }

    override fun onLyricResolved(outcome: LyricOutcome.Success) {
        val doc = outcome.doc
        AndroidLog.logI(
            TAG,
            "Publishing full lyric: lyricId=${doc.lyricId}, lines=${doc.lines.size}, " +
                "span=[${doc.firstStartMs}ms..${doc.lastEndMs}ms], " +
                "hasWords=${doc.lines.any { it.hasWords }}, " +
                "hasTranslation=${doc.lines.any { it.hasTranslation }}"
        )
        AbsPublisher.publishFullLyric(LyricDocAdapter.toFullData(doc).also {
            LyricDiag.dumpData("Publish", it)
        })
    }

    override fun onNoLyrics(identity: TrackIdentity?) {
        // 这条日志过去写的是"所有来源一致认为没有歌词"——那是旧口径。现在只有平台或宿主
        // **明确**说没有歌词才会走到这里；"查不到"走 onUnavailable，不下发占位
        AndroidLog.logI(
            TAG,
            "Platform/host reported no lyric; publishing instrumental placeholder for " +
                "id=${identity?.stableId}"
        )
        val doc = PureMusicDoc.create(
            title = identity?.title.orEmpty(),
            artist = identity?.artist.orEmpty(),
            durationMs = identity?.durationMs ?: 0L,
            lyricId = identity?.stableId,
        )
        AbsPublisher.publishFullLyric(LyricDocAdapter.toFullData(doc).also {
            LyricDiag.dumpData("PublishPlaceholder", it)
        })
    }

    override fun onUnavailable(identity: TrackIdentity?) {
        // 取词失败不等于"没有歌词"：不下发任何内容，否则断网会被钉死为纯音乐
        AndroidLog.logW(
            TAG,
            "Lyric fetch failed and retries are exhausted for id=${identity?.stableId}; " +
                "keeping the current display untouched"
        )
    }

    override fun onIdle() {
        AndroidLog.logD(TAG, "Playback stopped; publishing stop")
        AbsPublisher.publishStop()
    }

    override fun onStateChanged(state: LyricOrchestrator.State) {
        AndroidLog.logI(TAG, "State -> $state")
    }

    private companion object {
        const val TAG = "LyricPublishBridge"
    }
}
