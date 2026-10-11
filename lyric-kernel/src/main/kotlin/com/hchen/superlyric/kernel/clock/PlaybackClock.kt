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

import com.hchen.superlyric.kernel.model.LyricLine
import kotlin.math.max
import kotlin.math.min

/** 播放状态。刻意不复用 Android 的 `PlaybackState` —— 内核必须保持零 Android 依赖 */
enum class PlaybackStatus {
    STOPPED,
    PAUSED,
    BUFFERING,
    PLAYING,
}

/**
 * 唯一的播放时钟
 *
 * 宿主侧**不发布任何进度包**，本时钟是整条链路上唯一的进度来源
 *
 * ## 三条规则
 * - **锚点是宿主自报的时刻**：`pos(now) = position + (now - positionUpdateTime) * speed`，
 *   进度不随系统回调延迟而滞后
 * - **speed ≤ 0 的 PLAYING 视为冻结**，而非"运行但速率为 0"
 * - **位置回退是合法的**（重播、拖回开头），不做抑制，由上层据此重置行索引
 *
 * 时间源可注入，因此漂移、seek、重播、speed=0、暂停等场景均可确定性复现
 *
 * @param durationMs 曲目总时长；未知时为 0（表示不做上界钳制）
 * @param timeSource 单调时间源（毫秒）。默认取纳秒时钟换算，与 Android 的
 *                   `SystemClock.elapsedRealtime()` 同基
 *
 * @author 焕晨HChen
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

    private var status: PlaybackStatus = PlaybackStatus.STOPPED
    private var anchorPosition: Long = 0L
    private var anchorTime: Long = 0L
    private var speed: Float = 1f

    val currentStatus: PlaybackStatus get() = status

    val currentSpeed: Float get() = speed

    /**
     * 同步一次宿主上报的播放状态
     *
     * @param status               新的播放状态
     * @param positionMs           该快照对应的播放位置
     * @param speed                播放速率
     * @param positionUpdateTimeMs 该快照**产生**的时刻（与 [timeSource] 同基）；
     *                             传入 0 或负值表示宿主未提供，退回当前时刻
     */
    fun sync(
        status: PlaybackStatus,
        positionMs: Long,
        speed: Float = 1f,
        positionUpdateTimeMs: Long = timeSource(),
    ) {
        this.status = status
        this.anchorPosition = max(0L, positionMs)
        this.anchorTime = if (positionUpdateTimeMs > 0L) positionUpdateTimeMs else timeSource()
        this.speed = if (speed.isFinite()) speed else 1f
    }

    /**
     * 处理一次跳转。跳转后位置立即生效，并以此为新的锚点
     */
    fun seekTo(positionMs: Long, now: Long = timeSource()) {
        anchorPosition = max(0L, positionMs)
        anchorTime = now
    }

    /**
     * 重置为停止状态
     */
    fun reset() {
        status = PlaybackStatus.STOPPED
        anchorPosition = 0L
        anchorTime = timeSource()
        speed = 1f
    }

    /**
     * 计算当前播放位置
     */
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
        if (durationMs > 0L && position > durationMs) return durationMs
        return position
    }

    companion object {
        /**
         * 在按起始时间升序排列的歌词行中，定位给定时刻所在的行
         *
         * 使用二分查找；时刻早于首行时返回 0，晚于末行时返回末行下标
         *
         * @return 行下标；行列表为空时返回 -1
         */
        fun lineIndexAt(lines: List<LyricLine>, positionMs: Long): Int {
            if (lines.isEmpty()) return -1

            var low = 0
            var high = lines.size - 1
            var matched = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lines[mid].startMs <= positionMs) {
                    matched = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return if (matched < 0) 0 else min(matched, lines.size - 1)
        }
    }
}
