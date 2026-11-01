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
package com.hchen.superlyric.kernel.parse

import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 解析层的回归用例
 *
 * 覆盖平台载荷的两种错位：波点 LRCX 的译文在文件中跟随主行、时间戳却指向下一条主行；
 * QQ / 小米的 QRC 端点返回 LRC 形态明文。两者都曾在真机上整段丢词或错配译文。
 */
class ParserRegressionTest {

    private fun parse(
        format: LyricFormat,
        text: String,
        durationMs: Long = 0L,
        translation: String? = null,
    ) = LyricPipeline.parse(
        LyricRequest(
            format = format,
            text = text,
            translationText = translation,
            durationMs = durationMs,
        )
    )

    @Test
    fun `lrcx translation follows the preceding main line when its timestamp points at the next`() {
        // 真机载荷形态：译文紧跟它所翻译的主行，时间戳等于下一条主行的起始
        val text = """
            [kuwo:111]
            [00:01.000]<1000,500>English one<2000,500>
            [00:02.000]<0,0>译文一
            [00:02.000]<2000,500>English two<3000,500>
            [00:03.000]<0,0>译文二
            [00:03.000]<3000,500>English three<4000,500>
        """.trimIndent()

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals(3, doc!!.lines.size)
        assertEquals("English one", doc.lines[0].text)
        assertEquals("译文一", doc.lines[0].translation)
        assertEquals("English two", doc.lines[1].text)
        assertEquals("译文二", doc.lines[1].translation)
        assertEquals("English three", doc.lines[2].text)
        assertNull(doc.lines[2].translation)
    }

    @Test
    fun `lrcx trailing translation before the closing blank line belongs to the last main`() {
        // 真机载荷形态：最后一句的译文出现在文件末尾，时间戳等于其后空行的起始。
        // 丢弃会让末句永远没有译文
        val text = """
            [kuwo:026]
            [00:13.537]<304,-304>No <544,64>I <992,96>don't <1344,640>need <2050,638>a <3074,1026>cure <4162,1986>for <6004,2320>me
            [00:16.539]<0,0>我<0,0>不<0,0>需<0,0>要<0,0>治<0,0>愈
            [00:16.539]
        """.trimIndent()

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size)
        assertEquals("No I don't need a cure for me", doc.lines[0].text)
        assertEquals("我不需要治愈", doc.lines[0].translation)
    }

    @Test
    fun `lrcx translation of a mid file blank line is not moved to the previous main`() {
        val text = """
            [kuwo:111]
            [00:01.000]<1000,500>Main one<2000,500>
            [00:02.000]<0,0>空行译文
            [00:02.000]
            [00:03.000]<3000,500>Main two<4000,500>
        """.trimIndent()

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertNull(doc.lines[0].translation)
        assertNull(doc.lines[1].translation)
    }

    @Test
    fun `lrcx translation before its main line still pairs by timestamp`() {
        val text = """
            [kuwo:111]
            [00:01.000]<0,0>译文
            [00:01.000]<1000,500>Main
        """.trimIndent()

        val doc = KuwoLrcxParser.parse(text)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size)
        assertEquals("译文", doc.lines[0].translation)
    }

    @Test
    fun `qrc endpoint returning plain lrc is recovered by the generic parser`() {
        val lrc = "[00:01.380]第一行\n\n[00:06.690]第二行\n\n[00:12.013]第三行"

        val doc = parse(LyricFormat.QRC, lrc)

        assertNotNull(doc)
        assertEquals(3, doc!!.lines.size)
        assertEquals("第一行", doc.lines[0].text)
        assertEquals(1380L, doc.lines[0].startMs)
    }

    @Test
    fun `qrc fallback normalizes three part timestamps and keeps the pure music hint`() {
        val hint = "[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏"

        val doc = parse(LyricFormat.QRC, hint)

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size)
        assertTrue(doc.lines[0].text.contains("纯音乐"))
    }

    @Test
    fun `qrc xml lrc payload keeps the first line and drops the closing tag`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <QrcInfos><LyricInfo>
            <Lyric_1 LyricType="1" LyricContent="[00:01.380]第一行
            [00:06.690]第二行
            [00:12.013]第三行"/>
            </LyricInfo></QrcInfos>
        """.trimIndent()

        val doc = parse(LyricFormat.QRC, xml)

        assertNotNull(doc)
        assertEquals(3, doc!!.lines.size)
        assertEquals("第一行", doc.lines[0].text)
        assertEquals("第三行", doc.lines[2].text)
    }

    @Test
    fun `qrc entity newlines split lines instead of merging them`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos><LyricInfo>
<Lyric_1 LyricType="1" LyricContent="[0,1000]你(0,100)好(100,100)&#10;[2000,1000]再(2000,100)见(2100,100)"/>
</LyricInfo></QrcInfos>"""

        val doc = parse(LyricFormat.QRC, xml)

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("你好", doc.lines[0].text)
        assertEquals("再见", doc.lines[1].text)
    }

    @Test
    fun `qrc translation block with lrc timestamps is attached`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos><LyricInfo>
<Lyric_1 LyricType="1" LyricContent="[0,1000]你(0,100)好(100,100)
[2000,1000]再(2000,100)见(2100,100)"/>
<Lyric_2 LyricType="2" LyricContent="[00:00.00]trans one
[00:02.00]trans two"/>
</LyricInfo></QrcInfos>"""

        val doc = parse(LyricFormat.QRC, xml)

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("trans one", doc.lines[0].translation)
        assertEquals("trans two", doc.lines[1].translation)
    }

    @Test
    fun `translation sidecar with three part timestamps still attaches`() {
        val main = "[00:01.00]one\n[00:02.00]two"
        val sidecar = "[00:01:00]t1\n[00:02:00]t2"

        val doc = parse(LyricFormat.GENERAL, main, translation = sidecar)

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("t1", doc.lines[0].translation)
        assertEquals("t2", doc.lines[1].translation)
    }

    @Test
    fun `lines beyond the track duration are dropped`() {
        val lrc = "[00:01.00]one\n[00:10.00]two\n[99:00.01]promotion"

        val doc = parse(LyricFormat.GENERAL, lrc, durationMs = 30_000L)

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("two", doc.lines[1].text)
    }

    @Test
    fun `word ends are clamped to the line end`() {
        val qrc = "[1000,3]a(1000,600)\n[1003,500]b(1003,100)"

        val parsed = parse(LyricFormat.QRC, qrc)
        assertNotNull(parsed)
        val line = parsed!!.lines[0]
        assertTrue(line.endMs >= line.words.maxOf { it.endMs }, "词尾不得越过行尾")
    }
}
