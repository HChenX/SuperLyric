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
