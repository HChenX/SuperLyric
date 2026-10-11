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
package com.hchen.superlyric.kernel.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 三个自研解析器的金标准测试。
 *
 * 重点覆盖历史上出过缺陷的三类行为：翻译混入主歌词、翻译丢失、逐字时序错误。
 */
class ParserTest {

    // =========================================================================
    // QRC
    // =========================================================================

    @Test
    fun `bare qrc produces words with line relative timing`() {
        val doc = QrcParser.parse("[0,3500](0,500)Hello (500,400)world")

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("Hello world", line.text)
        assertEquals(0L, line.startMs)
        assertEquals(3500L, line.endMs)
        assertEquals(2, line.words.size)
        assertEquals(0L, line.words[0].startMs)
        assertEquals(500L, line.words[0].endMs)
        assertEquals(500L, line.words[1].startMs)
        assertEquals(900L, line.words[1].endMs)
    }

    @Test
    fun `xml wrapped qrc attaches Lyric_2 as translation rather than a main line`() {
        val xml = """
            <QrcInfos><LyricInfo>
            <Lyric_1 LyricType="1" LyricContent="[0,2000](0,1000)Hello (1000,1000)world"/>
            <Lyric_2 LyricType="2" LyricContent="[0,2000]你好 世界"/>
            </LyricInfo></QrcInfos>
        """.trimIndent()

        val doc = QrcParser.parse(xml)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size, "译文绝不能作为独立主行出现")
        assertEquals("Hello world", doc.lines[0].text)
        assertEquals("你好 世界", doc.lines[0].translation)
    }

    @Test
    fun `LyricType driven layout is also recognised`() {
        // 属性顺序被打乱，且译文用 <Lyric_1 LyricType="2"> 而非 <Lyric_2>
        val xml = """
            <QrcInfos>
            <Lyric_1 LyricContent="[0,2000](0,2000)Hello" LyricType="1"/>
            <Lyric_1 LyricContent="[0,2000]你好" LyricType="2"/>
            </QrcInfos>
        """.trimIndent()

        val doc = QrcParser.parse(xml)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size)
        assertEquals("Hello", doc.lines[0].text)
        assertEquals("你好", doc.lines[0].translation)
    }

    @Test
    fun `qrc translation tolerates small timestamp drift`() {
        // 旧实现用精确时间戳匹配，此处特意制造 37ms 漂移
        val xml = """
            <QrcInfos>
            <Lyric_1 LyricType="1" LyricContent="[1000,2000](0,2000)Hello"/>
            <Lyric_2 LyricType="2" LyricContent="[1037,2000]你好"/>
            </QrcInfos>
        """.trimIndent()

        val doc = QrcParser.parse(xml)

        assertNotNull(doc)
        assertEquals("你好", doc!!.lines[0].translation)
    }

    @Test
    fun `xml entities are decoded`() {
        val doc = QrcParser.parse("""[0,1000](0,1000)it&#39;s &amp; that""")

        assertNotNull(doc)
        assertEquals("it's & that", doc!!.lines[0].text)
    }

    @Test
    fun `empty or meaningless input yields null`() {
        assertNull(QrcParser.parse(null))
        assertNull(QrcParser.parse(""))
        assertNull(QrcParser.parse("no time tags here"))
    }

    // =========================================================================
    // LRCX
    // =========================================================================

    /** key 八进制 13 == 十进制 11 → c = 1, d = 1，使算式可手算验证 */
    private val lrcxHeader = "[kuwo:13]"

    @Test
    fun `lrcx word timing follows the vendor formula`() {
        // relStart = (v1+v2)/(c*2) = (1000+500)/2 = 750
        // relEnd   = (v1-v2)/(d*2) + relStart = 250 + 750 = 1000
        val text = "$lrcxHeader\n[00:01.00]<1000,500>Hello <2000,1000>world"

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals(1000L, line.startMs)
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words.size)

        assertEquals(1750L, line.words[0].startMs)
        assertEquals(2000L, line.words[0].endMs)
        assertEquals(2500L, line.words[1].startMs)
        assertEquals(3000L, line.words[1].endMs)
    }

    @Test
    fun `untagged line sharing a timestamp becomes the translation of that line`() {
        val text = "$lrcxHeader\n" +
            "[00:01.00]<1000,500>Hello\n" +
            "[00:01.00]你好"

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size, "成对出现的无标签行是译文，不是主行")
        assertEquals("Hello", doc.lines[0].text)
        assertEquals("你好", doc.lines[0].translation)
    }

    @Test
    fun `untagged line with a unique timestamp stays a main line`() {
        // 这是旧实现最严重的缺陷：它把任何无标签行都当成译文，
        // 导致这条独立主行要么被挂到别的行上，要么被静默丢弃。
        val text = "$lrcxHeader\n" +
            "[00:01.00]<1000,500>Hello\n" +
            "[00:05.00]A line without verbatim data"

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("Hello", doc.lines[0].text)
        assertEquals("A line without verbatim data", doc.lines[1].text)
        assertNull(doc.lines[1].translation)
    }

    @Test
    fun `text without a kuwo header is not treated as lrcx`() {
        assertFalse(KuwoLrcxParser.canParse("[00:01.00]<1000,500>Hello"))
        assertNull(KuwoLrcxParser.parse("[00:01.00]<1000,500>Hello"))
    }

    @Test
    fun `metadata tags are captured`() {
        val text = "$lrcxHeader\n[ti:Song]\n[ar:Artist]\n[al:Album]\n[00:01.00]<1000,500>Hello"

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals("Song", doc!!.title)
        assertEquals("Artist", doc.artist)
        assertEquals("Album", doc.album)
    }

    // =========================================================================
    // Soda JSON
    // =========================================================================

    @Test
    fun `soda json parses sentences and words`() {
        // 真实载荷中拉丁文的词文本自带尾随空格，中文则不含；拼接时不做任何猜测性补空格
        val json = """
            {"sentences":[
              {"startMs":0,"endMs":2000,"words":[
                 {"text":"Hello ","startMs":0,"endMs":1000},
                 {"text":"world","startMs":1000,"endMs":2000}]},
              {"startMs":2000,"endMs":3000,"text":"Second"}
            ]}
        """.trimIndent()

        val doc = SodaJsonParser.parse(json)

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("Hello world", doc.lines[0].text)
        assertEquals(2, doc.lines[0].words.size)
        assertEquals("Second", doc.lines[1].text)
    }

    @Test
    fun `soda json reads translations from translations cn`() {
        val json = """
            {"sentences":[{"startMs":0,"endMs":2000,"text":"Hello"}],
             "translations":{"cn":[{"startMs":0,"text":"你好"}]}}
        """.trimIndent()

        val doc = SodaJsonParser.parse(json)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size)
        assertEquals("Hello", doc.lines[0].text)
        assertEquals("你好", doc.lines[0].translation)
    }

    @Test
    fun `soda json resolves nested containers`() {
        val json = """
            {"loaderData":{"track_page":{"audioWithLyricsOption":{
              "lyrics":{"sentences":[{"startMs":0,"endMs":1000,"text":"Nested"}]}}}}}
        """.trimIndent()

        val doc = SodaJsonParser.parse(json)

        assertNotNull(doc)
        assertEquals("Nested", doc!!.lines[0].text)
    }

    @Test
    fun `soda json handles the first lines of a track without collapsing them`() {
        // 旧实现以 lineStart > 1000 为门控，曲目开头 1 秒内的行会被误判为相对时间
        val json = """
            {"sentences":[{"startMs":200,"endMs":1200,"words":[
              {"text":"早","startMs":200,"endMs":700},
              {"text":"安","startMs":700,"endMs":1200}]}]}
        """.trimIndent()

        val doc = SodaJsonParser.parse(json)

        assertNotNull(doc)
        val words = doc!!.lines[0].words
        assertEquals(2, words.size)
        assertEquals(200L, words[0].startMs)
        assertEquals(700L, words[1].startMs)
        assertTrue(words[0].startMs < words[1].startMs)
    }

    @Test
    fun `non json input is rejected`() {
        assertFalse(SodaJsonParser.canParse("[00:01.00]Hello"))
        assertNull(SodaJsonParser.parse("[00:01.00]Hello"))
    }
}
