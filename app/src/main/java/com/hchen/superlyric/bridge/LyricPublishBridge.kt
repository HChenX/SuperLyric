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
 * 把内核编排结果发布到系统框架。
 *
 * <h3>职责边界</h3>
 * 宿主侧只发布**三种包**：
 * <ul>
 *   <li><b>切歌</b>——曲目刚切换、歌词尚未就绪时下发，令客户端立刻作废旧歌词；</li>
 *   <li><b>全量歌词</b>——曲目解析完成、或确认无歌词时下发；</li>
 *   <li><b>停止</b>——播放真正停止时下发。</li>
 * </ul>
 * 进度推进完全由系统框架侧的时钟负责，宿主**不发布任何进度包**。
 *
 * <h3>为什么切歌就要立即清屏</h3>
 * 切歌后到新歌词就绪之间有几十毫秒到数百毫秒的空窗期。这段空窗期该显示什么，
 * **是客户端的决定，不是本模块的**——客户端可以画"歌词加载中"、可以做过渡动画，
 * 而模块既不知道客户端的 UI 意图，也不该替它保留上一首的歌词：
 * 那会让界面显示与当前播放**无关的内容**，且服务端时钟仍在推进那份旧文档，
 * 表现为"上一首的歌词还在跑"。
 *
 * <p>早期设计刻意不在切歌时下发任何东西（怕"闪断"），现已废弃：
 * 保留下错误内容比短暂留白更糟，而且把判断权交还给客户端才是正确的分层。
 *
 * @author 焕晨HChen
 */
class LyricPublishBridge : LyricOrchestrator.Listener {

    override fun onTrackChanged(identity: TrackIdentity) {
        AndroidLog.logD(
            TAG,
            "Track changed: id=${identity.stableId}, title=\"${identity.title}\", " +
                "artist=\"${identity.artist}\", duration=${identity.durationMs}ms"
        )
        // 旧歌词立刻作废。空窗期由客户端自行呈现。
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
        AndroidLog.logI(
            TAG,
            "All sources agree there is no lyric; publishing instrumental placeholder for " +
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
        // 取词失败不等于"这首曲子没有歌词"：不下发任何内容。
        // 若在此处下发占位，就会复现"断网即被永久钉死为纯音乐"的历史缺陷。
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
