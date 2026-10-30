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
 * 宿主侧只下发全量歌词与停止两种包：曲目切换与播放停止都下发停止包，令客户端立刻
 * 作废当前歌词；解析完成或确认无歌词时下发全量歌词。
 *
 * 进度推进完全由系统框架侧的时钟负责，宿主不发布任何进度包。
 *
 * 切歌到新歌词就绪之间有几十毫秒到数百毫秒的空窗期，期间显示什么由客户端决定：
 * 模块不知道客户端的 UI 意图，也不替它保留上一首的歌词。
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
        // 切歌后立刻下发停止，空窗期由客户端自行呈现
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
        LyricDiag.dumpDoc("Publish", doc)
        AbsPublisher.publishFullLyric(doc)
    }

    override fun onNoLyrics(identity: TrackIdentity?) {
        // 仅平台或宿主明确报告无歌词时进入；查询失败走 onUnavailable，不下发占位
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
        LyricDiag.dumpDoc("PublishPlaceholder", doc)
        AbsPublisher.publishFullLyric(doc)
    }

    override fun onUnavailable(identity: TrackIdentity?) {
        // 取词失败不代表没有歌词：不下发任何内容，否则一次断网就会把曲目永久标记为纯音乐
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
