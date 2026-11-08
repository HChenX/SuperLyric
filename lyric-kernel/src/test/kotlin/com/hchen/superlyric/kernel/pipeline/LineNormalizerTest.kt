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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.kernel.pipeline

import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LineNormalizerTest {

    @Test
    fun `valid words stay untouched`() {
        val line = line("ab", listOf(word("a", 0, 100), word("b", 100, 200)))
        val words = normalizedWords(line)
        assertEquals(listOf(word("a", 0, 100), word("b", 100, 200)), words)
    }

    @Test
    fun `space token overlapping the next glyph is merged`() {
        val line = line(
            "天 青",
            listOf(word("天", 0, 200), word(" ", 200, 350), word("青", 200, 400)),
        )
        val words = normalizedWords(line)
        assertEquals(listOf(word("天", 0, 200), word(" 青", 200, 400)), words)
        assertWordInvariants(line, words)
    }

    @Test
    fun `zero length space is absorbed by the previous word`() {
        val line = line(
            "天 青",
            listOf(word("天", 0, 200), word(" ", 200, 200), word("青", 200, 400)),
        )
        val words = normalizedWords(line)
        assertEquals(listOf(word("天 ", 0, 200), word("青", 200, 400)), words)
        assertWordInvariants(line, words)
    }

    @Test
    fun `zero length first word is merged with the next`() {
        val line = line(" a", listOf(word(" ", 0, 0), word("a", 0, 200)))
        val words = normalizedWords(line)
        assertEquals(listOf(word(" a", 0, 200)), words)
        assertWordInvariants(line, words)
    }

    @Test
    fun `overlapping title line collapses into one word`() {
        val text = "青花瓷 - 周杰伦"
        val line = line(
            text,
            listOf(
                word("青", 25, 175),
                word("花", 25, 62),
                word("瓷 ", 62, 113),
                word("- ", 113, 413),
                word("周", 113, 164),
                word("杰", 164, 314),
                word("伦", 164, 215),
            ),
        )
        val words = normalizedWords(line)
        assertEquals(1, words.size)
        assertEquals(text, words.single().text)
        assertEquals(25L, words.single().startMs)
        assertEquals(413L, words.single().endMs)
        assertWordInvariants(line, words)
    }

    @Test
    fun `negative word start is clamped to the line`() {
        val line = line("ab", listOf(word("a", -10, 40), word("b", 50, 90)), endMs = 100)
        val words = normalizedWords(line)
        assertEquals(listOf(word("a", 0, 40), word("b", 50, 90)), words)
        assertWordInvariants(line, words)
    }

    @Test
    fun `zero length text line gets a duration before the next line`() {
        val lines = listOf(
            LyricLine(startMs = 0, endMs = 500, text = "main"),
            LyricLine(startMs = 757, endMs = 757, text = "作词：施人诚"),
            LyricLine(startMs = 760, endMs = 760, text = "作曲：张简君伟"),
            LyricLine(startMs = 763, endMs = 1000, text = "next"),
        )

        val normalized = LineNormalizer.normalize(lines, 1000)

        assertEquals(758L, normalized[1].endMs)
        assertEquals(761L, normalized[2].endMs)
        assertTrue(normalized[1].endMs > normalized[1].startMs)
        assertTrue(normalized[2].endMs > normalized[2].startMs)
        assertTrue(normalized[1].endMs <= normalized[2].startMs)
    }

    @Test
    fun `line text collapses whitespace while word slices keep their spacing`() {
        val line = line(
            "  hello   world  ",
            listOf(word("hello", 0, 100), word("   ", 100, 150), word("world", 150, 300)),
        )
        val normalized = normalized(line)

        assertEquals("hello world", normalized.text)
        assertEquals(
            listOf(word("hello", 0, 100), word("   ", 100, 150), word("world", 150, 300)),
            normalized.words,
        )
    }

    @Test
    fun `CJK line text is unchanged by whitespace normalization`() {
        assertEquals("未来へ", normalized(line("未来へ", emptyList())).text)
    }

    @Test
    fun `empty word list is preserved`() {
        assertTrue(normalizedWords(line("plain", emptyList())).isEmpty())
    }

    private fun normalizedWords(line: LyricLine): List<LyricWord> = normalized(line).words

    private fun normalized(line: LyricLine): LyricLine =
        LineNormalizer.normalize(listOf(line), line.endMs).single()

    private fun line(text: String, words: List<LyricWord>, endMs: Long = 1000): LyricLine =
        LyricLine(startMs = 0, endMs = endMs, text = text, words = words)

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start, end)

    private fun assertWordInvariants(line: LyricLine, words: List<LyricWord>) {
        var previousEnd = line.startMs
        for (word in words) {
            assertTrue(word.startMs >= line.startMs, "word starts before line: " + word)
            assertTrue(word.endMs <= line.endMs, "word ends after line: " + word)
            assertTrue(word.endMs > word.startMs, "word has no duration: " + word)
            assertTrue(word.startMs >= previousEnd, "word overlaps previous: " + word)
            previousEnd = word.endMs
        }
        assertEquals(
            line.text.replace(Regex("\\s+"), ""),
            words.joinToString("") { it.text }.replace(Regex("\\s+"), ""),
            "word text diverges from line text",
        )
    }
}
