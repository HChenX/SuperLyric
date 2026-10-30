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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * QQ 检索候选的歌手名解析
 *
 * 歌手占打分器 30% 的一栏。取不到它，满分候选会掉到 70 分，低于 75 的采纳线，
 * 检索路径的合法匹配全部不采纳，表现为"这首歌没有歌词"。
 *
 * 用例固定歌手字段的三种形状：对象数组、纯字符串数组、纯字符串，以及两个兜底键。
 * `optString` 遇到数组会返回非空白的 JSON 文本，因此数组形状不得落入字符串兜底。
 */
class QQNetworkSingersTest {

    @Test
    fun `an array of singer objects yields the joined names`() {
        // 接口返回的真实形状
        val item = JSONObject().put(
            "singer",
            JSONArray()
                .put(JSONObject().put("name", "周杰伦"))
                .put(JSONObject().put("name", "费玉清")),
        )

        assertEquals("周杰伦/费玉清", readSingers(item))
    }

    @Test
    fun `an array of plain strings yields the joined names`() {
        val item = JSONObject().put("singer", JSONArray().put("A").put("B"))

        assertEquals("A/B", readSingers(item))
    }

    @Test
    fun `a plain string singer is accepted`() {
        assertEquals("周杰伦", readSingers(JSONObject().put("singer", "周杰伦")))
    }

    @Test
    fun `the singername key is a fallback`() {
        assertEquals("周杰伦", readSingers(JSONObject().put("singername", "周杰伦")))
    }

    @Test
    fun `the singers key is a fallback for the array shape`() {
        val item = JSONObject().put("singers", JSONArray().put(JSONObject().put("name", "A")))

        assertEquals("A", readSingers(item))
    }

    @Test
    fun `an array never degrades into raw json text`() {
        // 数组形状不得落进字符串兜底，否则歌手分会静默归零
        val item = JSONObject().put(
            "singer",
            JSONArray().put(JSONObject().put("name", "周杰伦")),
        )

        val singers = readSingers(item)

        assertTrue(singers == "周杰伦", "实际得到 $singers —— 数组被当成了字符串")
        assertTrue(!singers.contains("name"), "歌手名里不该出现 JSON 键名：$singers")
    }

    @Test
    fun `a missing singer yields an empty string`() {
        assertEquals("", readSingers(JSONObject().put("title", "x")))
    }

    @Test
    fun `an empty array never degrades into raw json text`() {
        // 空数组若按取出的串是否为空分派，会继续落到字符串兜底，
        // 拿到 `[]` 这段非空白 JSON 文本
        assertEquals("", readSingers(JSONObject().put("singer", JSONArray())))
    }

    @Test
    fun `an array whose entries carry no name never degrades into raw json text`() {
        // 元素缺 name 时数组分支拼出空串，同样不能落回字符串兜底
        val item = JSONObject().put("singer", JSONArray().put(JSONObject().put("id", 1)))

        assertEquals("", readSingers(item))
    }

    /** `readSingers` 是私有的纯函数（只吃一个 JSONObject），用反射直接调用 */
    private fun readSingers(item: JSONObject): String =
        QQNetwork::class.java
            .getDeclaredMethod("readSingers", JSONObject::class.java)
            .apply { isAccessible = true }
            .invoke(QQNetwork, item) as String
}
