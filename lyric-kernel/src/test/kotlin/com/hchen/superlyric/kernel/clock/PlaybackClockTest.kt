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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 播放时钟的确定性测试。时间源注入，因此每个场景都可精确复现。
 */
class PlaybackClockTest {

    private var now = 0L

    private fun clock(durationMs: Long = 300_000L) = PlaybackClock(durationMs) { now }

    @Test
    fun `position is anchored at the host reported update time, not the callback time`() {
        val clock = clock()

        // 宿主在 8000ms 时刻产生快照 pos=5000；我们在 10000ms 才把它喂给时钟
        now = 10_000L
        clock.sync(PlaybackStatus.PLAYING, positionMs = 5_000L, speed = 1f, positionUpdateTimeMs = 8_000L)

        // 旧实现会以 10000 为锚，算出 5000；正确结果是 5000 + (10000-8000) = 7000
        assertEquals(7_000L, clock.positionAt())
    }

    @Test
    fun `position advances with elapsed time`() {
        val clock = clock()

        now = 0L
        clock.sync(PlaybackStatus.PLAYING, positionMs = 1_000L, speed = 1f, positionUpdateTimeMs = 0L)

        now = 2_500L
        assertEquals(3_500L, clock.positionAt())
    }

    @Test
    fun `playing with zero speed is frozen rather than running in place`() {
        val clock = clock()

        now = 0L
        clock.sync(PlaybackStatus.PLAYING, positionMs = 5_000L, speed = 0f, positionUpdateTimeMs = 0L)

        now = 60_000L
        assertEquals(5_000L, clock.positionAt())
    }

    @Test
    fun `paused clock does not advance`() {
        val clock = clock()

        now = 0L
        clock.sync(PlaybackStatus.PAUSED, positionMs = 1_500L, speed = 0f, positionUpdateTimeMs = 0L)

        now = 30_000L
        assertEquals(1_500L, clock.positionAt())
    }

    @Test
    fun `seek takes effect immediately`() {
        val clock = clock()

        now = 0L
        clock.sync(PlaybackStatus.PLAYING, positionMs = 1_000L, speed = 1f, positionUpdateTimeMs = 0L)

        now = 5_000L
        clock.seekTo(50_000L)

        assertEquals(50_000L, clock.positionAt())
    }

    @Test
    fun `replay from the beginning is honoured instead of being suppressed`() {
        val clock = clock()

        now = 0L
        clock.sync(PlaybackStatus.PLAYING, positionMs = 100_000L, speed = 1f, positionUpdateTimeMs = 0L)
        assertEquals(101_000L, clock.positionAt(now = 1_000L))

        // 用户把进度条拖回开头并重新播放
        now = 1_000L
        clock.sync(PlaybackStatus.PLAYING, positionMs = 0L, speed = 1f, positionUpdateTimeMs = 1_000L)

        // 旧实现会把这当作"虚假 0ms"抑制掉，把时钟留在曲尾
        assertEquals(0L, clock.positionAt(now = 1_000L))
    }

    @Test
    fun `position is clamped to the track duration`() {
        val clock = clock(durationMs = 10_000L)

        now = 0L
        clock.sync(PlaybackStatus.PLAYING, positionMs = 9_000L, speed = 1f, positionUpdateTimeMs = 0L)

        assertEquals(10_000L, clock.positionAt(now = 60_000L))
    }

    @Test
    fun `reset returns the clock to a stopped zero state`() {
        val clock = clock()

        now = 0L
        clock.sync(PlaybackStatus.PLAYING, positionMs = 42_000L, speed = 1f, positionUpdateTimeMs = 0L)
        clock.reset()

        assertEquals(PlaybackStatus.STOPPED, clock.currentStatus)
        assertEquals(0L, clock.positionAt(now = 5_000L))
    }

    @Test
    fun `line index lookup covers boundaries`() {
        val lines = listOf(
            LyricLine(startMs = 0L, endMs = 1_000L, text = "a"),
            LyricLine(startMs = 1_000L, endMs = 2_000L, text = "b"),
            LyricLine(startMs = 2_000L, endMs = 3_000L, text = "c"),
        )

        assertEquals(-1, PlaybackClock.lineIndexAt(emptyList(), 0L))
        assertEquals(0, PlaybackClock.lineIndexAt(lines, -1L))
        assertEquals(0, PlaybackClock.lineIndexAt(lines, 500L))
        assertEquals(1, PlaybackClock.lineIndexAt(lines, 1_000L))
        assertEquals(2, PlaybackClock.lineIndexAt(lines, 99_999L))
    }
}
