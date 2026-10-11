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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 取词令牌的语义。
 *
 * 它取代的是原先共用、会被新 `start()` 重置的 `cancelled` 布尔：没有它，一首旧曲目在途的
 * 请求会把自己**过期**的结果交付出去。本地这次检查只做预筛（权威判定在编排器的 epoch），
 * 但预筛判错一样会把上一首的歌词挂到新曲目上，所以这几条要钉住。
 */
class FetchSlotTest {

    /** 直接执行即可：这里测的是令牌语义，不是线程池 */
    private val slot = FetchSlot { it.run() }

    @Test
    fun `a fresh token is current`() {
        val token = slot.open()

        assertTrue(slot.isCurrent(token))
    }

    @Test
    fun `a newer request supersedes the previous token`() {
        // 切歌 = 新请求：上一首在途的请求必须立刻失去投递资格
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
        // 各来源里的写法就是这个形状：令牌对不上就不交付
        var delivered = false
        val stale = slot.open()
        slot.open()

        slot.execute {
            if (slot.isCurrent(stale)) delivered = true
        }

        assertFalse(delivered)
    }
}
