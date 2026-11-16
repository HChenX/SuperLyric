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

import com.hchen.superlyric.kernel.pipeline.LyricFormatDetector
import com.hchen.superlyric.kernel.pipeline.LyricParseTrace
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.kernel.pipeline.LyricTextKind
import com.hchen.superlyric.kernel.text.LyricTextLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class RichSyncJsonParserTest {

    @Test
    fun `auto parses raw richsync with exact second conversion and word boundary spaces`() {
        val traces = ArrayList<LyricParseTrace>()
        val doc = LyricPipeline.parseDetailed(
            LyricRequest(
                text = """[{"ts":0.29,"te":1.17,"l":[{"c":"Hello ","o":0},{"c":"world","o":0.58}]}]""",
                title = "Song",
                lyricId = "cider:123",
            ),
        ) { traces += it }!!
        val line = doc.lines.single()

        assertEquals("Hello world", line.text)
        assertEquals(290L, line.startMs)
        assertEquals(1_170L, line.endMs)
        assertEquals(listOf("Hello ", "world"), line.words.map { it.text })
        assertEquals(listOf(290L, 870L), line.words.map { it.startMs })
        assertEquals(listOf(870L, 1_170L), line.words.map { it.endMs })
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals("Song", doc.title)
        assertEquals("cider:123", doc.lyricId)
        assertTrue(traces.any { it.parserId == "rich-sync" && it.accepted })
    }

    @Test
    fun `submillisecond origins and offsets are added before integer milliseconds`() {
        val line = LyricPipeline.parse(
            LyricRequest(
                text = """[{"ts":0.0005,"te":0.010,"l":[{"c":"a","o":0.0005},{"c":"b","o":0.0025}]}]""",
            ),
        )!!.lines.single()

        assertEquals(0L, line.startMs)
        assertEquals(listOf(1L, 3L), line.words.map { it.startMs })
        assertEquals(listOf(3L, 10L), line.words.map { it.endMs })
    }

    @Test
    fun `shared normalization sorts rows and repairs zero or out of bounds slices`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                text = """
                    [
                      {"ts":3,"te":4,"l":[{"c":"next","o":0}]},
                      {"te":2,"l":[{"c":"a","o":-0.1},{"c":"b","o":0},{"c":"c","o":0.5}],"ts":1}
                    ]
                """.trimIndent(),
                durationMs = 5_000L,
            ),
        )!!

        assertEquals(listOf("abc", "next"), doc.lines.map { it.text })
        assertEquals(listOf(1_000L, 3_000L), doc.lines.map { it.startMs })
        val first = doc.lines.first()
        assertEquals(first.text, first.words.joinToString("") { it.text })
        assertTrue(first.words.all { it.startMs >= first.startMs && it.endMs <= first.endMs })
        assertTrue(first.words.all { it.endMs > it.startMs })
        assertTrue(first.words.zipWithNext().all { (left, right) -> left.endMs <= right.startMs })
    }

    @Test
    fun `invisible text is cleaned while an explicit space token survives`() {
        val line = LyricPipeline.parse(
            LyricRequest(
                text = """[{"ts":0,"te":1,"l":[{"c":"Hi\u200B","o":0},{"c":" ","o":0.3},{"c":"there\u0000","o":0.4}]}]""",
            ),
        )!!.lines.single()

        assertEquals("Hi there", line.text)
        assertEquals(listOf("Hi", " ", "there"), line.words.map { it.text })
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test
    fun `malformed empty and incomplete payloads do not become lyric documents`() {
        for (text in listOf(
            null,
            "",
            "[]",
            """[{"ts":1,"te":2,"l":[]}]""",
            """[{"ts":1,"te":2,"l":[{"c":" ","o":0}]}]""",
            """[{"ts":1,"l":[{"c":"missing end","o":0}]}]""",
            """[{"ts":1,"te":2,"l":[{"c":"missing offset"}]}]""",
            """[{"ts":1,"te":2,"l":[{"c":42,"o":0}]}]""",
            """[{"ts":1,"te":2,"l":[{"c":"bad offset","o":"x"}]}]""",
            """[{"ts":1,"te":2,"l":[{"c":"truncated","o":0}]}""",
            """[{"ts":1,"te":2,"l":[{"c":"trailing","o":0}]}] other""",
            """[{"ts":NaN,"te":2,"l":[{"c":"not finite","o":0}]}]""",
            """[{"ts":1e100,"te":1e100,"l":[{"c":"overflow","o":0}]}]""",
            """[{"ts":9223372036854775,"te":9223372036854775,"l":[{"c":"overflow","o":10}]}]""",
        )) {
            assertNull(RichSyncJsonParser.parse(text), text)
        }
        val traces = ArrayList<LyricParseTrace>()
        assertNull(
            LyricPipeline.parseDetailed(
                LyricRequest(text = """[{"ts":1,"te":2,"l":[{"c":"bad","o":"x"}]}]"""),
            ) { traces += it },
        )
        assertEquals(listOf("rich-sync"), traces.map { it.parserId })
    }

    @Test
    fun `richsync signatures do not change soda or plain lrc recognition`() {
        assertEquals(
            LyricTextKind.SODA_JSON,
            LyricFormatDetector.detect("""{"sentences":[{"startMs":0,"endMs":1000,"text":"Hi"}]}"""),
        )
        assertEquals(LyricTextKind.LRC, LyricFormatDetector.detect("[00:01]Hi\n[00:02]there"))
        val lrc = LyricPipeline.parse(LyricRequest(text = "[00:01]Hi\n[00:02]there"))!!
        assertEquals(listOf("Hi", "there"), lrc.lines.map { it.text })
        for (text in listOf(
            """{"ts":1,"te":2,"l":[{"c":"object root","o":0}]}""",
            """[{"start":1,"end":2,"text":"unrelated"}]""",
            """[{"ts":1,"te":2,"sentences":[]}]""",
            "[00:01]contains ts te l",
        )) {
            assertFalse(RichSyncJsonParser.canParse(text), text)
        }
    }

    @Test
    fun `a long minified song is recognized from a bounded structural prefix`() {
        val text = (0 until 300).joinToString(",", prefix = "[", postfix = "]") { index ->
            """{"ts":$index,"te":${index + 1},"l":[{"c":"line $index","o":0}]}"""
        }
        assertTrue(text.length > LyricTextLimits.MAX_LINE_CHARS)
        assertTrue(RichSyncJsonParser.canParse(text))
        val doc = LyricPipeline.parse(LyricRequest(text = text))!!
        assertEquals(300, doc.lines.size)
        assertEquals(299_000L, doc.lines.last().words.single().startMs)
        assertEquals("line 299", doc.lines.last().text)

        val distantKeys =
            """[{"padding":"${"x".repeat(LyricTextLimits.MAX_LINE_CHARS)}","ts":0,"te":1,"l":[{"c":"late","o":0}]}]"""
        assertTimeout(Duration.ofSeconds(3)) {
            assertFalse(RichSyncJsonParser.canParse(distantKeys))
        }
    }

    @Test
    fun `pipeline input limit rejects an oversized richsync payload before parsing`() {
        val text = """[{"ts":0,"te":1,"l":[{"c":"${"x".repeat(2_000_001)}","o":0}]}]"""
        val traces = ArrayList<LyricParseTrace>()
        assertNull(LyricPipeline.parseDetailed(LyricRequest(text = text)) { traces += it })
        assertEquals(listOf("input"), traces.map { it.parserId })
    }
}
