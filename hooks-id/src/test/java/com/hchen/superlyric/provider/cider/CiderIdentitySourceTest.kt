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
package com.hchen.superlyric.provider.cider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CiderIdentitySourceTest {

    private val source = CiderIdentitySource("CiderProvider")

    @Test
    fun `catalog identity survives metadata refresh and distinguishes duplicate titles`() {
        val first = source.current("123", "Song", "Artist", 180_000L)
        val refreshed = source.current("123", "Song", "Artist", 180_050L)
        val other = source.current("456", "Song", "Artist", 180_000L)

        assertEquals("cider:123", first.stableId)
        assertEquals(first.stableId, refreshed.stableId)
        assertNotEquals(first.stableId, other.stableId)
        assertNull(first.catalogId)
    }

    @Test
    fun `library identifiers are preserved and placeholders stay provisional`() {
        assertEquals("cider:i.ABC123", source.current("i.ABC123", "Song", "Artist", 0L).stableId)
        for (id in listOf("", "0", "-1", "null", "unknown", "none")) {
            val identity = source.current(id, "Song", "Artist", 180_000L)
            assertNull(identity.stableId)
            assertEquals("Song", identity.title)
            assertEquals("Artist", identity.artist)
        }
    }
}
