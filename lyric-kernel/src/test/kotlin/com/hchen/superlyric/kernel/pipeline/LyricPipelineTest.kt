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
package com.hchen.superlyric.kernel.pipeline

import com.hchen.superlyric.kernel.decode.DecodeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

/**
 * 管线端到端测试：从原始载荷到规范模型的完整链路。
 *
 * 覆盖三个返回值语义的分界：
 * 成功返回文档、确定性无歌词返回 null、解码失败抛 [DecodeException]。
 */
class LyricPipelineTest {

    private val krcKey = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    private fun encodeKrc(plainText: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(plainText.toByteArray(StandardCharsets.UTF_8))
        deflater.finish()
        val compressed = ByteArrayOutputStream().apply {
            val buffer = ByteArray(1024)
            while (!deflater.finished()) write(buffer, 0, deflater.deflate(buffer))
        }.toByteArray()
        deflater.end()

        return ByteArrayOutputStream().apply {
            write("krc1".toByteArray(StandardCharsets.US_ASCII))
            compressed.forEachIndexed { index, byte ->
                write((byte.toInt() xor krcKey[index % krcKey.size].toInt()) and 0xff)
            }
        }.toByteArray()
    }

    @Test
    fun `krc payload flows through decode and the library parser`() {
        val plain = "[0,3500]<0,500,0>Hello <500,400,0>world\n[3500,2000]<0,800,0>Second"
        val request = LyricRequest(
            format = LyricFormat.KRC,
            bytes = encodeKrc(plain),
            title = "Song",
            artist = "Artist",
        )

        val doc = LyricPipeline.parse(request)

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("Hello world", doc.lines[0].text)
        assertEquals(2, doc.lines[0].words.size)
        assertEquals("Second", doc.lines[1].text)
        assertEquals("Song", doc.title)
        assertEquals("Artist", doc.artist)
    }

    @Test
    fun `qrc payload carries its embedded translation through`() {
        val xml = """
            <QrcInfos>
            <Lyric_1 LyricType="1" LyricContent="[0,2000]Hello(0,2000)"/>
            <Lyric_2 LyricType="2" LyricContent="[0,2000]你好"/>
            </QrcInfos>
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.QRC, text = xml))

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size)
        assertEquals("你好", doc.lines[0].translation)
    }

    @Test
    fun `lrc shaped sidecar is attached as translation`() {
        val request = LyricRequest(
            format = LyricFormat.QRC,
            text = "[0,2000]Hello(0,2000)",
            translationText = "[00:00.00]你好",
        )

        val doc = LyricPipeline.parse(request)

        assertNotNull(doc)
        assertEquals("你好", doc!!.lines[0].translation)
    }

    @Test
    fun `sidecar is routed to the phonetic channel when requested`() {
        val request = LyricRequest(
            format = LyricFormat.QRC,
            text = "[0,2000]Hello(0,2000)",
            phoneticText = "[00:00.00]he-lou",
        )

        val doc = LyricPipeline.parse(request)

        assertNotNull(doc)
        assertEquals("he-lou", doc!!.lines[0].phonetic)
        assertNull(doc.lines[0].translation)
    }

    @Test
    fun `auto format sniffs each structural shape`() {
        val qrc = LyricPipeline.parse(LyricRequest(text = "[0,2000]Hello(0,2000)"))
        assertNotNull(qrc)
        assertEquals("Hello", qrc!!.lines[0].text)

        val lrcx = LyricPipeline.parse(
            LyricRequest(text = "[kuwo:13]\n[00:01.00]<1000,500>Hello")
        )
        assertNotNull(lrcx)
        assertEquals("Hello", lrcx!!.lines[0].text)

        val soda = LyricPipeline.parse(
            LyricRequest(text = """{"sentences":[{"startMs":0,"endMs":1000,"text":"Soda"}]}""")
        )
        assertNotNull(soda)
        assertEquals("Soda", soda!!.lines[0].text)
    }

    @Test
    fun `metadata from the request backfills a payload that lacks it`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.QRC,
                text = "[0,1000]Hello(0,1000)",
                title = "T",
                album = "A",
                durationMs = 12345L,
                lyricId = "qq:abc",
            )
        )

        assertNotNull(doc)
        assertEquals("T", doc!!.title)
        assertEquals("A", doc.album)
        assertEquals(12345L, doc.durationMs)
        assertEquals("qq:abc", doc.lyricId)
    }

    @Test
    fun `normalisation guarantees monotonic timing`() {
        val doc = LyricPipeline.parse(
            LyricRequest(format = LyricFormat.QRC, text = "[5000,1000](0,1000)A\n[1000,1000](0,1000)B")
        )

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertTrue(doc.lines[0].startMs <= doc.lines[1].startMs, "行必须按起始时间升序")
        doc.lines.forEach { line ->
            assertTrue(line.endMs >= line.startMs, "行结束时间不得早于起始时间")
            line.words.forEach { word ->
                assertTrue(word.endMs >= word.startMs, "词结束时间不得早于起始时间")
                assertTrue(word.startMs >= line.startMs, "词不得早于所属行")
            }
        }
    }

    @Test
    fun `empty and meaningless payloads yield null rather than an exception`() {
        assertNull(LyricPipeline.parse(LyricRequest(format = LyricFormat.QRC, text = "")))
        assertNull(LyricPipeline.parse(LyricRequest(format = LyricFormat.QRC, text = "   ")))
        assertNull(LyricPipeline.parse(LyricRequest(format = LyricFormat.SODA_JSON, text = "{}")))
        assertNull(LyricPipeline.parse(LyricRequest(format = LyricFormat.LRCX, text = "no header")))
    }

    @Test
    fun `undecodable payload raises decode exception so callers never treat it as no lyrics`() {
        // 这是纯音乐误判的结构性防线：解码失败必须是异常，不能是 null
        val garbage = ByteArray(64) { 0x5a }

        assertThrows(DecodeException::class.java) {
            LyricPipeline.parse(LyricRequest(format = LyricFormat.QRC, bytes = garbage))
        }
    }

    @Test
    fun `netease yrc is not mistaken for qrc`() {
        // QRC 的词起始是行内相对偏移，YRC 的是歌曲绝对毫秒。两者词标签形态几乎一样，
        // 只有分量个数不同（QRC 二元组、YRC 三元组）。误判会让每个词的时间
        // 被多加一次行起始时间，逐字高光整体漂移。
        val yrc = "[1000,2000](1000,500,0)你(1500,500,0)好"

        val doc = LyricPipeline.parse(LyricRequest(text = yrc))

        assertNotNull(doc)
        val words = doc!!.lines[0].words
        assertTrue(words.isNotEmpty(), "YRC 应当解析出逐字切片")
        assertEquals(
            1_000L,
            words[0].startMs,
            "YRC 词时间戳是绝对值；若被当作 QRC，这里会变成 1000 + 1000 = 2000",
        )
    }

    @Test
    fun `two component word tags are still treated as qrc`() {
        // 真机 QRC 形态：文字在时间标签之前
        val qrc = "[1000,2000]你(0,500)好(500,500)"

        val doc = LyricPipeline.parse(LyricRequest(text = qrc))

        assertNotNull(doc)
        val words = doc!!.lines[0].words
        assertEquals(1_000L, words[0].startMs, "QRC 词起始按行内相对偏移换算")
        assertEquals(1_500L, words[1].startMs)
    }

    @Test
    fun `absurd end time is bounded so the last line cannot span until 2038`() {
        // 上游库对"结束时间未知"的行会填 Int.MAX_VALUE 之类的哨兵值，末行尤其常见
        // （它后面没有下一行可以兜住）。实测网易云的 LRC 就出现过 span=[384..2147483647]。
        val doc = LyricPipeline.parse(
            LyricRequest(text = "[00:00.00]First\n[00:10.00]Last", durationMs = 20_000L)
        )

        assertNotNull(doc)
        doc!!.lines.forEach { line ->
            assertTrue(
                line.endMs - line.startMs <= 300_000L,
                "行区间不得荒谬：\"${line.text}\" [${line.startMs}..${line.endMs}]",
            )
        }
    }

    @Test
    fun `simultaneous romaji and translation never become independent lyric lines`() {
        // 真机报文形态（椒盐）：同一时间戳连续三行——主歌词 / 罗马音 / 翻译。
        // 解析库只能为一条主行接纳一条伴随行，于是罗马音被当成译文、
        // 真正的译文留在列表里成了独立歌词行，界面就会把翻译当歌词显示。
        val payload = """
            [00:20.699]<00:20.699>初<00:21.133>め
            [00:20.699]ha ji me
            [00:20.699]第一次参观
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(text = payload))

        assertNotNull(doc)
        val lines = doc!!.lines
        assertEquals(1, lines.size, "同时刻的伴随行绝不能成为独立歌词行：${lines.map { it.text }}")
        assertEquals("初め", lines[0].text)
        assertEquals("第一次参观", lines[0].translation)
        assertEquals("ha ji me", lines[0].phonetic)
    }

    @Test
    fun `chinese companion of a latin main line is a translation not a romanization`() {
        // 主行无可罗马化的字符时，同伴只可能是译文
        val payload = """
            [00:18.251]<00:18.251>We<00:18.690> don't
            [00:18.251]我们不再
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(text = payload))

        assertNotNull(doc)
        val lines = doc!!.lines
        assertEquals(1, lines.size)
        assertEquals("我们不再", lines[0].translation)
        assertNull(lines[0].phonetic)
    }

    /**
     * 真机报文形态（椒盐）：主行末尾的词标签把行的结束时间撑到**下一行起始之后**，
     * 于是主行之间互相重叠。通用解析库按"区间重叠"配对主行与伴随行，
     * 会把相邻主行误配成「主行 + 译文」，真正的伴随行反而落单成独立歌词行。
     */
    private val overlappingSaltPayload = """
        [00:00.100] <00:00.100>We Don't Talk Anymore - Charlie Puth <00:00.200>
        [00:00.150] <00:00.150>Written by：Jacob Kasher <00:00.300>
        [00:00.860] <00:00.860>Charlie Puth： <00:00.960>
        [00:00.867] <00:00.867>We <00:01.010>don't <00:01.249>talk <00:05.000>
        [00:00.867]只剩沉默 我们之间只剩沉默
        [00:05.523] <00:05.523>We <00:05.666>don't <00:06.281>talk <00:09.354>
        [00:05.523]我们之间只有沉默
    """.trimIndent()

    @Test
    fun `companion splitter strips companions and repairs overlap`() {
        val split = CompanionSplitter.split(overlappingSaltPayload)

        assertNotNull(split)
        assertEquals(
            listOf("[00:00.867]只剩沉默 我们之间只剩沉默", "[00:05.523]我们之间只有沉默"),
            split!!.translation?.lines(),
        )
        assertNull(split.phonetic)

        // 伴随行被移走
        assertFalse(split.main.contains("只剩沉默"))
        // 尾部词标签被钳到下一主行起始之前，让两条主行各自独立
        assertFalse(split.main.contains("<00:00.200>"), "重叠必须被修掉：\n${split.main}")
        assertFalse(split.main.contains("<00:00.960>"), "重叠必须被修掉：\n${split.main}")
        // 三条主行都还在
        assertTrue(split.main.contains("Written by：Jacob Kasher"))
        assertTrue(split.main.contains("Charlie Puth："))
    }

    @Test
    fun `companion lines never end up as independent lyric lines`() {
        val doc = LyricPipeline.parse(LyricRequest(text = overlappingSaltPayload))

        assertNotNull(doc)
        val lines = doc!!.lines
        assertEquals(
            listOf(
                "We Don't Talk Anymore - Charlie Puth",
                "Written by：Jacob Kasher",
                "Charlie Puth：",
                "We don't talk",
                "We don't talk",
            ),
            lines.map { it.text },
            "伴随行不得成为独立歌词行，相邻主行也不得被相互吞并",
        )
        assertNull(lines[0].translation, "头行不得被错配上相邻主行作为译文")
        assertNull(lines[1].translation)
        assertEquals("只剩沉默 我们之间只剩沉默", lines[3].translation)
        assertEquals("我们之间只有沉默", lines[4].translation)
    }

    @Test
    fun `space-only slices are preserved so latin words stay separated`() {
        // 真机报文（椒盐 · 翻唱版）把**空格本身**也作为一个切片：
        // <00:00.106> <00:00.212>。若把它当空切片丢掉，会拼成 Wedon'ttalk。
        val payload = """
            [00:00.000] <00:00.000>We<00:00.106> <00:00.212>Don't<00:00.318> <00:00.424>Talk<00:01.590>
            [00:00.000]不想说话
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(text = payload))

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("We Don't Talk", line.text)
        assertEquals("不想说话", line.translation)
    }

    @Test
    fun `blank and placeholder sidecars are dropped`() {
        // 波点会下发只有空格的伴随行；TME（QQ）用 "//" 表示"此行无译文"。
        // 两者都不该作为译文继续下发。
        val payload = listOf(
            "[00:00.100]Line one",
            "[00:00.100] ",
            "[00:05.000]Line two",
            "[00:05.000]//",
        ).joinToString("\n")

        val doc = LyricPipeline.parse(LyricRequest(text = payload))

        assertNotNull(doc)
        val lines = doc!!.lines
        assertEquals(listOf("Line one", "Line two"), lines.map { it.text })
        assertNull(lines[0].translation, "空白译文不得继续下发")
        assertNull(lines[1].translation, "占位符 // 不得作为译文继续下发")
    }
}
