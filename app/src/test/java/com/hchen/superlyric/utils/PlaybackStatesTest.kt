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
package com.hchen.superlyric.utils

import android.media.session.PlaybackState
import com.hchen.superlyric.kernel.orchestrate.PlaybackSignal
import com.hchen.superlyricapi.model.PlaybackState as V4PlaybackState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Android 播放常量到 v4 播放状态的映射
 *
 * PAUSED 保持为 PAUSED，STATE_NONE 保持为不知道。两者一旦并入 STOPPED，
 * 客户端会清空内容或停留在错误结论上。
 */
class PlaybackStatesTest {

    @Test
    fun `playing variants map to playing`() {
        assertEquals(
            V4PlaybackState.PLAYING,
            PlaybackStates.toV4(PlaybackState.STATE_PLAYING)
        )
        assertEquals(
            V4PlaybackState.PLAYING,
            PlaybackStates.toV4(PlaybackState.STATE_FAST_FORWARDING)
        )
        assertEquals(
            V4PlaybackState.PLAYING,
            PlaybackStates.toV4(PlaybackState.STATE_SKIPPING_TO_NEXT)
        )
    }

    @Test
    fun `paused is not stopped`() {
        assertEquals(
            V4PlaybackState.PAUSED,
            PlaybackStates.toV4(PlaybackState.STATE_PAUSED)
        )
    }

    @Test
    fun `stopped and error stay distinct`() {
        assertEquals(
            V4PlaybackState.STOPPED,
            PlaybackStates.toV4(PlaybackState.STATE_STOPPED)
        )
        assertEquals(
            V4PlaybackState.ERROR,
            PlaybackStates.toV4(PlaybackState.STATE_ERROR)
        )
    }

    @Test
    fun `none means unknown rather than stopped`() {
        assertNull(PlaybackStates.toV4(PlaybackState.STATE_NONE))
    }

    @Test
    fun `signal mapping keeps none neutral and only terminal states stop`() {
        assertEquals(PlaybackSignal.UNKNOWN, PlaybackStates.toSignal(PlaybackState.STATE_NONE))
        assertEquals(PlaybackSignal.ACTIVE, PlaybackStates.toSignal(PlaybackState.STATE_BUFFERING))
        assertEquals(PlaybackSignal.STOPPED, PlaybackStates.toSignal(PlaybackState.STATE_STOPPED))
        assertEquals(PlaybackSignal.PLAYING, PlaybackStates.toSignal(PlaybackState.STATE_PLAYING))
    }
}
