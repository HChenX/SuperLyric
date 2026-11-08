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
package com.hchen.superlyric.kernel.text

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LrcOffsetTest {

    @Test
    fun `positive offset shifts lines and words`() {
        val doc = doc(
            line(
                1000, 2000, "ab",
                word("a", 1000, 1500),
                word("b", 1500, 2000),
            ),
        )

        val shifted = LrcOffset.apply(doc, 250)

        assertEquals(1250L, shifted.lines.single().startMs)
        assertEquals(2250L, shifted.lines.single().endMs)
        assertEquals(1250L, shifted.lines.single().words[0].startMs)
        assertEquals(1750L, shifted.lines.single().words[0].endMs)
        assertEquals(1750L, shifted.lines.single().words[1].startMs)
        assertEquals(2250L, shifted.lines.single().words[1].endMs)
    }

    @Test
    fun `negative offset clamps line and word times to zero`() {
        val doc = doc(
            line(
                200, 800, "ab",
                word("a", 200, 400),
                word("b", 400, 600),
            ),
        )

        val shifted = LrcOffset.apply(doc, -500)

        assertEquals(0L, shifted.lines.single().startMs)
        assertEquals(300L, shifted.lines.single().endMs)
        // 直接 apply 只负责 clamp；正时长与行内不重叠由管线 offset 后的 normalize 恢复。
        assertTrue(shifted.lines.single().words.all { it.startMs >= 0L && it.endMs >= it.startMs })
    }

    @Test
    fun `parse removes offset but keeps metadata and picks the last value`() {
        val parsed = LrcOffset.parse(
            """
            [ti:Song]
            [ar:Artist]
            [offset:+250]
            [offset:-500]
            [00:01.00]alpha
            """.trimIndent(),
        )

        assertEquals(-500L, parsed.offsetMs)
        assertTrue(parsed.text.contains("[ti:Song]"))
        assertTrue(parsed.text.contains("[ar:Artist]"))
        assertFalse(parsed.text.contains("[offset:"))
        assertTrue(parsed.text.contains("[00:01.00]alpha"))
    }

    @Test
    fun `no offset leaves the payload unchanged`() {
        val text = "[ti:Song]\n[00:01.00]alpha"
        val parsed = LrcOffset.parse(text)

        assertEquals(0L, parsed.offsetMs)
        assertEquals(text, parsed.text)
        assertEquals(1000L, LrcOffset.apply(doc(line(1000, 2000, "alpha")), 0).lines.single().startMs)
    }

    private fun doc(vararg lines: LyricLine) = LyricDoc(lines = lines.toList())

    private fun line(
        start: Long,
        end: Long,
        text: String,
        vararg words: LyricWord,
    ) = LyricLine(startMs = start, endMs = end, text = text, words = words.toList())

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start, end)
}
