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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.base

import com.hchen.superlyric.kernel.outcome.LyricOutcome
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * 取词令牌的语义
 *
 * 每次新请求更换令牌，取消时令牌失效，在途请求据此判定结果是否仍可交付。
 * 本地检查只做预筛，权威判定在编排器的 epoch；预筛判错会把上一首的歌词挂到新曲目上。
 */
class FetchSlotTest {

    /** 同步执行任务，此处只测令牌语义，不涉及线程池 */
    private val slot = FetchSlot { it.run() }

    @Test
    fun `a fresh token is current`() {
        val token = slot.open()

        assertTrue(slot.isCurrent(token))
    }

    @Test
    fun `a newer request supersedes the previous token`() {
        // 切歌即新请求：上一首在途的请求立刻失去投递资格
        val first = slot.open()
        val second = slot.open()

        assertFalse(slot.isCurrent(first))
        assertTrue(slot.isCurrent(second))
    }

    @Test
    fun `cancelling invalidates the token in flight`() {
        val token = slot.open()

        slot.invalidate()

        assertFalse(slot.isCurrent(token), "取消之后不得再交付")
    }

    @Test
    fun `a request started after a cancel is current again`() {
        // 停止后重新播放同一首歌必须能重新取词
        slot.open()
        slot.invalidate()

        val token = slot.open()

        assertTrue(slot.isCurrent(token))
    }

    @Test
    fun `a result arriving after a newer request is not delivered`() {
        // 各来源的交付路径同此形状：令牌对不上就不交付
        var delivered = false
        val stale = slot.open()
        slot.open()

        slot.execute {
            if (slot.isCurrent(stale)) delivered = true
        }

        assertFalse(delivered)
    }

    @Test
    fun `launch delivers the resolved outcome`() {
        var delivered: LyricOutcome? = null

        slot.launch("test", { delivered = it }) { LyricOutcome.NoLyrics("none") }

        assertTrue(delivered is LyricOutcome.NoLyrics)
    }

    @Test
    fun `launch does not deliver after cancel`() {
        var delivered = false

        slot.launch("test", { delivered = true }) {
            slot.invalidate()
            LyricOutcome.NoLyrics("none")
        }

        assertFalse(delivered)
    }

    @Test
    fun `withCall cancels and cleans up when the block throws`() {
        val call = FakeCall()

        assertThrows(IOException::class.java) {
            slot.withCall(call) {
                slot.invalidate()
                call.execute()
            }
        }
        assertTrue(call.cancelled, "invalidate 必须取消已登记 Call")

        call.cancelled = false
        slot.invalidate()
        assertFalse(call.cancelled, "withCall finally 必须移除已完成的 Call")
    }

    private class FakeCall : Call {
        var cancelled = false

        override fun request(): Request = Request.Builder().url("https://example.invalid").build()

        override fun execute(): Response {
            if (cancelled) throw IOException("canceled")
            error("unexpected execute")
        }

        override fun enqueue(responseCallback: Callback) = Unit

        override fun addEventListener(eventListener: EventListener) = Unit

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(
            type: kotlin.reflect.KClass<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout()

        override fun clone(): Call = this
    }

    @Test
    fun `launch turns an exception into a transient failure`() {
        var delivered: LyricOutcome? = null

        slot.launch("test", { delivered = it }) { error("boom") }

        assertTrue(delivered is LyricOutcome.TransientFailure)
    }
}
