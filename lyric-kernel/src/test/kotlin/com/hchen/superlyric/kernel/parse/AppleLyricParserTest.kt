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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Apple 宿主行模型的映射契约
 *
 * 行文本 trim 后的内容必须与逐字拼接一致；逐字切片保留原有空格，
 * 否则按字符索引做的高亮会漏掉词边界。
 */
class AppleLyricParserTest {

    @Test
    fun `host lines are mapped onto the lyric document`() {
        val doc = AppleLyricParser.parse(
            lines = listOf(
                AppleLyricParser.Line(
                    text = "Hello world",
                    translation = "你好 世界",
                    startMs = 1_000L,
                    endMs = 2_000L,
                    words = listOf(
                        AppleLyricParser.Word("Hello ", 1_000L, 1_500L),
                        AppleLyricParser.Word("world", 1_500L, 2_000L),
                    ),
                ),
            ),
            title = "Song",
            artist = "Artist",
            durationMs = 60_000L,
            lyricId = "apple:123",
        )

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("Hello world", line.text)
        assertEquals("你好 世界", line.translation)
        assertEquals(1_000L, line.startMs)
        assertEquals(2_000L, line.endMs)
        assertEquals(listOf("Hello ", "world"), line.words.map { it.text })
        assertEquals("Song", doc.title)
        assertEquals("Artist", doc.artist)
        assertEquals(60_000L, doc.durationMs)
        assertEquals("apple:123", doc.lyricId)
    }

    @Test
    fun `lines are sorted by start`() {
        val doc = AppleLyricParser.parse(
            lines = listOf(
                line("later", 2_000L),
                line("earlier", 1_000L),
            ),
            title = "Song",
            artist = "Artist",
            durationMs = 60_000L,
            lyricId = null,
        )

        assertNotNull(doc)
        assertEquals(listOf("earlier", "later"), doc!!.lines.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), doc.lines.map { it.startMs })
    }

    @Test
    fun `blank lines and words are dropped`() {
        val doc = AppleLyricParser.parse(
            lines = listOf(
                line("<i></i>", 0L),
                AppleLyricParser.Line(
                    text = "Kept",
                    translation = "   ",
                    startMs = 1_000L,
                    endMs = 2_000L,
                    words = listOf(
                        AppleLyricParser.Word("<i></i>", 1_000L, 1_200L),
                        AppleLyricParser.Word("Kept", 1_200L, 2_000L),
                    ),
                ),
            ),
            title = "Song",
            artist = "Artist",
            durationMs = 60_000L,
            lyricId = null,
        )

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size)
        assertEquals("Kept", doc.lines[0].text)
        assertNull(doc.lines[0].translation)
        assertEquals(listOf("Kept"), doc.lines[0].words.map { it.text })
    }

    @Test
    fun `word boundary spaces survive so the join matches the line text`() {
        val doc = AppleLyricParser.parse(
            lines = listOf(
                AppleLyricParser.Line(
                    text = " Hello world ",
                    translation = "",
                    startMs = 0L,
                    endMs = 2_000L,
                    words = listOf(
                        AppleLyricParser.Word("Hello ", 0L, 1_000L),
                        AppleLyricParser.Word("world", 1_000L, 2_000L),
                    ),
                ),
            ),
            title = "Song",
            artist = "Artist",
            durationMs = 60_000L,
            lyricId = null,
        )

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("Hello world", line.text)
        assertEquals("Hello ", line.words[0].text)
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test
    fun `html markup entities and break tags are normalised`() {
        val doc = AppleLyricParser.parse(
            lines = listOf(
                AppleLyricParser.Line(
                    text = "it&#39;s <i>mine</i><br>now",
                    translation = "ok &amp; fine",
                    startMs = 0L,
                    endMs = 1_000L,
                    words = listOf(
                        AppleLyricParser.Word("it&#39;s ", 0L, 400L),
                        AppleLyricParser.Word("<i>mine</i>", 400L, 700L),
                        AppleLyricParser.Word("<br>now", 700L, 1_000L),
                    ),
                ),
            ),
            title = "Song",
            artist = "Artist",
            durationMs = 60_000L,
            lyricId = null,
        )

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("it's mine now", line.text)
        assertEquals("ok & fine", line.translation)
        assertEquals("it's mine now", line.words.joinToString("") { it.text })
    }

    @Test
    fun `a host model without usable lines yields no document`() {
        val doc = AppleLyricParser.parse(
            lines = listOf(line("", 0L), line("<span></span>", 1_000L)),
            title = "Song",
            artist = "Artist",
            durationMs = 60_000L,
            lyricId = null,
        )

        assertNull(doc)
    }

    private fun line(text: String, startMs: Long) =
        AppleLyricParser.Line(text, "", startMs, startMs + 1_000L, emptyList())
}
