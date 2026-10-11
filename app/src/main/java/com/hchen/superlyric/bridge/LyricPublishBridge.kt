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

/**
 * 把内核编排结果发布到系统框架。
 *
 * <h3>职责边界</h3>
 * 宿主侧只发布**两种包**：
 * <ul>
 *   <li><b>全量歌词</b>——曲目解析完成、或确认无歌词时下发；</li>
 *   <li><b>停止</b>——播放真正停止时下发。</li>
 * </ul>
 * 进度推进完全由系统框架侧的时钟负责，宿主**不发布任何进度包**。
 *
 * <h3>为什么切歌时不发布任何东西</h3>
 * 切歌不是"停止播放"，因此**绝不能**走停止通道——那会清空服务端的活跃发布者
 * 并向所有接收者广播停播，表现为歌词在切歌瞬间闪断。
 * 而"用零行全量包清空展示"同样不可行：`SuperLyricData.hasAllLyrics()` 要求
 * 歌词数组长度大于 0，零行包会被判定为**增量包**而非全量包，语义完全错位。
 *
 * 因此清空上一首残留展示由**服务端**在新的全量包到达时负责（会话替换 + 时钟重置）。
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
        AbsPublisher.publishFullLyric(LyricDocAdapter.toFullData(doc))
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
        AbsPublisher.publishFullLyric(LyricDocAdapter.toFullData(doc))
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

    private companion object {
        const val TAG = "LyricPublishBridge"
    }
}
