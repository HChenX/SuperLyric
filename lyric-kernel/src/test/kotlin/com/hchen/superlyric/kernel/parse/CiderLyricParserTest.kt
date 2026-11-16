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
package com.hchen.superlyric.kernel.parse

import com.hchen.superlyric.kernel.model.LyricWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CiderLyricParserTest {

    @Test
    fun `absolute word times and boundary spaces survive mapping`() {
        val doc = CiderLyricParser.parse(
            listOf(
                CiderLyricParser.Line(
                    text = " Hello world ",
                    translation = "",
                    startMs = 10_000L,
                    endMs = 12_000L,
                    words = listOf(
                        CiderLyricParser.Word("Hello ", 10_000L, 11_000L),
                        CiderLyricParser.Word("world", 11_000L, 12_000L),
                    ),
                ),
            ),
            durationMs = 60_000L,
        )!!

        val line = doc.lines.single()
        assertEquals("Hello world", line.text)
        assertEquals(listOf("Hello ", "world"), line.words.map { it.text })
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals(listOf(10_000L, 11_000L), line.words.map { it.startMs })
        assertEquals(listOf(11_000L, 12_000L), line.words.map { it.endMs })
        assertEquals(60_000L, doc.durationMs)
        assertEquals("", doc.title)
        assertEquals("", doc.artist)
        assertNull(doc.lyricId)
    }

    @Test
    fun `translation stays separate and decoded host text stays literal`() {
        val doc = CiderLyricParser.parse(
            listOf(line("<3 &amp; love", 0L).copy(translation = " 爱与喜欢\u200B ")),
            durationMs = 2_000L,
        )!!

        assertEquals(1, doc.lines.size)
        assertEquals("<3 &amp; love", doc.lines.single().text)
        assertEquals("爱与喜欢", doc.lines.single().translation)
        assertNull(doc.lines.single().phonetic)
    }

    @Test
    fun `one background voice remains a separate simultaneous lyric line`() {
        val background = line("backing voice", 1_000L).copy(
            translation = "伴唱",
            background = line("unrelated nested model", 1_000L),
        )
        val doc = CiderLyricParser.parse(
            listOf(line("main voice", 1_000L).copy(translation = "主唱", background = background)),
            durationMs = 5_000L,
        )!!

        assertEquals(listOf("main voice", "backing voice"), doc.lines.map { it.text })
        assertEquals(listOf("主唱", "伴唱"), doc.lines.map { it.translation })
        assertEquals(listOf(1_000L, 1_000L), doc.lines.map { it.startMs })
        assertTrue(doc.lines.all { it.endMs > it.startMs })
    }

    @Test
    fun `instrumental sentinels and invisible content do not become lyric lines`() {
        val doc = CiderLyricParser.parse(
            listOf(
                line("Instrumental", 0L).copy(empty = true),
                line(" \u200B\u0000\r\n ", 0L),
                line("\uFEFFKe\u200Dpt\u0000", 1_000L).copy(
                    translation = " \u200C\r\n ",
                    words = listOf(
                        CiderLyricParser.Word("\u200B\u0000", 1_000L, 1_100L),
                        CiderLyricParser.Word("Ke\u200Dpt", 1_100L, 2_000L),
                    ),
                ),
            ),
            durationMs = 5_000L,
        )!!

        val line = doc.lines.single()
        assertEquals("Kept", line.text)
        assertNull(line.translation)
        assertEquals(listOf("Kept"), line.words.map { it.text })
    }

    @Test
    fun `unusable host lines yield no document`() {
        assertNull(CiderLyricParser.parse(emptyList(), 0L))
        assertNull(
            CiderLyricParser.parse(
                listOf(line("", 0L), line("\u0000\u200B", 100L), line("sentinel", 200L).copy(empty = true)),
                durationMs = 2_000L,
            ),
        )
        assertNull(CiderLyricParser.parse(listOf(line("far past the track", 500_000L)), 2_000L))
    }

    @Test
    fun `shared normalization repairs line order overlaps and out of bounds words`() {
        val doc = CiderLyricParser.parse(
            listOf(
                line("next", 2_000L).copy(endMs = 1_990L),
                line("ab", -100L).copy(
                    endMs = 5_000L,
                    words = listOf(
                        CiderLyricParser.Word("a", -10L, 500L),
                        CiderLyricParser.Word("b", 450L, 3_000L),
                    ),
                ),
            ),
            durationMs = 4_000L,
        )!!

        assertEquals(listOf("ab", "next"), doc.lines.map { it.text })
        assertEquals(listOf(0L, 2_000L), doc.lines.map { it.startMs })
        assertEquals(listOf(2_000L, 2_001L), doc.lines.map { it.endMs })
        val first = doc.lines.first()
        assertEquals(listOf(LyricWord("ab", 0L, 2_000L)), first.words)
        assertEquals(first.text, first.words.joinToString("") { it.text })
    }

    private fun line(text: String, startMs: Long) =
        CiderLyricParser.Line(text, "", startMs, startMs + 1_000L, emptyList())
}
