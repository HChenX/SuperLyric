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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 系统元数据标题的可信度判定
 *
 * 部分宿主会把当前歌词行写进 TITLE，本条规则决定校验时能否使用该标题。
 * 同一曲目内标题变化即判为不可信；只观察一次不构成证据。
 * 规则针对字段行为，与宿主名称无关。
 */
class MediaFactsTest {

    @Test
    fun `a single observation is not evidence`() {
        val facts = MediaFacts()

        facts.update("rid:1", "Song", 180_000L)

        // 第一次看到的值也可能已经是被改写的歌词行，不能拿来否决发布
        assertFalse(facts.isTitleUsable())
        assertEquals("Song", facts.title())
    }

    @Test
    fun `a stable title stays usable`() {
        val facts = MediaFacts()

        facts.update("rid:1", "Song", 180_000L)
        facts.update("rid:1", "Song", 180_000L)

        assertTrue(facts.isTitleUsable())
        assertEquals("Song", facts.title())
        assertEquals(180_000L, facts.durationMs())
    }

    @Test
    fun `a title rewritten inside the same track is not usable`() {
        val facts = MediaFacts()

        facts.update("rid:1", "Song", 180_000L)
        facts.update("rid:1", "first lyric line", 180_000L)

        assertFalse(facts.isTitleUsable())
    }

    @Test
    fun `a new media id resets the verdict`() {
        val facts = MediaFacts()

        facts.update("rid:1", "Song", 180_000L)
        facts.update("rid:1", "lyric line", 180_000L)
        facts.update("rid:2", "Other", 200_000L)
        assertFalse(facts.isTitleUsable())

        facts.update("rid:2", "Other", 200_000L)
        assertTrue(facts.isTitleUsable())
        assertEquals("Other", facts.title())
        assertEquals(200_000L, facts.durationMs())
    }

    @Test
    fun `without a media id a large duration jump resets the verdict`() {
        val facts = MediaFacts()

        facts.update(null, "Song", 180_000L)
        facts.update(null, "lyric line", 180_000L)
        assertFalse(facts.isTitleUsable())

        facts.update(null, "Other", 240_000L)
        assertFalse(facts.isTitleUsable())

        facts.update(null, "Other", 240_000L)
        assertTrue(facts.isTitleUsable())
    }

    @Test
    fun `duration jitter alone does not reset the verdict`() {
        val facts = MediaFacts()

        facts.update("rid:1", "Song", 180_000L)
        facts.update("rid:1", "lyric line", 180_500L)

        assertFalse(facts.isTitleUsable())
        assertEquals(180_500L, facts.durationMs())
    }
}
