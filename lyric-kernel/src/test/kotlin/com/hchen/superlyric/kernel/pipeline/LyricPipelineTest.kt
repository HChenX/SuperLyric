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

import com.google.gson.Gson
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
import java.util.Base64
import java.util.zip.Deflater

/**
 * 管线端到端测试：从原始载荷到规范模型的完整链路
 *
 * 覆盖三个返回值语义的分界：
 * 成功返回文档、无有效行返回 null、解码失败抛 [DecodeException]。
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
    fun `plaintext krc from the text channel keeps per-word timing`() {
        // 汽水音乐交给歌词模型的正文就是这个形态：行头是「起始,时长」毫秒对，
        // 词标签是「行内偏移,时长,0」三元组。它走 text 通道，没有 krc1 头也不加密
        val plain = "[1000,2000]<0,500,0>Hello <500,400,0>world\n[3000,1500]<0,800,0>Second"

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.KRC, text = plain))

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("Hello world", doc.lines[0].text)
        assertEquals(1000L, doc.lines[0].startMs)
        assertEquals(2, doc.lines[0].words.size)
        // 词偏移是相对行首的，不是绝对毫秒
        assertEquals(1000L, doc.lines[0].words[0].startMs)
        assertEquals(1500L, doc.lines[0].words[1].startMs)
        assertEquals("Second", doc.lines[1].text)
    }

    @Test
    fun `trailing syllable timestamps keep every line first character`() {
        // 椒盐的 sourceText 形态：行首时间戳之后，每个文字片段后面跟一个时间标签。
        // 通用解析库会把每个标签当成新行，导致每行首个字符被前一个标签吞掉。
        val payload = """
            [ti:温柔 #MaydayBlue20th]
            [00:00.000]温[00:00.033]柔[00:00.066] [00:00.099]Mayday[00:00.165]
            [00:29.280]孤[00:29.665]单[00:30.040]的[00:30.433]今[00:30.737]后[00:31.457]
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.AUTO, text = payload))

        assertNotNull(doc)
        assertEquals(listOf("温柔 Mayday", "孤单的今后"), doc!!.lines.map { it.text })
        val second = doc.lines[1]
        assertEquals(29_280L, second.startMs)
        assertEquals(31_457L, second.endMs)
        assertEquals(5, second.words.size)
        assertEquals(listOf(29_280L, 29_665L, 30_040L, 30_433L, 30_737L), second.words.map { it.startMs })
        assertEquals(listOf(29_665L, 30_040L, 30_433L, 30_737L, 31_457L), second.words.map { it.endMs })
        assertEquals("孤单的今后", second.words.joinToString("") { it.text })
    }

    @Test
    fun `trailing timestamp parser ignores plain and multi timestamp lrc`() {
        assertNull(TrailingTimestampLrcParser.parse("[00:01.00]Hello"))
        // 行首后直接跟第二个标签是同一行的多个时间戳，不是逐字格式
        assertNull(TrailingTimestampLrcParser.parse("[00:01.00][00:05.00]Hello"))
    }

    @Test
    fun `krc language block supplies translation and phonetic by line index`() {
        val tag = krcLanguageTag(
            translation = listOf("你好", "再见"),
            phonetic = listOf("konnichiwa", "sayounara"),
        )
        val plain = """
            [ti:Test]
            $tag
            [1000,1000]<0,100,0>こんにちは
            [3000,1000]<0,100,0>さようなら
        """.trimIndent()

        val doc = LyricPipeline.parse(
            LyricRequest(format = LyricFormat.KRC, text = plain, durationMs = 5000)
        )

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertEquals("你好", doc.lines[0].translation)
        assertEquals("konnichiwa", doc.lines[0].phonetic)
        assertEquals("再见", doc.lines[1].translation)
        assertEquals("sayounara", doc.lines[1].phonetic)
        assertFalse(doc.lines.any { it.text.contains("[language:") })
    }

    @Test
    fun `krc language channels are selected by type not array order`() {
        val tag = krcLanguageTag(
            translation = listOf("你好", "再见"),
            phonetic = listOf("konnichiwa", "sayounara"),
            phoneticFirst = true,
        )
        val plain = """
            $tag
            [1000,1000]<0,100,0>こんにちは
            [3000,1000]<0,100,0>さようなら
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.KRC, text = plain))

        assertNotNull(doc)
        assertEquals("你好", doc!!.lines[0].translation)
        assertEquals("konnichiwa", doc.lines[0].phonetic)
    }

    @Test
    fun `malformed krc language block leaves the main lyric readable`() {
        val plain = """
            [language:!!!not-base64!!!]
            [1000,1000]<0,100,0>こんにちは
            [3000,1000]<0,100,0>さようなら
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.KRC, text = plain))

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertNull(doc.lines[0].translation)
        assertNull(doc.lines[0].phonetic)
    }

    @Test
    fun `krc language block with non-array content leaves the main lyric readable`() {
        // {"content":1} 是合法 JSON 但结构错误；不能让整份 KRC 退化为无歌词
        val plain = """
            [language:eyJjb250ZW50IjoxfQ==]
            [1000,1000]<0,100,0>こんにちは
            [3000,1000]<0,100,0>さようなら
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.KRC, text = plain))

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertNull(doc.lines[0].translation)
    }

    @Test
    fun `mismatched krc language array length is discarded`() {
        val tag = krcLanguageTag(
            translation = listOf("只有一行"),
            phonetic = listOf("only one"),
        )
        val plain = """
            $tag
            [1000,1000]<0,100,0>こんにちは
            [3000,1000]<0,100,0>さようなら
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.KRC, text = plain))

        assertNotNull(doc)
        assertEquals(2, doc!!.lines.size)
        assertNull(doc.lines[0].translation)
        assertNull(doc.lines[1].translation)
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
        // QRC 与 YRC 的词标签形态接近，分量个数是唯一可靠的区分点
        // （QRC 二元组、YRC 三元组）。误判会让每个词的时间多叠加一次行起始，
        // 逐字高光整体漂移。
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
        // QRC 形态：文字在时间标签之前
        val qrc = "[1000,2000]你(0,500)好(500,500)"

        val doc = LyricPipeline.parse(LyricRequest(text = qrc))

        assertNotNull(doc)
        val words = doc!!.lines[0].words
        assertEquals(1_000L, words[0].startMs, "QRC 词起始按行内相对偏移换算")
        assertEquals(1_500L, words[1].startMs)
    }

    @Test
    fun `absurd end time is bounded so the last line cannot span until 2038`() {
        // 上游库对"结束时间未知"的行会填 Int.MAX_VALUE 一类的哨兵值，末行尤其常见
        // （后面没有下一行兜住）。例如网易云 LRC 的 span 可能为 [384..2147483647]。
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
        // 椒盐报文形态：同一时间戳连续三行，依次为主歌词、罗马音、翻译。
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
     * 椒盐报文形态：主行末尾的词标签把行的结束时间撑到下一行起始之后，
     * 主行之间因此互相重叠。通用解析库按"区间重叠"配对主行与伴随行，
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
        // 椒盐翻唱版报文把空格本身也作为一个切片：
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
    fun `trailing tags on the main line keep its same timestamp translation`() {
        // 椒盐内嵌日文歌词：主行用方括号逐字标签，译文行只有行首标签且与主行同始。
        // 伴随行识别只认尖括号标签时，译文会被当成普通行，主行标签也被漏掉。
        val payload = """
            [00:55.823]弄[00:56.415]ら[00:56.720]れ[00:57.023]て[00:57.183]垂[00:57.320]れ[00:57.463]流[00:57.728]す[00:58.192] [00:58.192]音[00:58.552]の[00:58.797]な[00:59.117]い[00:59.469]音[00:59.710]
            [00:55.823]在命运玩弄下发出无声之音
            [00:59.710]遍[01:00.150]く[01:00.590] [01:00.590]名[01:00.758]前[01:00.968]を[01:01.112]捨[01:01.264]て[01:01.408]た[01:01.568]の[01:01.816]ね[01:01.906]
            [00:59.710]将名字尽数舍弃
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(text = payload))

        assertNotNull(doc)
        val lines = doc!!.lines
        assertEquals(listOf("弄られて垂れ流す 音のない音", "遍く 名前を捨てたのね"), lines.map { it.text })
        assertEquals("在命运玩弄下发出无声之音", lines[0].translation)
        assertEquals("将名字尽数舍弃", lines[1].translation)
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

    @Test
    fun `general lrc offset shifts line and word times exactly once`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = "[offset:+250]\n[00:01.00]<00:01.00>Hello <00:02.00>world",
            )
        )

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals(1250L, line.startMs)
        assertEquals("Hello world", line.text)
    }

    @Test
    fun `general lrc offset keeps ti and ar metadata`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = "[ti:Song]\n[ar:Artist]\n[offset:+250]\n[00:01.00]alpha",
            )
        )

        assertNotNull(doc)
        assertEquals(1250L, doc!!.lines.single().startMs)
        assertEquals("Song", doc.title)
        assertEquals("Artist", doc.artist)
    }

    @Test
    fun `negative general lrc offset clamps to zero`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = "[offset:-500]\n[00:00.20]alpha",
            )
        )

        assertNotNull(doc)
        assertEquals(0L, doc!!.lines.single().startMs)
        assertEquals("alpha", doc.lines.single().text)
    }

    @Test
    fun `auto format applies the general lrc offset`() {
        val doc = LyricPipeline.parse(
            LyricRequest(text = "[offset:+250]\n[00:01.00]alpha")
        )

        assertNotNull(doc)
        assertEquals(1250L, doc!!.lines.single().startMs)
    }

    @Test
    fun `general lrc without offset keeps its timestamps`() {
        val doc = LyricPipeline.parse(
            LyricRequest(format = LyricFormat.GENERAL, text = "[00:01.00]alpha")
        )

        assertNotNull(doc)
        assertEquals(1000L, doc!!.lines.single().startMs)
    }

    @Test
    fun `qrc offset is still applied only once by the pipeline`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.QRC,
                text = "[offset:500]\n[1000,2000]Hello(1000,500)world(1500,500)",
            )
        )

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals(1500L, line.startMs)
        assertEquals(listOf(1500L, 2000L), line.words.map { it.startMs })
    }

    @Test
    fun `standalone speaker labels merge into the following line`() {
        val payload = listOf(
            "[00:01.00]男：",
            "[00:01.20]Hello",
            "[00:03.00]女：",
            "[00:03.20]World",
        ).joinToString("\n")

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.GENERAL, text = payload))

        assertNotNull(doc)
        assertEquals(listOf("男：Hello", "女：World"), doc!!.lines.map { it.text })
    }

    @Test
    fun `negative offset restores positive non-overlapping words`() {
        val payload = """
            [offset:-500]
            [00:00.20] <00:00.20>Hello<00:00.40> <00:00.60>world
            [00:00.20]你好
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.GENERAL, text = payload))

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals(0L, line.startMs)
        assertEquals("你好", line.translation)
        assertTrue(line.words.isNotEmpty())
        var previousEnd = line.startMs
        line.words.forEach { word ->
            assertTrue(word.startMs >= line.startMs, "词不得早于行: " + word)
            assertTrue(word.endMs <= line.endMs, "词不得晚于行: " + word)
            assertTrue(word.endMs > word.startMs, "词必须有正时长: " + word)
            assertTrue(word.startMs >= previousEnd, "词不得重叠: " + word)
            previousEnd = word.endMs
        }
    }

    @Test
    fun `negative offset re-collapses simultaneous lines`() {
        val payload = "[offset:-500]\n[00:00.20]First\n[00:00.30]Second"

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.GENERAL, text = payload))

        assertNotNull(doc)
        assertEquals(1, doc!!.lines.size)
        assertEquals("First", doc.lines.single().text)
        assertEquals("Second", doc.lines.single().translation)
    }

    @Test
    fun `positive offset drops lines outside the duration window`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = "[offset:+130000]\n[00:01.00]alpha",
                durationMs = 5_000L,
            )
        )

        assertNull(doc)
    }

    @Test
    fun `main offset keeps sidecar translation and phonetic aligned`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = "[offset:+250]\n[00:01.00]alpha",
                translationText = "[offset:+250]\n[00:01.00]译文",
                phoneticText = "[offset:+250]\n[00:01.00]a-lpha",
            )
        )

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals(1250L, line.startMs)
        assertEquals("译文", line.translation)
        assertEquals("a-lpha", line.phonetic)
    }

    @Test
    fun `qrc fallback lrc offset is applied once`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.QRC,
                text = "[offset:+250]\n[00:01.00]alpha",
            )
        )

        assertNotNull(doc)
        assertEquals(1250L, doc!!.lines.single().startMs)
    }

    @Test
    fun `line text is whitespace-normalised at the pipeline boundary`() {
        val doc = LyricPipeline.parse(
            LyricRequest(format = LyricFormat.GENERAL, text = "[00:01.00]hello   world")
        )

        assertNotNull(doc)
        assertEquals("hello world", doc!!.lines.single().text)
    }

    @Test
    fun `plain lrc without a fraction is parsed after canonicalisation`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = """
                    [ti:Song]
                    [00:01]Hello
                    [00:05]World
                """.trimIndent(),
            )
        )

        assertNotNull(doc)
        assertEquals(listOf("Hello", "World"), doc!!.lines.map { it.text })
        assertEquals(1_000L, doc.lines[0].startMs)
        assertEquals(5_000L, doc.lines[1].startMs)
    }

    @Test
    fun `three part lrc timestamps are parsed after canonicalisation`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = """
                    [00:01:02]Hello
                    [00:05:10]World
                """.trimIndent(),
            )
        )

        assertNotNull(doc)
        assertEquals(listOf("Hello", "World"), doc!!.lines.map { it.text })
        assertEquals(1_020L, doc.lines[0].startMs)
        assertEquals(5_100L, doc.lines[1].startMs)
    }

    @Test
    fun `cjk lyricify syllable is detected and parsed`() {
        val doc = LyricPipeline.parse(
            LyricRequest(format = LyricFormat.AUTO, text = "[0]你(0,100)好(100,100)")
        )

        assertNotNull(doc)
        val line = doc!!.lines.single()
        assertEquals("你好", line.text)
        assertEquals(2, line.words.size)
        assertEquals("你", line.words[0].text)
        assertEquals("好", line.words[1].text)

        val latin = LyricPipeline.parse(
            LyricRequest(format = LyricFormat.AUTO, text = "[0]Hel(0,100)lo(100,100)")
        )
        assertNotNull(latin)
        assertEquals("Hello", latin!!.lines.single().text)
    }

    @Test
    fun `ttml without a namespace is parsed`() {
        val ttml = """
            <tt><body><div>
            <p begin="00:00:01.000" end="00:00:02.000">
            <span begin="00:00:01.000" end="00:00:02.000">Hello</span>
            </p></div></body></tt>
        """.trimIndent()

        val doc = LyricPipeline.parse(LyricRequest(format = LyricFormat.AUTO, text = ttml))

        assertNotNull(doc)
        assertEquals("Hello", doc!!.lines.single().text)
        assertEquals(1_000L, doc.lines.single().startMs)
    }

    @Test
    fun `parse trace reports the accepted parser`() {
        val traces = ArrayList<LyricParseTrace>()
        val doc = LyricPipeline.parseDetailed(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = """
                    [00:01.00]Hello
                    [00:05.00]World
                """.trimIndent(),
            ),
            null,
        ) { traces += it }

        assertNotNull(doc)
        assertEquals("library", traces.last { it.accepted }.parserId)
        assertEquals(2, traces.last { it.accepted }.lineCount)
    }

    private fun krcLanguageTag(
        translation: List<String>,
        phonetic: List<String>,
        phoneticFirst: Boolean = false,
    ): String {
        val translationEntry = mapOf(
            "type" to 1,
            "language" to 0,
            "lyricContent" to translation.map { listOf(it) },
        )
        val phoneticEntry = mapOf(
            "type" to 0,
            "language" to 0,
            "lyricContent" to phonetic.map { listOf(it) },
        )
        val content = if (phoneticFirst) {
            listOf(phoneticEntry, translationEntry)
        } else {
            listOf(translationEntry, phoneticEntry)
        }
        val json = Gson().toJson(mapOf("content" to content, "version" to 1))
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        return "[language:$encoded]"
    }
}
