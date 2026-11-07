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
package com.hchen.superlyric.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class PublisherRegistryTest {

    @BeforeEach
    fun reset() {
        PublisherRegistry.pids.clear()
        PublisherRegistry.generations.clear()
        PublisherRegistry.generationSequence.set(0L)
        PublisherRegistry.disabledPackages.clear()
    }

    @AfterEach
    fun clear() {
        reset()
    }

    @Test
    fun `quota rejects the thirty-third package and accepts all accepted ones`() {
        repeat(32) { i ->
            assertEquals(
                PublisherRegistry.Registration.OK,
                PublisherRegistry.registerWithinQuota("p$i", 1),
            )
        }

        assertEquals(32, PublisherRegistry.pids.size)
        assertEquals(
            PublisherRegistry.Registration.QUOTA,
            PublisherRegistry.registerWithinQuota("overflow", 1),
        )
        assertEquals(32, PublisherRegistry.pids.size)
    }

    @Test
    fun `concurrent registrations never exceed the package quota`() {
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (0 until 48).map { i ->
                pool.submit<PublisherRegistry.Registration> {
                    PublisherRegistry.registerWithinQuota("p$i", 1)
                }
            }
            val results = futures.map { it.get() }

            assertEquals(32, results.count { it == PublisherRegistry.Registration.OK })
            assertTrue(PublisherRegistry.pids.size <= 32)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `per-package quota rejects the ninth pid`() {
        repeat(8) { pid ->
            assertEquals(
                PublisherRegistry.Registration.OK,
                PublisherRegistry.registerWithinQuota("same", pid + 1),
            )
        }

        assertEquals(
            PublisherRegistry.Registration.QUOTA,
            PublisherRegistry.registerWithinQuota("same", 9),
        )
    }

    @Test
    fun `generation changes on every new process and disappears with the last one`() {
        assertTrue(PublisherRegistry.register("pkg", 1))
        val first = PublisherRegistry.generations.getValue("pkg")
        assertTrue(PublisherRegistry.register("pkg", 2))
        val second = PublisherRegistry.generations.getValue("pkg")
        assertTrue(second > first)

        assertFalse(PublisherRegistry.removeProcess("pkg", 1))
        assertEquals(second, PublisherRegistry.generations.getValue("pkg"))
        assertTrue(PublisherRegistry.removeProcess("pkg", 2))
        assertNull(PublisherRegistry.generations["pkg"])
    }

    @Test
    fun `session trimming keeps at most five sessions and preserves the active publisher`() {
        val registry = PublisherRegistry()
        registry.activePublisher = "active"
        registry.getOrCreateSession("active")

        repeat(8) { i -> registry.getOrCreateSession("p$i") }

        assertTrue(registry.sessions.size <= 5)
        assertNotNull(registry.sessions["active"])
    }
}
