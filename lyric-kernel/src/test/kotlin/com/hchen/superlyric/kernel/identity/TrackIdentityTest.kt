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
package com.hchen.superlyric.kernel.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 音轨同一性判定。
 *
 * 每条用例都对应一个重构前的真实缺陷。
 */
class TrackIdentityTest {

    private fun id(
        source: String = "qq",
        stableId: String? = null,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
    ) = TrackIdentity(source, stableId, title, artist, durationMs)

    @Test
    fun `namespaced and bare stable ids are comparable`() {
        val fromHook = id(source = "qq", stableId = "qq:005Ct2abcdefgh")
        val fromSession = id(source = "qq", stableId = "005Ct2abcdefgh")

        // 旧实现按字符串原样比较，两者永远不等，Hook 捕获会被当作过期数据丢弃
        assertTrue(fromHook.matches(fromSession))
    }

    @Test
    fun `different stable ids never match even with identical titles`() {
        val a = id(stableId = "qq:AAA", title = "Intro", artist = "X", durationMs = 60_000L)
        val b = id(stableId = "qq:BBB", title = "Intro", artist = "X", durationMs = 60_000L)

        assertFalse(a.matches(b))
    }

    @Test
    fun `same title with clearly different durations does not match`() {
        val studio = id(title = "Song", artist = "Artist", durationMs = 200_000L)
        val live = id(title = "Song", artist = "Artist", durationMs = 260_000L)

        // 旧实现只看标题，这两首会被永久合并，第二首永远取不到歌词
        assertFalse(studio.matches(live))
    }

    @Test
    fun `same title with close durations matches`() {
        val fromHost = id(title = "Song", artist = "Artist", durationMs = 200_000L)
        val fromSession = id(title = "Song", artist = "Artist", durationMs = 200_500L)

        assertTrue(fromHost.matches(fromSession))
    }

    @Test
    fun `unknown duration on one side is not a negative signal`() {
        val known = id(title = "Song", artist = "Artist", durationMs = 200_000L)
        val unknown = id(title = "Song", artist = "Artist", durationMs = 0L)

        assertTrue(known.matches(unknown))
    }

    @Test
    fun `titles that normalise to empty never match`() {
        val a = id(title = "  ", artist = "Artist", durationMs = 1_000L)
        val b = id(title = "---", artist = "Artist", durationMs = 1_000L)

        assertFalse(a.matches(b))
    }

    @Test
    fun `bracketed suffixes are ignored during title comparison`() {
        val plain = id(title = "Song", artist = "Artist", durationMs = 100_000L)
        val bracketed = id(title = "Song (Live)", artist = "Artist", durationMs = 100_000L)

        assertTrue(plain.matches(bracketed))
    }

    @Test
    fun `incompatible artists do not match`() {
        val a = id(title = "Song", artist = "Alice", durationMs = 100_000L)
        val b = id(title = "Song", artist = "Bob", durationMs = 100_000L)

        assertFalse(a.matches(b))
    }

    @Test
    fun `multi artist notation is compatible with a single artist`() {
        val multi = id(title = "Song", artist = "Alice/Bob", durationMs = 100_000L)
        val single = id(title = "Song", artist = "Alice", durationMs = 100_000L)

        assertTrue(multi.matches(single))
    }

    @Test
    fun `null other never matches`() {
        assertFalse(id(title = "Song").matches(null))
    }

    @Test
    fun `stable id helper normalises blank input to null`() {
        assertNull(TrackIdentity.stableId("qq", null))
        assertNull(TrackIdentity.stableId("qq", "   "))
        assertEquals("qq:abc", TrackIdentity.stableId("qq", " abc "))
        assertEquals("abc", TrackIdentity.normalizeStableId("qq:abc"))
        assertEquals("abc", TrackIdentity.normalizeStableId("abc"))
        assertNull(TrackIdentity.normalizeStableId("  "))
    }
}
