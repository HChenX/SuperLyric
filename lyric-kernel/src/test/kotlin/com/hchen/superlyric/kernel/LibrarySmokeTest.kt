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
package com.hchen.superlyric.kernel

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.core.parser.EnhancedLrcParser
import com.mocharealm.accompanist.lyrics.core.parser.ILyricsParser
import com.mocharealm.accompanist.lyrics.core.parser.KugouKrcParser
import com.mocharealm.accompanist.lyrics.core.parser.LyricifySyllableParser
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import com.mocharealm.accompanist.lyrics.core.parser.TTMLParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 冒烟测试：验证 `lyrics-core` 的公开 API 与既定设计假设一致，
 * 并确认自研插件可通过 [AutoParser] 的解析器列表插入。
 *
 * 若本测试失败，说明库的 API 与架构设计不符，应在继续实现前先修正设计。
 */
private object ProbeParser : ILyricsParser {
    override fun canParse(content: String): Boolean = content.startsWith("PROBE:")

    override fun parse(content: String): SyncedLyrics =
        SyncedLyrics(lines = listOf(SyncedLine("probe", null, 0, 100)))
}

class LibrarySmokeTest {

    @Test
    fun `plain lrc is parsed into synced lines`() {
        val lrc = "[00:01.00]Hello\n[00:03.50]World"

        val parsed = AutoParser().parse(lrc)

        assertEquals(2, parsed.lines.size)
        val first = parsed.lines[0] as SyncedLine
        assertEquals("Hello", first.content)
        assertEquals(1_000, first.start)
        assertEquals(3_500, first.end)
    }

    @Test
    fun `every built in parser is reachable as a callable instance`() {
        val parsers = listOf<ILyricsParser>(
            TTMLParser(),
            LyricifySyllableParser,
            EnhancedLrcParser,
            KugouKrcParser,
            NeteaseYrcParser,
        )

        val lrc = "[00:01.00]Hello"
        val matched = parsers.filter { it.canParse(lrc) }

        assertEquals(1, matched.size, "expect exactly one built-in parser to claim plain LRC")
        assertTrue(matched.single() === EnhancedLrcParser)
    }

    @Test
    fun `custom parser registered ahead of built ins wins`() {
        val parser = AutoParser(parsers = listOf(ProbeParser, EnhancedLrcParser))

        assertTrue(parser.canParse("PROBE:anything"))
        assertFalse(AutoParser(parsers = listOf(EnhancedLrcParser)).canParse("PROBE:anything"))

        val parsed = parser.parse("PROBE:anything")
        assertEquals(1, parsed.lines.size)
        assertEquals("probe", (parsed.lines[0] as SyncedLine).content)
    }

    @Test
    fun `parser ordering is a contract, later parsers never see matched content`() {
        val shadow = object : ILyricsParser {
            override fun canParse(content: String): Boolean = true
            override fun parse(content: String): SyncedLyrics =
                SyncedLyrics(lines = listOf(SyncedLine("shadow", null, 0, 1)))
        }

        // 先注册者通吃：即便后者 canParse 恒为真，也不会被调用
        val parsed = AutoParser(parsers = listOf(EnhancedLrcParser, shadow)).parse("[00:01.00]Hello")

        assertTrue(parsed.lines[0] is SyncedLine)
        assertEquals("Hello", (parsed.lines[0] as SyncedLine).content)
    }
}
