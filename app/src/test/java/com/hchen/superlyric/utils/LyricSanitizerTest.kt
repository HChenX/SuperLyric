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
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * 发布路径上的文本净化。
 *
 * 这一层是**发布前最后一道**：它放行什么，消费方就收到什么。两个策略在此钉住——
 * 不可见字符的剥离（含实体解码才能产出的 NUL）与逐字数据的一致性判定。
 */
class LyricSanitizerTest {

    @Test
    fun `every invisible or control character is stripped`() {
        // NUL 可由 `&#0;` 实体解码得到，而它同样会让下游按字符索引的逐字高亮越界
        val dirty = "a\u0000b​c‌d‍e﻿f\rg\nh"

        assertEquals("abcdefgh", LyricSanitizer.cleanInvisibleChars(dirty))
    }

    @Test
    fun `the strip policy is the one the kernel defines`() {
        // 表只有一份（内核 `LyricText`）。这里另写一份时曾漏掉 NUL，于是"往表里补字符"
        // 落在没生效的那一份上——发布路径把关的是本方法
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
        // 全角空格 U+3000 不属于 \s。只按 \s 归一化的话，"含全角空格"与"不含"会被判为不等，
        // 合法的逐字数据因此被整块丢弃
        val line = SuperLyricLine(
            "a　b",
            arrayOf(SuperLyricWord("a", 0L, 500L), SuperLyricWord("b", 500L, 1000L)),
            null,
            null,
            0L,
            1000L,
        )

        val cleaned = LyricSanitizer.sanitizeLine(line)

        assertNotNull(cleaned)
        assertNotNull(cleaned!!.words, "只差一个全角空格不该让逐字数据被判废")
        assertEquals(2, cleaned.words!!.size)
    }

    @Test
    fun `words survive when the line carries an invisible character`() {
        // 行文本里的零宽字符必须先被剥掉再与逐字拼接比对：否则比对必然失配，
        // 一条本可用的逐字数据会被当成损坏而清空
        val line = SuperLyricLine(
            "a​b",
            arrayOf(SuperLyricWord("a", 0L, 500L), SuperLyricWord("b", 500L, 1000L)),
            null,
            null,
            0L,
            1000L,
        )

        val cleaned = LyricSanitizer.sanitizeLine(line)

        assertNotNull(cleaned)
        assertNotNull(cleaned!!.words)
        assertEquals(2, cleaned.words!!.size)
    }
}
