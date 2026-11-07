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
package com.hchen.superlyric.anchors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnchorBusTest {

    @Test
    fun `every listener receives the event in registration order`() {
        val bus = AnchorBus<String>()
        val order = ArrayList<String>()
        bus.subscribe { order += "first:$it" }
        bus.subscribe { order += "second:$it" }

        bus.emit("A")

        assertEquals(listOf("first:A", "second:A"), order)
    }

    @Test
    fun `one failing listener does not stop the others`() {
        val bus = AnchorBus<String>()
        val received = ArrayList<String>()
        bus.subscribe { error("boom") }
        bus.subscribe { received += it }

        bus.emit("A")

        assertEquals(listOf("A"), received)
    }

    @Test
    fun `a new listener receives the last event and close unsubscribes`() {
        val bus = AnchorBus<String>()
        bus.emit("A")
        val received = ArrayList<String>()
        val subscription = bus.subscribe { received += it }

        assertEquals(listOf("A"), received)

        subscription.close()
        bus.emit("B")
        assertEquals(listOf("A"), received)
    }

    @Test
    fun `listeners can be added and removed independently`() {
        val bus = AnchorBus<String>()
        val first = ArrayList<String>()
        val second = ArrayList<String>()
        val one = bus.subscribe { first += it }
        bus.subscribe { second += it }

        one.close()
        bus.emit("A")

        assertTrue(first.isEmpty())
        assertEquals(listOf("A"), second)
    }
}
