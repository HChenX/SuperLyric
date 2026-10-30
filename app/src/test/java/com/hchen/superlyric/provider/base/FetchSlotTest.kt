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
}
