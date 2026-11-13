@file:Suppress("DEPRECATION") // 3.x 协议模型已标记 deprecated；本测试只验证 3.x 出站边界

/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.core

import com.hchen.superlyric.bridge.LyricDocAdapter
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyricapi.SuperLyricData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.x 出站载荷按接收者能力懒构造
 *
 * 全量映射按行、按字分配，没有接收者或没有全量能力时不得构造；同一广播内每种能力至多一次。
 */
class LyricRuntimePayloadTest {

    private val lyric = LyricDocAdapter.toLyric(
        LyricDoc(
            lines = listOf(
                LyricLine(startMs = 0L, endMs = 1_000L, text = "Hello"),
                LyricLine(startMs = 1_000L, endMs = 2_000L, text = "World"),
            ),
            title = "T",
            artist = "A",
            album = "Al",
            durationMs = 2_000L,
            lyricId = "qq:x",
        ),
    )!!

    @Test
    fun `a full-capable receiver gets the whole payload only for a full source`() {
        val payload = LyricRuntime.legacyPayload(lyric, 1, 1_500L, true, true)!!

        assertTrue(payload.hasAllLyrics())
        assertEquals(1, payload.currentLyricIndex)
        assertEquals(1_500L, payload.position)
        assertEquals("World", payload.lyric!!.text)
        assertEquals(2, payload.allLyricsCount)
    }

    @Test
    fun `a line-only receiver never gets the whole payload`() {
        val payload = LyricRuntime.legacyPayload(lyric, 1, 1_500L, true, false)!!

        assertFalse(payload.hasAllLyrics())
        assertEquals(1, payload.currentLyricIndex)
        assertEquals("World", payload.lyric!!.text)
    }

    @Test
    fun `a single-line source gives a full-capable receiver only the current line`() {
        val payload = LyricRuntime.legacyPayload(lyric, 0, 500L, false, true)!!

        assertFalse(payload.hasAllLyrics())
        assertEquals("Hello", payload.lyric!!.text)
    }

    @Test
    fun `before the first line both capabilities get metadata only`() {
        for (supportsFullLyric in listOf(true, false)) {
            val payload = LyricRuntime.legacyPayload(lyric, -1, 250L, true, supportsFullLyric)!!

            assertFalse(payload.hasAllLyrics())
            assertEquals(-1, payload.currentLyricIndex)
            assertNull(payload.lyric)
            assertEquals("T", payload.title)
        }
    }

    @Test
    fun `no payload exists before the first line of a single-line source`() {
        for (supportsFullLyric in listOf(true, false)) {
            assertNull(LyricRuntime.legacyPayload(lyric, -1, 250L, false, supportsFullLyric))
        }
    }

    @Test
    fun `an out-of-range line yields no line payload`() {
        assertNull(LyricRuntime.legacyPayload(lyric, 5, 0L, false, false))
    }

    @Test
    fun `each capability builds its payload at most once per broadcast`() {
        var calls = 0
        val produced = SuperLyricData()
        val payloads = LyricRuntime.CapabilityPayloads(
            LyricRuntime.LegacyPayloadProvider {
                calls++
                produced
            },
        )

        assertSame(produced, payloads.get(false))
        assertSame(produced, payloads.get(false))
        assertSame(produced, payloads.get(true))
        assertSame(produced, payloads.get(true))
        assertEquals(2, calls)
    }

    @Test
    fun `an empty payload is computed only once per capability`() {
        var calls = 0
        val payloads = LyricRuntime.CapabilityPayloads(
            LyricRuntime.LegacyPayloadProvider {
                calls++
                null
            },
        )

        assertNull(payloads.get(false))
        assertNull(payloads.get(false))
        assertNull(payloads.get(true))
        assertEquals(2, calls)
    }
}
