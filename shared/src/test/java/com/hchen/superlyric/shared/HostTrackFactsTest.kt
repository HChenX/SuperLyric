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
package com.hchen.superlyric.shared

import com.hchen.superlyric.kernel.catalog.CatalogId
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostTrackFactsTest {

    private fun identity(
        stableId: String? = null,
        title: String = "Song",
        artist: String = "Artist",
        durationMs: Long = 1_000L,
        catalogId: CatalogId? = null,
    ) = TrackIdentity("test", stableId, title, artist, durationMs, catalogId)

    @Test
    fun `facts match the current track by title artist and duration`() {
        val facts = HostTrackFacts(identity())

        assertTrue(facts.matches(identity()))
        assertFalse(facts.matches(identity(title = "Other")))
    }

    @Test
    fun `merge keeps the stable id and fills host metadata`() {
        val current = identity(stableId = "test:x", title = "", artist = "", durationMs = 0L)
        val facts = HostTrackFacts(identity(title = "Host", artist = "Singer", durationMs = 9_000L))

        assertEquals(identity(stableId = "test:x", title = "Host", artist = "Singer", durationMs = 9_000L), facts.merge(current))
    }

    @Test
    fun `merge keeps the catalog id from facts or the current identity`() {
        val currentCatalog = CatalogId(LyricCatalog.QQ, "1")
        val factsCatalog = CatalogId(LyricCatalog.QQ, "2")
        val current = identity(stableId = "test:x", catalogId = currentCatalog)

        val withoutFactsCatalog = HostTrackFacts(identity(title = "Host"))
        assertEquals(currentCatalog, withoutFactsCatalog.merge(current).catalogId)

        val withFactsCatalog = HostTrackFacts(identity(title = "Host", catalogId = factsCatalog))
        assertEquals(factsCatalog, withFactsCatalog.merge(current).catalogId)
        assertEquals(factsCatalog, withFactsCatalog.merge(identity(stableId = "test:x")).catalogId)
    }
}
