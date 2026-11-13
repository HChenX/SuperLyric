/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.kernel.catalog

import com.hchen.superlyric.kernel.identity.TrackIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CatalogIdsTest {

    @Test
    fun `qq mid requires fourteen base62 characters with a letter`() {
        assertEquals("001Qu4I30nRtz1", CatalogIds.qqMid("001Qu4I30nRtz1"))
        assertNull(CatalogIds.qqMid("12345678901234"))
        assertNull(CatalogIds.qqMid("short"))
    }

    @Test
    fun `kugou hash is exactly thirty two hex characters`() {
        assertEquals(
            "0123456789ABCDEF0123456789ABCDEF",
            CatalogIds.kugouHash("0123456789ABCDEF0123456789ABCDEF"),
        )
        assertNull(CatalogIds.kugouHash("0123"))
    }

    @Test
    fun `numeric ids only accept one to fifteen digits`() {
        assertEquals(123L, CatalogIds.qqSongId("123"))
        assertEquals(123L, CatalogIds.neteaseSongId("123"))
        assertNull(CatalogIds.qqSongId("0"))
        assertNull(CatalogIds.qqSongId("-1"))
        assertNull(CatalogIds.qqSongId("1234567890123456"))
    }

    @Test
    fun `spotify track id is base62 with a bounded length`() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", CatalogIds.spotifyTrackId("4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(CatalogIds.spotifyTrackId("../track"))
    }

    @Test
    fun `lyric id prefers a matching catalog id over the stable id`() {
        val identity = TrackIdentity(
            source = "test",
            stableId = "qq:AAAAAAAAAAAAAA",
            catalogId = CatalogId(LyricCatalog.QQ, "001Qu4I30nRtz1"),
        )

        assertEquals(
            "001Qu4I30nRtz1",
            CatalogIds.lyricId(identity, LyricCatalog.QQ, CatalogIds::qqMid),
        )
    }

    @Test
    fun `lyric id ignores a catalog id from another catalog and falls back to the stable id`() {
        val identity = TrackIdentity(
            source = "test",
            stableId = "qq:001Qu4I30nRtz1",
            catalogId = CatalogId(LyricCatalog.KUGOU, "001Qu4I30nRtz1"),
        )

        assertEquals(
            "001Qu4I30nRtz1",
            CatalogIds.lyricId(identity, LyricCatalog.QQ, CatalogIds::qqMid),
        )
    }

    @Test
    fun `lyric id validates both sources with the catalog rule`() {
        val identity = TrackIdentity(
            source = "test",
            stableId = "qq:001Qu4I30nRtz1",
            catalogId = CatalogId(LyricCatalog.QQ, "not-a-mid"),
        )

        assertEquals(
            "001Qu4I30nRtz1",
            CatalogIds.lyricId(identity, LyricCatalog.QQ, CatalogIds::qqMid),
        )
    }

    @Test
    fun `lyric id is null when neither source is valid`() {
        val identity = TrackIdentity(
            source = "test",
            stableId = "qq:??",
            catalogId = CatalogId(LyricCatalog.QQ, "not-a-mid"),
        )

        assertNull(CatalogIds.lyricId(identity, LyricCatalog.QQ, CatalogIds::qqMid))
    }

}
