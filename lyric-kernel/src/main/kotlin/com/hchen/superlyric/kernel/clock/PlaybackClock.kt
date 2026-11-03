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
package com.hchen.superlyric.kernel.clock

import kotlin.math.max

/** 播放状态。刻意不复用 Android 的 `PlaybackState` ： 内核必须保持零 Android 依赖 */
enum class PlaybackStatus {
    STOPPED,
    PAUSED,
    BUFFERING,
    PLAYING,
}

/**
 * 唯一的播放时钟
 *
 * 宿主侧不发布任何进度包，本时钟是整条链路上唯一的进度来源
 *
 * 三条规则
 * - 锚点是宿主自报的时刻：`pos(now) = position + (now - positionUpdateTime) * speed`，
 *   进度不随系统回调延迟而滞后
 * - speed ≤ 0 的 PLAYING 视为冻结，而非"运行但速率为 0"
 * - 位置回退合法（重播、拖回开头），不做抑制，由上层据此重置行索引
 */
class PlaybackClock(
    durationMs: Long = 0L,
    private val timeSource: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /**
     * 曲目总时长；0 表示未知（不做上界钳制）。换歌时由调用方更新
     */
    @Volatile
    var durationMs: Long = durationMs

    /**
     * 歌词末行的结束时刻；0 表示未知
     *
     * 宿主上报的时长可能偏短（元数据滞后或缺失）。只按时长钳制位置，歌词后半段会在
     * 时长处停住、永远不显示；歌词跨度是位置上界的最低可信值。
     */
    @Volatile
    var lyricsEndMs: Long = 0L

    private var status: PlaybackStatus = PlaybackStatus.STOPPED
    private var anchorPosition: Long = 0L
    private var anchorTime: Long = 0L
    private var speed: Float = 1f

    val currentStatus: PlaybackStatus
        @Synchronized get() = status

    val currentSpeed: Float
        @Synchronized get() = speed

    /**
     * 同步一次宿主上报的播放状态
     *
     * @param status               新的播放状态
     *                             传入 0 或负值表示宿主未提供，退回当前时刻
     */
    @Synchronized
    fun sync(
        status: PlaybackStatus,
        positionMs: Long,
        speed: Float = 1f,
        positionUpdateTimeMs: Long = timeSource(),
    ) {
        this.status = status
        this.anchorPosition = max(0L, positionMs)
        val now = timeSource()
        // 锚点在未来通常意味着宿主使用了另一个时间基（如 uptimeMillis/currentTimeMillis）。
        // 照单全收会让 positionAt 的 elapsed 长期为负、位置整首冻结，因此退回当前时刻，
        // 以宿主上报的 position 作为本次锚点。
        this.anchorTime = when {
            positionUpdateTimeMs <= 0L -> now
            positionUpdateTimeMs > now -> now
            else -> positionUpdateTimeMs
        }
        this.speed = if (speed.isFinite()) speed else 1f
    }

    /**
     * 处理一次跳转。跳转后位置立即生效，并以此为新的锚点
     */
    @Synchronized
    fun seekTo(positionMs: Long, now: Long = timeSource()) {
        anchorPosition = max(0L, positionMs)
        anchorTime = now
    }

    /**
     * 重置为停止状态
     */
    @Synchronized
    fun reset() {
        status = PlaybackStatus.STOPPED
        anchorPosition = 0L
        anchorTime = timeSource()
        speed = 1f
    }

    /**
     * 计算当前播放位置
     */
    @Synchronized
    fun positionAt(now: Long = timeSource()): Long {
        val base = max(0L, anchorPosition)

        if (status != PlaybackStatus.PLAYING) return clamp(base)
        // speed 为 0 的 PLAYING 视为冻结，而非"运行但速率 0"
        if (speed <= 0f) return clamp(base)

        val elapsed = now - anchorTime
        if (elapsed <= 0L) return clamp(base)

        val delta = elapsed.toDouble() * speed
        if (!delta.isFinite()) return clamp(Long.MAX_VALUE)
        if (delta >= Long.MAX_VALUE.toDouble()) return clamp(Long.MAX_VALUE)

        return clamp(base + delta.toLong())
    }

    private fun clamp(position: Long): Long {
        if (position < 0L) return 0L
        val upperBound = max(durationMs, lyricsEndMs)
        if (upperBound > 0L && position > upperBound) return upperBound
        return position
    }
}
