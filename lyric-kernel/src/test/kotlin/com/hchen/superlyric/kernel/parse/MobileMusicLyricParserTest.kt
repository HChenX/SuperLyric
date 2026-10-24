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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.kernel.parse

import com.hchen.superlyric.kernel.merge.TimeText
import com.hchen.superlyric.kernel.parse.MobileMusicLyricParser.NativeLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MobileMusicLyricParserTest {

    private val mrc = NativeLine(
        1_000L, 2_200L, "Hello world\r\n", null,
        listOf("Hello", " ", "world"), listOf(500L, 100L, 600L),
    )

    private fun line(start: Long, text: String, end: Long = 0L) =
        NativeLine(start, end, text, null, null, null)

    @Test
    fun `native MRC word intervals are accumulated and the host offset is subtracted`() {
        val doc = MobileMusicLyricParser.parse(listOf(mrc), listOf(TimeText(1_010L, "你好世界")), 5_000L, 200L)!!
        val line = doc.lines.single()
        assertEquals("Hello world", line.text)
        assertEquals("你好世界", line.translation)
        assertEquals(800L, line.startMs)
        assertEquals(2_000L, line.endMs)
        assertEquals(listOf(800L, 1_300L, 1_400L), line.words.map { it.startMs })
        assertEquals(listOf(1_300L, 1_400L, 2_000L), line.words.map { it.endMs })
        assertEquals(5_000L, doc.durationMs)
    }

    @Test
    fun `plain LRC ends at the next line or the duration and timed blank lines are preserved`() {
        val doc = MobileMusicLyricParser.parse(
            listOf(line(1_000L, "one"), line(2_000L, ""), line(3_000L, "two")), emptyList(), 5_000L, 0L,
        )!!
        assertEquals(listOf(2_000L, 3_000L, 5_000L), doc.lines.map { it.endMs })
        assertEquals("", doc.lines[1].text)
        assertTrue(doc.lines.all { !it.hasWords })
    }

    @Test
    fun `invalid word models fall back to line timing without losing lyrics`() {
        val malformed = listOf(
            mrc.copy(wordDurationsMs = listOf(500L)),
            mrc.copy(wordDurationsMs = listOf(500L, -100L, 600L)),
            mrc.copy(wordDurationsMs = listOf(0L, 100L, 600L)),
            mrc.copy(wordTexts = listOf("wrong", " ", "world")),
            mrc.copy(wordTexts = listOf(null, " ", "world")),
            mrc.copy(wordDurationsMs = listOf(500L, 100L, 601L)),
        )
        for (input in malformed) {
            val result = MobileMusicLyricParser.parse(listOf(input), emptyList(), 5_000L, 0L)!!.lines.single()
            assertEquals("Hello world", result.text)
            assertTrue(result.words.isEmpty(), "Invalid native words must not produce broken highlighting")
        }
    }

    @Test
    fun `empty or unordered payloads do not become deterministic no lyrics`() {
        assertNull(MobileMusicLyricParser.parse(emptyList(), emptyList(), 5_000L, 0L))
        assertNull(MobileMusicLyricParser.parse(listOf(line(-1L, "bad")), emptyList(), 5_000L, 0L))
        // 乱序不再整首拒绝：按起始排序后保留两行
        val sorted = MobileMusicLyricParser.parse(
            listOf(line(2_000L, "two"), line(1_000L, "one")), emptyList(), 5_000L, 0L,
        )!!
        assertEquals(listOf("one", "two"), sorted.lines.map { it.text })
        // 坏行占比超过 20% 才整体拒绝
        assertNull(
            MobileMusicLyricParser.parse(
                listOf(line(-1L, "bad"), line(1_000L, "a"), line(2_000L, "b"), line(3_000L, "c")),
                emptyList(), 5_000L, 0L,
            ),
        )
    }

    @Test
    fun `translations use the shared tolerance and never create or overwrite lyric lines`() {
        val original = listOf(line(1_000L, "a"), line(1_300L, "b").copy(translation = "内嵌"))
        val doc = MobileMusicLyricParser.parse(
            original, listOf(TimeText(1_010L, "甲"), TimeText(1_300L, "乙"), TimeText(3_000L, "orphan")), 5_000L, 0L,
        )!!
        assertEquals(2, doc.lines.size)
        assertEquals(listOf("甲", "内嵌"), doc.lines.map { it.translation })
        val outside = MobileMusicLyricParser.parse(
            listOf(line(1_000L, "a")), listOf(TimeText(1_151L, "too late")), 5_000L, 0L,
        )!!
        assertNull(outside.lines.single().translation)
    }

    @Test
    fun `translation alignment happens before a large positive offset collapses line starts`() {
        val doc = MobileMusicLyricParser.parse(
            listOf(line(100L, "a", 200L), line(500L, "b", 600L)),
            listOf(TimeText(500L, "乙")), 5_000L, 800L,
        )!!
        assertNull(doc.lines[0].translation)
        assertEquals("乙", doc.lines[1].translation)
        assertEquals(listOf(0L, 0L), doc.lines.map { it.startMs })
    }

    @Test
    fun `negative offsets delay the line and zero length words are not published after clipping`() {
        val delayed = MobileMusicLyricParser.parse(listOf(mrc), emptyList(), 5_000L, -200L)!!.lines.single()
        assertEquals(1_200L, delayed.startMs)
        assertEquals(2_400L, delayed.endMs)
        val clipped = MobileMusicLyricParser.parse(listOf(mrc), emptyList(), 5_000L, 1_600L)!!.lines.single()
        assertTrue(clipped.words.all { it.endMs > it.startMs })
        assertEquals(listOf("world"), clipped.words.map { it.text })
    }
}
