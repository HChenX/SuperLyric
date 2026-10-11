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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.spotify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Spotify 纯解析层测试。
 *
 * <p>Spotify 是订阅制服务，本机无法做真机验证（无 VIP），因此**解析层必须靠单测兜住**。
 * 这里只覆盖 [SpotifyLyricAnalysis.parseLyrics] 的 JSON 分支——它是纯函数，
 * 不触碰任何 Android API；网络、鉴权与 protobuf 二进制分支不在本测试范围内。
 *
 * @author 焕晨HChen
 */
class SpotifyLyricAnalysisTest {

    @Test
    fun `json lyrics keep timing transliteration and syllables`() {
        // 音节 count 必须**恰好覆盖整行文本**（实现里有 cursor == text.length() 的硬校验）：
        // "Hello world" 共 11 个字符 → 5 + 6
        val json = """
            {"lyrics":{"lines":[
              {"startTimeMs":"1000","words":"Hello world","endTimeMs":"3000",
               "transliteratedWords":"he-lou world",
               "syllables":[{"startTimeMs":"1000","count":5,"endTimeMs":"2000"},
                            {"startTimeMs":"2000","count":6,"endTimeMs":"3000"}]},
              {"startTimeMs":"4000","words":"Second line","endTimeMs":"6000"}
            ]}}
        """.trimIndent()

        val lines = SpotifyLyricAnalysis.parseLyrics(json)

        assertNotNull(lines)
        assertEquals(2, lines!!.size)

        assertEquals("Hello world", lines[0].text)
        assertEquals(1_000L, lines[0].startTimeMs)
        assertEquals(3_000L, lines[0].endTimeMs)
        assertEquals("he-lou world", lines[0].transliteratedWords)
        assertEquals(
            listOf("Hello", " world"),
            lines[0].words?.map { it.word },
            "音节必须按 count 原样切分",
        )

        assertEquals("Second line", lines[1].text)
        assertNull(lines[1].words, "没有音节数据时不得凭空造出逐字")
        assertNull(lines[1].transliteratedWords)
    }

    @Test
    fun `blank and negative lines are dropped and the rest is sorted`() {
        val json = """
            {"lyrics":{"lines":[
              {"startTimeMs":"5000","words":"Later","endTimeMs":"7000"},
              {"startTimeMs":"1000","words":"   ","endTimeMs":"2000"},
              {"startTimeMs":"-5","words":"Negative","endTimeMs":"100"},
              {"startTimeMs":"2000","words":"Earlier","endTimeMs":"4000"}
            ]}}
        """.trimIndent()

        val lines = SpotifyLyricAnalysis.parseLyrics(json)

        assertNotNull(lines)
        assertEquals(listOf("Earlier", "Later"), lines!!.map { it.text })
    }

    @Test
    fun `a line without an explicit end is bounded by the next line`() {
        val json = """
            {"lyrics":{"lines":[
              {"startTimeMs":"1000","words":"First","endTimeMs":"0"},
              {"startTimeMs":"3500","words":"Second","endTimeMs":"5000"}
            ]}}
        """.trimIndent()

        val lines = SpotifyLyricAnalysis.parseLyrics(json)

        assertNotNull(lines)
        assertEquals(2, lines!!.size)
        assertEquals(3_500L, lines[0].endTimeMs, "末位未知时应收口到下一行起始")
    }

    @Test
    fun `malformed or empty payloads yield no lines instead of throwing`() {
        assertNull(SpotifyLyricAnalysis.parseLyrics("not json at all"))
        assertNull(SpotifyLyricAnalysis.parseLyrics("{}"))
        assertNull(SpotifyLyricAnalysis.parseLyrics("""{"lyrics":{}}"""))
        // 结构合法但没有内容：返回空列表而非 null，调用方按"无歌词"处理
        assertTrue(SpotifyLyricAnalysis.parseLyrics("""{"lyrics":{"lines":[]}}""").isNullOrEmpty())
    }
}
