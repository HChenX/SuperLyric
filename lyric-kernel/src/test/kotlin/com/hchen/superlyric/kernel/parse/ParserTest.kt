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

import com.hchen.superlyric.kernel.pipeline.CompanionSplitter
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
    fun `bare qrc keeps the first word and maps each word to its own timing`() {
        // 真机报文形态：[行起始,行时长]文字(词起始,词时长)文字(词起始,词时长)…
        // 文字在时间标签**之前**。旧实现按"标签在前"捕获，导致每行首个词被整段丢弃。
        val doc = QrcParser.parse("[0,3500]Hello(0,500) world(500,400)")

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("Hello world", line.text)
        assertTrue(line.text.startsWith("Hello"), "首个词绝不能丢")
        assertEquals(0L, line.startMs)
        assertEquals(3500L, line.endMs)
        assertEquals(2, line.words.size)
        assertEquals("Hello", line.words[0].text)
        assertEquals(0L, line.words[0].startMs)
        assertEquals(500L, line.words[0].endMs)
        assertEquals(500L, line.words[1].startMs)
        assertEquals(900L, line.words[1].endMs)
    }

    @Test
    fun `qrc word text stays aligned with its own tag rather than the next one`() {
        // 真机样本："后会无期" 曾被解析成 "会无期"
        val doc = QrcParser.parse("[0,4000]后(0,1000)会(1000,1000)无(2000,1000)期(3000,1000)")

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("后会无期", line.text)
        assertEquals(listOf("后", "会", "无", "期"), line.words.map { it.text })
        assertEquals(0L, line.words[0].startMs)
        assertEquals(3000L, line.words[3].startMs)
    }

    @Test
    fun `absolute word timing layout is detected and not offset by the line start`() {
        // 真机 QQ 报文：词起始是**歌内绝对毫秒**。
        // 早期实现按"行内相对偏移"解释，会把行起始加两次（20699 + 20699），
        // 再被 normalize 把词首压回行首，逐字高光实际全程失效。
        val doc = QrcParser.parse("[20699,2312]初(20699,434)め(21133,169)て(21302,84)")

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("初めて", line.text)
        assertEquals(20_699L, line.startMs)
        assertEquals(listOf(20_699L, 21_133L, 21_302L), line.words.map { it.startMs })
        assertEquals(20_699L + 434L, line.words[0].endMs)
        assertEquals(21_133L + 169L, line.words[1].endMs)
    }

    @Test
    fun `relative word timing layout is detected and offset by the line start`() {
        // 另一种真实排布：词起始是行内相对偏移
        val doc = QrcParser.parse("[1000,2000]你(0,500)好(500,500)")

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals(listOf(1_000L, 1_500L), line.words.map { it.startMs })
        assertEquals(1_500L, line.words[0].endMs)
    }

    @Test
    fun `xml wrapped qrc attaches Lyric_2 as translation rather than a main line`() {
        val xml = """
            <QrcInfos><LyricInfo>
            <Lyric_1 LyricType="1" LyricContent="[0,2000]Hello(0,1000) world(1000,1000)"/>
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
            <Lyric_1 LyricContent="[0,2000]Hello(0,2000)" LyricType="1"/>
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
            <Lyric_1 LyricType="1" LyricContent="[1000,2000]Hello(0,2000)"/>
            <Lyric_2 LyricType="2" LyricContent="[1037,2000]你好"/>
            </QrcInfos>
        """.trimIndent()

        val doc = QrcParser.parse(xml)

        assertNotNull(doc)
        assertEquals("你好", doc!!.lines[0].translation)
    }

    @Test
    fun `xml entities are decoded`() {
        val doc = QrcParser.parse("""[0,1000]it&#39;s &amp; that(0,1000)""")

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
    fun `a zero-tagged translation line is still recognised as a translation`() {
        // 真机报文（波点）：译文行**也带标签，但值全是 `<0,0>`**——那等于没有逐字数据。
        // 若按"有标签即主行"处理，译文行会被当成普通歌词行，译文永远配不上
        // （实测整首歌 196 行、0 条译文，且译文全部作为独立行下发）。
        val text = "$lrcxHeader\n[00:01.00]<0,0>你好 世界\n[00:01.00]<1000,500>Hello <2000,1000>world"

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size, "零标签的译文行绝不能成为独立主行")
        assertEquals("Hello world", doc.lines[0].text)
        assertEquals("你好 世界", doc.lines[0].translation)
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
    fun `a translation whose main line is empty is discarded instead of promoted`() {
        // 真机报文（波点《Dream It Possible》）的末尾形状：译文行之后的主行**只有时间戳、
        // 内容为空**，被解析器丢弃。丢弃时若不留下时间戳痕迹，这条译文就找不到主行，
        // 会被当成"无逐字数据的独立主行"提升 —— 实测整首歌的最后一行变成了一句译文
        val text = "$lrcxHeader\n" +
            "[00:01.00]<0,0>梦想成真\n" +
            "[00:01.00]<1000,500>And we'll dream it possible\n" +
            "[00:05.00]<0,0>我们期盼梦想成真\n" +
            "[00:05.00]"

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size, "失去主行的译文不得成为独立主行")
        assertEquals("And we'll dream it possible", doc.lines[0].text)
        assertEquals("梦想成真", doc.lines[0].translation)
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

    @Test
    fun `an oversized sentence array is capped instead of allocated whole`() {
        // 载荷由宿主给出，规模不受本层控制：预分配跟着 sentences.size() 走的话，
        // 一份构造出来的巨型 JSON 能在解析出任何一行之前就把堆吃掉
        val cap = 4096
        val builder = StringBuilder("""{"sentences":[""")
        for (i in 0 until cap + 500) {
            if (i > 0) builder.append(',')
            builder.append("""{"startMs":""").append(i * 1_000L)
                .append(""","endMs":""").append(i * 1_000L + 900L)
                .append(""","text":"line""").append(i).append("\"}")
        }
        builder.append("]}")

        val doc = SodaJsonParser.parse(builder.toString())

        assertNotNull(doc)
        assertEquals(cap, doc!!.lines.size, "超过上限的句必须被截断，上限值改动时同步改本断言")
    }

    // =========================================================================
    // CompanionSplitter（同时间戳伴随行的拆分）
    // =========================================================================

    @Test
    fun `a companion row written before its main row is still split out`() {
        // 伴随行写在主行**之前**的排布。判据必须与遍历顺序无关：若以"遍历到该行时
        // 是否已经见过它的主行"为准，这种排布下所有伴随行都找不到主人，于是没有任何行
        // 被拆出去，split() 直接返回 null —— 整份载荷退回通用解析库，
        // 而库正是会把译文留成独立歌词行的那个（见 CompanionSplitter 的类注释）。
        val text = "[00:20.699]第一次参观卢浮宫\n" +
            "[00:20.699]<00:20.699>初<00:21.133>め<00:22.298>は <00:23.011>"

        val split = CompanionSplitter.split(text)

        assertNotNull(split, "伴随行在前时仍必须识别出这一形态")
        assertFalse(split!!.main.contains("第一次参观卢浮宫"), "译文不得留在主载荷里")
        assertTrue(
            split.translation?.contains("第一次参观卢浮宫") == true,
            "译文必须**带着时间戳**进入伴随通道，TranslationMerger 靠它对齐",
        )
    }
}
