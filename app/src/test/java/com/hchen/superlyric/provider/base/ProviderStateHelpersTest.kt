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
package com.hchen.superlyric.provider.base

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderStateHelpersTest {

    private fun identity(id: String = "id-1") = TrackIdentity("test", id, "Title", "Artist", 1_000L)

    private val session = LyricSource.Session { }

    @Test
    fun `a new request invalidates the previous generation`() {
        val gate = SourceRequestGate()
        val first = gate.open(identity("a"), session)
        val second = gate.open(identity("b"), session)

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        assertEquals("b", gate.current()?.identity?.stableId)
    }

    @Test
    fun `invalidate drops the current request`() {
        val gate = SourceRequestGate()
        val request = gate.open(identity(), session)

        gate.invalidate()

        assertNull(gate.current())
        assertFalse(gate.isCurrent(request))
    }

    @Test
    fun `stash only matches the exact key and clears after take`() {
        val stash = KeyedStash<String>()
        stash.put("a", "value")

        assertNull(stash.takeIf("b"))
        assertEquals("value", stash.takeIf("a"))
        assertNull(stash.takeIf("a"))
    }

    @Test
    fun `empty stash keys never match`() {
        val stash = KeyedStash<String>()
        stash.put("", "value")
        stash.put(null, "value")

        assertNull(stash.takeIf(""))
        assertNull(stash.takeIf(null))
    }

    @Test
    fun `metadata prefers the current identity and fills the rest`() {
        val doc = LyricDoc(lines = emptyList(), title = "Parsed", artist = "", durationMs = 0L, lyricId = null)

        val result = doc.withMetadata(identity(), hostTitle = "Host", hostArtist = "Host Artist")

        assertEquals("Title", result.title)
        assertEquals("Artist", result.artist)
        assertEquals(1_000L, result.durationMs)
        assertEquals("id-1", result.lyricId)
    }

    @Test
    fun `metadata outcome leaves non-success untouched`() {
        val outcome: LyricOutcome = LyricOutcome.NoLyrics("host")

        assertEquals(outcome, outcome.withMetadata(identity()))
    }
}
