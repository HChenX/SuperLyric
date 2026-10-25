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
    fun `byte entry honours UTF-8 BOM before the JSON branch`() {
        val json = """{"lyrics":{"lines":[{"startTimeMs":"1000","words":"hello","syllables":[]}]}}"""
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                json.toByteArray(Charsets.UTF_8)

        val lines = SpotifyLyricAnalysis.parseLyrics(withBom)

        assertNotNull(lines)
        assertEquals(1, lines!!.size)
        assertEquals(1_000L, lines[0].startTimeMs)
    }


    @Test
    fun `syllables that go backwards drop words`() {
        // line: start=1, words="hello", syllable1 [100,200], syllable2 [50,300]
        val bytes = byteArrayOf(
            0x0A, 0x1D,
            0x12, 0x1B,
            0x08, 0x01,
            0x12, 0x05, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
            0x1A, 0x07, 0x08, 0x64, 0x10, 0x02, 0x18, 0xC8.toByte(), 0x01,
            0x1A, 0x07, 0x08, 0x32, 0x10, 0x03, 0x18, 0xAC.toByte(), 0x02,
        )

        val lines = SpotifyLyricAnalysis.parseLyrics(bytes)

        assertNotNull(lines)
        assertNull(lines!![0].words)
    }

    @Test
    fun `syllable count mismatch drops words but keeps the line`() {
        // field1 lyrics -> field2 line -> { start=1, words="hello", syllable{count=4} }
        val bytes = byteArrayOf(
            0x0A, 0x13,
            0x12, 0x11,
            0x08, 0x01,
            0x12, 0x05, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
            0x1A, 0x06, 0x08, 0x00, 0x10, 0x04, 0x18, 0x0A,
        )

        val lines = SpotifyLyricAnalysis.parseLyrics(bytes)

        assertNotNull(lines)
        assertEquals("hello", lines!![0].text)
        assertNull(lines[0].words)
    }


    private fun varint(value: Int): ByteArray {
        var v = value
        val out = java.io.ByteArrayOutputStream()
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
        return out.toByteArray()
    }

    @Test
    fun `a protobuf string over the 64K limit is rejected`() {
        val huge = ByteArray(64 * 1024 + 1) { 'a'.code.toByte() }
        val line = byteArrayOf(0x12) + varint(huge.size) + huge
        val lyrics = byteArrayOf(0x12) + varint(line.size) + line
        val top = byteArrayOf(0x0A) + varint(lyrics.size) + lyrics

        assertNull(SpotifyLyricAnalysis.parseLyrics(top))
    }

    @Test
    fun `truncated protobuf frames are rejected without throwing`() {
        // field 1, length-delimited, declared 5 bytes but only 1 present
        assertNull(SpotifyLyricAnalysis.parseLyrics(byteArrayOf(0x0A, 0x05, 0x01)))
        // field 1 with invalid wire type 7
        assertNull(SpotifyLyricAnalysis.parseLyrics(byteArrayOf(0x0F)))
        // length varint exceeds the parse budget
        assertNull(
            SpotifyLyricAnalysis.parseLyrics(
                byteArrayOf(0x0A, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),
            ),
        )
    }

    @Test
    fun `empty and non-lyric protobuf bytes do not throw`() {
        assertNull(SpotifyLyricAnalysis.parseLyrics(ByteArray(0)))
        // field 1 是 varint，不是 lyrics 的 length-delimited；应被 skip 后返回 null
        assertNull(SpotifyLyricAnalysis.parseLyrics(byteArrayOf(0x08, 0x01)))
        // 空 lyrics 容器：结构合法但没有行，返回空列表而不是崩溃
        assertTrue(SpotifyLyricAnalysis.parseLyrics(byteArrayOf(0x0A, 0x00)).isNullOrEmpty())
    }

    @Test
    fun `malformed or empty payloads yield no lines instead of throwing`() {
        assertNull(SpotifyLyricAnalysis.parseLyrics("not json at all"))
        assertNull(SpotifyLyricAnalysis.parseLyrics("{}"))
        assertNull(SpotifyLyricAnalysis.parseLyrics("""{"lyrics":{}}"""))
        // 结构合法但没有内容：返回空列表而非 null；调用方 SpotifyApiSource 把
        // lines.isEmpty() 归为 TransientFailure，绝不能据此下发纯音乐占位。
        assertTrue(SpotifyLyricAnalysis.parseLyrics("""{"lyrics":{"lines":[]}}""").isNullOrEmpty())
    }
}
