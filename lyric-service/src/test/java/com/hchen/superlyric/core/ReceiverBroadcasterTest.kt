@file:Suppress("DEPRECATION")

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
import android.os.RemoteCallbackList
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReceiverBroadcasterTest {

    @Test
    fun `registration and unregister update quota state sequentially`() {
        val broadcaster = ReceiverBroadcaster(FakeCallbacks())
        val receivers = (0 until 64).map { FakeReceiver() }
        receivers.forEachIndexed { index, receiver ->
            broadcaster.register(receiver, index + 1, index + 1, "p$index", false)
        }
        assertTrue(receivers.all { broadcaster.isRegistered(it) })

        val overflow = FakeReceiver()
        broadcaster.register(overflow, 100, 100, "overflow", false)
        assertFalse(broadcaster.isRegistered(overflow))

        broadcaster.unregister(receivers[0], 1, "p0")
        assertFalse(broadcaster.isRegistered(receivers[0]))
        broadcaster.unregister(receivers[0], 1, "p0")
        broadcaster.register(overflow, 100, 100, "overflow", false)
        assertTrue(broadcaster.isRegistered(overflow))
    }

    @Test
    fun `concurrent registration cannot exceed the receiver quota`() {
        val broadcaster = ReceiverBroadcaster(FakeCallbacks())
        val receivers = (0 until 96).map { FakeReceiver() }
        val pool = Executors.newFixedThreadPool(16)
        try {
            receivers.forEachIndexed { index, receiver ->
                pool.submit {
                    broadcaster.register(receiver, index + 1, index + 1, "p$index", false)
                }
            }
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }

        assertEquals(64, receivers.count { broadcaster.isRegistered(it) })
    }

    @Test
    fun `dead callback removes state and releases quota`() {
        val callbacks = FakeCallbacks()
        val broadcaster = ReceiverBroadcaster(callbacks)
        val receivers = (0 until 64).map { FakeReceiver() }
        receivers.forEachIndexed { index, receiver ->
            broadcaster.register(receiver, index + 1, index + 1, "p$index", false)
        }

        callbacks.removeForTest(receivers[0])
        val died = ReceiverBroadcaster::class.java.getDeclaredMethod(
            "handleCallbackDied",
            ISuperLyricReceiver::class.java,
        )
        died.isAccessible = true
        died.invoke(broadcaster, receivers[0])

        assertFalse(broadcaster.isRegistered(receivers[0]))
        val replacement = FakeReceiver()
        broadcaster.register(replacement, 100, 100, "replacement", false)
        assertTrue(broadcaster.isRegistered(replacement))
    }

    private class FakeCallbacks : RemoteCallbackList<ISuperLyricReceiver>() {
        private val items = LinkedHashMap<IBinder, ISuperLyricReceiver>()

        override fun getRegisteredCallbackCount(): Int = items.size

        override fun register(callback: ISuperLyricReceiver): Boolean {
            items[callback.asBinder()] = callback
            return true
        }

        override fun unregister(callback: ISuperLyricReceiver): Boolean =
            items.remove(callback.asBinder()) != null

        fun removeForTest(callback: ISuperLyricReceiver) {
            items.remove(callback.asBinder())
        }
    }

    private class FakeReceiver : ISuperLyricReceiver {
        private val binder: IBinder = object : Binder() {
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit

            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true

            override fun isBinderAlive(): Boolean = true
        }

        override fun asBinder(): IBinder = binder

        override fun onLyric(publisher: String, data: SuperLyricData) {
        }

        override fun onStop(publisher: String, data: SuperLyricData) {
        }
    }
}
