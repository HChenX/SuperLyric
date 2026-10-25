/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
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
package com.hchen.superlyric.provider.qq

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 锚点捕获的 songMID 与元数据标识之间如何取舍。
 *
 * ## 为什么值得一条测试
 * 锚点常**先于**新曲目的元数据到达，也可能**根本没触发**。前者要求不能盲目信锚点、
 * 后者要求不能盲目信元数据——判错任一方向都会漏切歌（新曲目被构造成上一首的 mid，
 * 编排器判为同一首、不重启取词，旧歌词继续显示）。
 */
class QQIdentityTest {

    private val midA = "001Qu4I30nRtz1"
    private val midB = "003aAYrm3GE0Ac"

    @Test
    fun `a stale anchor mid yields to a different mid carried by the metadata`() {
        // 锚点停在上一首、元数据已带新曲目的 mid：必须采用元数据那个
        assertEquals(midB, QQIdentity.preferredMid(midA, midB))
    }

    @Test
    fun `the anchor wins when the metadata carries no mid`() {
        // 元数据的 MEDIA_ID 常只是数字 songID（非 mid）：此时锚点是唯一可信来源
        assertEquals(midA, QQIdentity.preferredMid(midA, "1234567"))
    }

    @Test
    fun `the anchor wins when the metadata repeats the same mid`() {
        assertEquals(midA, QQIdentity.preferredMid(midA, midA))
    }

    @Test
    fun `a metadata mid is used when the anchor never fired`() {
        assertEquals(midB, QQIdentity.preferredMid(null, midB))
    }

    @Test
    fun `a 14 digit numeric song id is not a mid`() {
        assertFalse(QQIdentity.isMid("12345678901234"))
        assertNull(QQIdentity.preferredMid("12345678901234", "12345678901234"))
    }

    @Test
    fun `neither side being a mid yields null`() {
        assertNull(QQIdentity.preferredMid(null, null))
        assertNull(QQIdentity.preferredMid("123", "456"))
        // 长度不足 14 的串不是 songMID，不得被采纳
        assertNull(QQIdentity.preferredMid("abc", "shortid"))
    }
}
