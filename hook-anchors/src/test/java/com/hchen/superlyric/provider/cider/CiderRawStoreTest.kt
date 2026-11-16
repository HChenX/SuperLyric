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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.cider

import com.hchen.superlyric.kernel.decode.PayloadLimits
import com.hchen.superlyric.provider.cider.CiderRawStore.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CiderRawStoreTest {

    @Test
    fun `automatic selection uses the host resolved backend instead of the first response`() {
        val store = CiderRawStore()
        assertFalse(store.setSelection(null))
        capture(store, "A", Source.APPLE, listOf("apple raw"))
        capture(store, "A", Source.COMMUNITY, listOf("community raw"))

        assertTrue(store.selected("A", null).isEmpty())
        assertEquals(listOf("community raw"), store.selected("A", Source.COMMUNITY))
        assertEquals(listOf("apple raw"), store.selected("A", Source.APPLE))
    }

    @Test
    fun `explicit backends never use another source or another catalog id`() {
        val store = CiderRawStore()
        assertFalse(store.setSelection(null))
        for (source in Source.values()) {
            capture(store, "123", source, listOf("raw $source"))
        }
        capture(store, "1234", Source.APPLE, listOf("another track"))

        for (source in Source.values()) {
            store.setSelection(source)
            assertEquals(listOf("raw $source"), store.selected("123", source))
            assertTrue(store.selected("12", source).isEmpty())
            for (other in Source.values().filter { it != source }) {
                assertTrue(store.selected("123", other).isEmpty())
            }
        }
        store.setSelection(Source.APPLE)
        assertEquals(listOf("another track"), store.selected("1234", Source.APPLE))
    }

    @Test
    fun `selection changes reject old asynchronous payloads without invalidating matching cached sources`() {
        val store = CiderRawStore()
        assertFalse(store.setSelection(Source.APPLE))
        capture(store, "A", Source.APPLE, listOf("cached apple"))
        store.enter("B", Source.APPLE, null, false)
        val stale = store.current()!!
        store.exit()

        assertFalse(store.setSelection(Source.APPLE))
        assertTrue(store.setSelection(Source.NETEASE))
        store.capture(stale, listOf("late apple"))
        capture(store, "B", Source.NETEASE, listOf("selected netease"))

        assertEquals(listOf("selected netease"), store.selected("B", Source.NETEASE))
        store.setSelection(Source.APPLE)
        assertTrue(store.selected("B", Source.APPLE).isEmpty())
        assertEquals(listOf("cached apple"), store.selected("A", Source.APPLE))
    }

    @Test
    fun `a continuation keeps its original catalog id epoch and enhanced request across threads`() {
        val store = CiderRawStore()
        store.setSelection(null)
        val continuation = Any()
        store.enter("A", Source.APPLE, null, true)
        val original = store.current()!!
        store.bind(continuation)
        store.exit()
        assertNull(store.current())
        store.setSelection(Source.COMMUNITY)

        val resumed = CompletableFuture.supplyAsync {
            store.enter(null, Source.APPLE, continuation, false)
            try {
                store.current()
            } finally {
                store.exit()
            }
        }.get(5, TimeUnit.SECONDS)

        assertEquals(original, resumed)
        assertTrue(resumed!!.enhanced())
        store.capture(resumed, listOf("old selection"))
        store.setSelection(Source.APPLE)
        assertTrue(store.selected("A", Source.APPLE).isEmpty())
        assertNull(store.current())
    }

    @Test
    fun `nested invocations cannot borrow another backend continuation and cleanup restores the outer request`() {
        val store = CiderRawStore()
        val continuation = Any()
        store.enter("outer", Source.APPLE, null, false)
        val outer = store.current()
        store.bind(continuation)

        store.enter(null, Source.NETEASE, continuation, false)
        assertNull(store.current())
        store.exit()
        assertEquals(outer, store.current())

        store.enter("inner", Source.COMMUNITY, null, false)
        assertEquals("inner", store.current()!!.catalogId())
        store.exit()
        assertEquals(outer, store.current())
        store.exit()
        assertNull(store.current())
    }

    @Test
    fun `raw payloads remain immutable and source invalidation preserves other tracks`() {
        val store = CiderRawStore()
        val original = mutableListOf("<tt>raw</tt>", "", "<tt>raw</tt>", "[00:01]line")
        capture(store, "A", Source.APPLE, original)
        capture(store, "A", Source.NETEASE, listOf("netease raw"))
        capture(store, "B", Source.APPLE, listOf("track B"))
        original.clear()

        assertEquals(listOf("<tt>raw</tt>", "[00:01]line"), store.selected("A", Source.APPLE))
        assertThrows(UnsupportedOperationException::class.java) { store.selected("A", Source.APPLE).clear() }

        store.invalidate("A", Source.APPLE)
        assertTrue(store.selected("A", Source.APPLE).isEmpty())
        assertEquals(listOf("netease raw"), store.selected("A", Source.NETEASE))
        store.invalidate("A", null)
        assertTrue(store.selected("A", Source.NETEASE).isEmpty())
        assertEquals(listOf("track B"), store.selected("B", Source.APPLE))
    }

    @Test
    fun `entry budget evicts the least recently used track while retaining a revisited track`() {
        val store = CiderRawStore()
        for (id in 1..30) capture(store, "$id", Source.APPLE, listOf("track $id"))
        assertEquals(listOf("track 1"), store.selected("1", Source.APPLE))
        capture(store, "31", Source.APPLE, listOf("track 31"))

        assertEquals(listOf("track 1"), store.selected("1", Source.APPLE))
        assertTrue(store.selected("2", Source.APPLE).isEmpty())
        assertEquals(listOf("track 31"), store.selected("31", Source.APPLE))
    }

    @Test
    fun `total payload budget includes replacements and refuses oversized or empty captures`() {
        val store = CiderRawStore()
        val maxCharacters = PayloadLimits.MAX_PAYLOAD_BYTES / Character.BYTES
        val large = "x".repeat(maxCharacters / 2 + 1)
        capture(store, "A", Source.APPLE, listOf(large))
        capture(store, "B", Source.APPLE, listOf(large))
        assertTrue(store.selected("A", Source.APPLE).isEmpty())

        capture(store, "B", Source.APPLE, listOf("small replacement"))
        capture(store, "C", Source.APPLE, listOf(large))
        assertEquals(listOf("small replacement"), store.selected("B", Source.APPLE))
        assertEquals(listOf(large), store.selected("C", Source.APPLE))

        capture(store, "oversized", Source.APPLE, listOf("x".repeat(maxCharacters + 1)))
        capture(store, "empty", Source.APPLE, listOf("", " \n "))
        assertTrue(store.selected("oversized", Source.APPLE).isEmpty())
        assertTrue(store.selected("empty", Source.APPLE).isEmpty())
        assertEquals(listOf("small replacement"), store.selected("B", Source.APPLE))
    }

    @Test
    fun `plain Apple fallback preserves enhanced raw candidates and a new enhanced reply refreshes them`() {
        val store = CiderRawStore()
        store.setSelection(Source.APPLE)
        capture(store, "A", Source.APPLE, listOf("enhanced TTML"), enhanced = true)
        capture(store, "A", Source.APPLE, listOf("plain TTML"))
        capture(store, "A", Source.APPLE, listOf("plain TTML"))

        assertEquals(listOf("enhanced TTML", "plain TTML"), store.selected("A", Source.APPLE))

        capture(store, "A", Source.APPLE, listOf("refreshed enhanced TTML"), enhanced = true)
        assertEquals(listOf("refreshed enhanced TTML"), store.selected("A", Source.APPLE))
        capture(store, "A", Source.APPLE, listOf("refreshed plain TTML"))
        assertEquals(
            listOf("refreshed enhanced TTML", "refreshed plain TTML"),
            store.selected("A", Source.APPLE),
        )
    }

    @Test
    fun `an oversized Apple merge retains the enhanced payload without exceeding the raw budget`() {
        val store = CiderRawStore()
        val maxCharacters = PayloadLimits.MAX_PAYLOAD_BYTES / Character.BYTES
        val enhanced = "e".repeat(maxCharacters / 2 + 1)
        val plain = "p".repeat(maxCharacters / 2 + 1)
        capture(store, "A", Source.APPLE, listOf(enhanced), enhanced = true)
        capture(store, "A", Source.APPLE, listOf(plain))
        assertEquals(listOf(enhanced), store.selected("A", Source.APPLE))

        capture(store, "A", Source.APPLE, listOf("new enhanced"), enhanced = true)
        capture(store, "B", Source.APPLE, listOf(plain))
        assertEquals(listOf("new enhanced"), store.selected("A", Source.APPLE))
        assertEquals(listOf(plain), store.selected("B", Source.APPLE))
    }

    private fun capture(
        store: CiderRawStore,
        id: String,
        source: Source,
        texts: List<String>,
        enhanced: Boolean = false,
    ) {
        store.enter(id, source, null, enhanced)
        try {
            store.capture(store.current(), texts)
        } finally {
            store.exit()
        }
    }
}
