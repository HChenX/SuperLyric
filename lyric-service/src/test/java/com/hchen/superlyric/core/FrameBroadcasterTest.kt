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

import android.os.Binder
import android.os.IBinder
import com.hchen.superlyricapi.v4.ILyricFrameListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class FrameBroadcasterTest {

    @Test
    fun `global quota rejects the sixty-fifth listener`() {
        val broadcaster = FrameBroadcaster()
        repeat(64) { index ->
            assertEquals(
                FrameBroadcaster.REGISTER_OK,
                broadcaster.register(FakeListener(), index + 1, index + 1, "p$index"),
            )
        }

        assertEquals(
            FrameBroadcaster.REGISTER_QUOTA,
            broadcaster.register(FakeListener(), 100, 100, "overflow"),
        )
    }

    @Test
    fun `per-uid quota rejects the seventeenth listener`() {
        val broadcaster = FrameBroadcaster()
        repeat(16) { index ->
            assertEquals(
                FrameBroadcaster.REGISTER_OK,
                broadcaster.register(FakeListener(), 7, index + 1, "same"),
            )
        }

        assertEquals(
            FrameBroadcaster.REGISTER_QUOTA,
            broadcaster.register(FakeListener(), 7, 17, "same"),
        )
    }

    @Test
    fun `unregister releases the quota slot`() {
        val broadcaster = FrameBroadcaster()
        val first = FakeListener()
        assertEquals(
            FrameBroadcaster.REGISTER_OK,
            broadcaster.register(first, 9, 1, "first"),
        )
        repeat(63) { index ->
            assertEquals(
                FrameBroadcaster.REGISTER_OK,
                broadcaster.register(FakeListener(), index + 1, index + 1, "p$index"),
            )
        }
        assertEquals(
            FrameBroadcaster.REGISTER_QUOTA,
            broadcaster.register(FakeListener(), 100, 100, "overflow"),
        )

        broadcaster.unregister(first, 1, "first")
        broadcaster.unregister(first, 1, "first")
        assertEquals(
            FrameBroadcaster.REGISTER_OK,
            broadcaster.register(FakeListener(), 101, 101, "after-unregister"),
        )
    }

    @Test
    fun `concurrent registration cannot exceed the global quota`() {
        val broadcaster = FrameBroadcaster()
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (0 until 96).map { index ->
                pool.submit<Int> {
                    broadcaster.register(FakeListener(), index + 1, index + 1, "p$index")
                }
            }
            val results = futures.map { it.get() }

            assertEquals(
                64,
                results.count { it == FrameBroadcaster.REGISTER_OK },
            )
            assertTrue(results.count { it == FrameBroadcaster.REGISTER_QUOTA } >= 32)
        } finally {
            pool.shutdownNow()
        }
    }

    private class FakeListener : ILyricFrameListener {
        private val binder: IBinder = Binder()

        override fun asBinder(): IBinder = binder

        override fun onFrame(frame: ByteArray) {
        }
    }
}
