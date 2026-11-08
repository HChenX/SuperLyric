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

import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.kernel.text.LyricText
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.Word
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 发布路径上的文本净化
 *
 * 剥离不可见字符（含实体解码产出的 NUL），并判定逐字数据与行文本是否一致。
 */
class LyricSanitizerTest {

    @Test
    fun `every invisible or control character is stripped`() {
        val dirty = "a\u0000b​c‌d‍e﻿f\rg\nh"

        assertEquals("abcdefgh", LyricSanitizer.cleanInvisibleChars(dirty))
    }

    @Test
    fun `the strip policy is the one the kernel defines`() {
        val samples = listOf("", "plain", "a\u0000b", "x​y", "p\r\nq", "﻿")

        for (sample in samples) {
            assertEquals(
                LyricText.cleanInvisibleChars(sample),
                LyricSanitizer.cleanInvisibleChars(sample),
                "样本 ${sample.length} 字符的净化结果必须与内核一致",
            )
        }
    }

    @Test
    fun `words survive when the only difference is a full width space`() {
        val line = Line(
            "a　b",
            0L,
            1_000L,
            listOf(Word("a", 0L, 500L), Word("b", 500L, 1_000L)),
            null,
            null,
        )

        val cleaned = LyricSanitizer.sanitizeLine(line)

        assertNotNull(cleaned)
        assertNotNull(cleaned!!.words, "只差一个全角空格不该让逐字数据被判废")
        assertEquals(2, cleaned.words!!.size)
    }

    @Test
    fun `a word starting after the line end is pulled back inside`() {
        val line = Line(
            "ab",
            0L,
            1_000L,
            listOf(Word("a", 0L, 400L), Word("b", 1_500L, 1_600L)),
            null,
            null,
        )

        val cleaned = LyricSanitizer.sanitizeLine(line)

        assertNotNull(cleaned)
        val words = cleaned!!.words
        assertNotNull(words)
        assertTrue(words!!.all { it.start >= 0L && it.end <= 1_000L }, "逐字时序必须落在行内")
        assertEquals("ab", words.joinToString("") { it.text })
    }

    @Test
    fun `line normalizer output survives sanitizer unchanged`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = "[00:01.00]<00:01.00>a<00:01.00>b",
            )
        )
        val line = doc!!.lines.single()
        val input = Line(
            line.text,
            line.startMs,
            line.endMs,
            line.words.map { Word(it.text, it.startMs, it.endMs) }.takeIf { it.isNotEmpty() },
            null,
            null,
        )

        val cleaned = LyricSanitizer.sanitizeLine(input)

        assertNotNull(cleaned)
        assertEquals(input.text, cleaned!!.text)
        assertEquals(input.start, cleaned.start)
        assertEquals(input.end, cleaned.end)
        assertEquals(input.words, cleaned.words)
    }

    @Test
    fun `a word after a full-line word is merged without overlap`() {
        val line = Line(
            "ab",
            0L,
            1_000L,
            listOf(Word("a", 0L, 1_000L), Word("b", 1_500L, 1_600L)),
            null,
            null,
        )

        val cleaned = LyricSanitizer.sanitizeLine(line)

        assertNotNull(cleaned)
        val words = cleaned!!.words
        assertNotNull(words)
        var previousEnd = 0L
        for (word in words!!) {
            assertTrue(word.start >= previousEnd, "词起点不得早于前词结束")
            assertTrue(word.end >= word.start, "词尾不得早于词头")
            assertTrue(word.start >= 0L && word.end <= 1_000L, "词必须落在行内")
            previousEnd = word.end
        }
        assertEquals("ab", words.joinToString("") { it.text }, "越界词文本必须保留")
    }

    @Test
    fun `an out-of-line word after a merge still advances the cursor`() {
        assertOrdered(
            sanitizeWords(
                Line(
                    "abc",
                    0L,
                    1_000L,
                    listOf(
                        Word("a", 0L, 500L),
                        Word("b", 1_500L, 1_600L),
                        Word("c", 800L, 900L),
                    ),
                    null,
                    null,
                )
            ),
            "abc",
        )
    }

    @Test
    fun `a first word beyond the line end is pulled inside`() {
        assertOrdered(
            sanitizeWords(
                Line(
                    "ab",
                    0L,
                    1_000L,
                    listOf(Word("a", 1_500L, 1_600L), Word("b", 1_700L, 1_800L)),
                    null,
                    null,
                )
            ),
            "ab",
        )
    }

    @Test
    fun `zero-duration and overlapping words stay ordered`() {
        assertOrdered(
            sanitizeWords(
                Line(
                    "ab",
                    0L,
                    1_000L,
                    listOf(Word("a", 0L, 0L), Word("b", 500L, 600L)),
                    null,
                    null,
                )
            ),
            "ab",
        )
        assertOrdered(
            sanitizeWords(
                Line(
                    "ab",
                    0L,
                    1_000L,
                    listOf(Word("a", 0L, 600L), Word("b", 500L, 700L)),
                    null,
                    null,
                )
            ),
            "ab",
        )
    }

    private fun sanitizeWords(line: Line): List<Word> {
        val cleaned = LyricSanitizer.sanitizeLine(line)
        assertNotNull(cleaned)
        val words = cleaned!!.words
        assertNotNull(words)
        return words!!
    }

    @Test
    fun `last word beyond the line end and last zero word are contained`() {
        assertOrdered(
            sanitizeWords(
                Line(
                    "ab",
                    0L,
                    1_000L,
                    listOf(Word("a", 0L, 500L), Word("b", 1_500L, 1_600L)),
                    null,
                    null,
                )
            ),
            "ab",
        )
        assertOrdered(
            sanitizeWords(
                Line(
                    "ab",
                    0L,
                    1_000L,
                    listOf(Word("a", 0L, 500L), Word("b", 1_000L, 1_000L)),
                    null,
                    null,
                )
            ),
            "ab",
        )
    }

    @Test
    fun `middle word beyond the line end does not overlap the next`() {
        assertOrdered(
            sanitizeWords(
                Line(
                    "abc",
                    0L,
                    1_000L,
                    listOf(
                        Word("a", 0L, 300L),
                        Word("b", 1_500L, 1_600L),
                        Word("c", 800L, 900L),
                    ),
                    null,
                    null,
                )
            ),
            "abc",
        )
    }

    @Test
    fun `non-zero line start is preserved as the lower bound`() {
        assertOrdered(
            sanitizeWords(
                Line(
                    "ab",
                    100L,
                    1_100L,
                    listOf(Word("a", 100L, 600L), Word("b", 1_500L, 1_600L)),
                    null,
                    null,
                )
            ),
            "ab",
            100L,
            1_100L,
        )
    }

    private fun assertOrdered(
        words: List<Word>,
        text: String,
        lineStart: Long = 0L,
        lineEnd: Long = 1_000L,
    ) {
        var previousEnd = lineStart
        for (word in words) {
            assertTrue(word.start >= previousEnd, "词起点不得早于前词结束")
            assertTrue(word.end >= word.start, "词尾不得早于词头")
            assertTrue(word.start >= lineStart && word.end <= lineEnd, "词必须落在行内")
            previousEnd = word.end
        }
        assertEquals(text, words.joinToString("") { it.text }, "越界词文本必须保留")
    }

    @Test
    fun `words survive when the line carries an invisible character`() {
        val line = Line(
            "a​b",
            0L,
            1_000L,
            listOf(Word("a", 0L, 500L), Word("b", 500L, 1_000L)),
            null,
            null,
        )

        val cleaned = LyricSanitizer.sanitizeLine(line)

        assertNotNull(cleaned)
        assertNotNull(cleaned!!.words)
        assertEquals(2, cleaned.words!!.size)
    }
}
