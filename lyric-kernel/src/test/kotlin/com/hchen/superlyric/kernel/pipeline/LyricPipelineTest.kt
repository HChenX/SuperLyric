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
            <Lyric_1 LyricType="1" LyricContent="[0,2000](0,2000)Hello"/>
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
            text = "[0,2000](0,2000)Hello",
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
            text = "[0,2000](0,2000)Hello",
            phoneticText = "[00:00.00]he-lou",
        )

        val doc = LyricPipeline.parse(request)

        assertNotNull(doc)
        assertEquals("he-lou", doc!!.lines[0].phonetic)
        assertNull(doc.lines[0].translation)
    }

    @Test
    fun `auto format sniffs each structural shape`() {
        val qrc = LyricPipeline.parse(LyricRequest(text = "[0,2000](0,2000)Hello"))
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
                text = "[0,1000](0,1000)Hello",
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
        val qrc = "[1000,2000](0,500)你(500,500)好"

        val doc = LyricPipeline.parse(LyricRequest(text = qrc))

        assertNotNull(doc)
        val words = doc!!.lines[0].words
        assertEquals(1_000L, words[0].startMs, "QRC 词起始按行内相对偏移换算")
        assertEquals(1_500L, words[1].startMs)
    }
}
