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

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyricapi.wire.Frame
import com.hchen.superlyricapi.model.PlaybackState
import com.hchen.superlyricapi.model.State
import com.hchen.superlyricapi.model.Stop
import com.hchen.superlyricapi.model.StopReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * v4 线上帧的编解码
 *
 * 服务端与客户端共用 AAR 里的 [Frame]。FULL 携带文档与状态，STATE 与 STOP 不携带文档；
 * 单帧上限为客户端要求的 512 KiB。
 */
class LyricFrameTest {

    private val doc = LyricDoc(
        lines = listOf(
            LyricLine(
                startMs = 0L,
                endMs = 1_000L,
                text = "Hello",
                translation = "你好",
                phonetic = "he-lou",
                words = listOf(LyricWord("He", 0L, 400L), LyricWord("llo", 400L, 1_000L)),
            ),
            LyricLine(startMs = 1_000L, endMs = 2_000L, text = "World"),
        ),
        title = "T",
        artist = "A",
        album = "Al",
        durationMs = 2_000L,
        lyricId = "qq:x",
    )

    @Test
    fun `full frame carries the document and the state`() {
        val lyric = LyricDocAdapter.toLyric(doc)!!
        val state = State(lyric.id, 1, 1_500L, 1.25f, PlaybackState.PAUSED)

        val decoded = Frame.decode(Frame.full(lyric, state, "com.example.music").encode())

        assertEquals(Frame.Kind.FULL, decoded.kind)
        assertEquals("com.example.music", decoded.publisher)
        assertNotNull(decoded.doc)
        assertEquals("qq:x", decoded.doc!!.id)
        assertEquals(2, decoded.doc!!.lines.size)
        assertEquals("你好", decoded.doc!!.lines[0].translation)
        assertEquals("he-lou", decoded.doc!!.lines[0].phonetic)
        assertNotNull(decoded.state)
        assertEquals(1, decoded.state!!.index)
        assertEquals(1_500L, decoded.state!!.position)
        assertEquals(PlaybackState.PAUSED, decoded.state!!.state)
    }

    @Test
    fun `state frame carries no document`() {
        val state = State("qq:x", 0, 500L, 1f, PlaybackState.PLAYING)

        val decoded = Frame.decode(Frame.state(state, "com.example.music").encode())

        assertEquals(Frame.Kind.STATE, decoded.kind)
        assertEquals("com.example.music", decoded.publisher)
        assertEquals(null, decoded.doc)
        assertEquals(0, decoded.state!!.index)
    }

    @Test
    fun `stop frame carries the reason but no document`() {
        val decoded = Frame.decode(
            Frame.stop(Stop("qq:x", StopReason.CLEARED, "com.example.music")).encode(),
        )

        assertEquals(Frame.Kind.STOP, decoded.kind)
        assertEquals("com.example.music", decoded.publisher)
        assertEquals(null, decoded.doc)
        assertEquals(StopReason.CLEARED, decoded.stop!!.reason)
        assertEquals("qq:x", decoded.stop!!.id)
        assertEquals("com.example.music", decoded.stop!!.publisher)
    }

    @Test
    fun `frame cap matches the client`() {
        assertEquals(512 * 1024, Frame.MAX_SIZE)
    }
}
