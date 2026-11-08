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

import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeakerLabelMergerTest {

    @Test
    fun `male female unison and chorus labels merge into the next line`() {
        for (label in listOf("男：", "女：", "合：", "合唱：")) {
            val merged = SpeakerLabelMerger.merge(listOf(line(1000, label), line(1200, "Hello")))

            assertEquals(1, merged.size, label)
            assertEquals(label + "Hello", merged.single().text, label)
            assertEquals(1200L, merged.single().startMs, label)
        }
    }

    @Test
    fun `English singer labels are restricted`() {
        assertEquals(2, SpeakerLabelMerger.merge(listOf(line(1000, "Hello:"), line(1200, "lyric"))).size)
        assertEquals(1, SpeakerLabelMerger.merge(listOf(line(1000, "A:"), line(1200, "lyric"))).size)
        assertEquals(1, SpeakerLabelMerger.merge(listOf(line(1000, "歌手A："), line(1200, "lyric"))).size)
        assertEquals(1, SpeakerLabelMerger.merge(listOf(line(1000, "男："), line(1200, "lyric"))).size)
        assertEquals(2, SpeakerLabelMerger.merge(listOf(line(1000, "Verse:"), line(1200, "lyric"))).size)
    }

    @Test
    fun `section labels are not merged`() {
        for (label in listOf("Verse:", "Chorus:", "Intro:", "Bridge:")) {
            val merged = SpeakerLabelMerger.merge(listOf(line(1000, label), line(1200, "Hello")))

            assertEquals(2, merged.size, label)
            assertEquals(label, merged[0].text, label)
            assertEquals("Hello", merged[1].text, label)
        }
    }

    @Test
    fun `a section label between speaker and target is skipped`() {
        val merged = SpeakerLabelMerger.merge(
            listOf(line(1000, "男："), line(1150, "Chorus:"), line(1300, "Hello"))
        )

        assertEquals(2, merged.size)
        assertEquals("Chorus:", merged[0].text)
        assertEquals("男：Hello", merged[1].text)
    }

    @Test
    fun `a label more than 1_5 seconds away stays independent`() {
        val merged = SpeakerLabelMerger.merge(listOf(line(1000, "男："), line(2501, "Hello")))

        assertEquals(2, merged.size)
        assertEquals("男：", merged[0].text)
        assertEquals("Hello", merged[1].text)
    }

    @Test
    fun `a label exactly 1_5 seconds away still merges`() {
        val merged = SpeakerLabelMerger.merge(listOf(line(1000, "男："), line(2500, "Hello")))

        assertEquals(1, merged.size)
        assertEquals("男：Hello", merged.single().text)
    }

    @Test
    fun `ordinary colon lyrics are not merged`() {
        val merged = SpeakerLabelMerger.merge(listOf(line(1000, "他说："), line(1200, "Hello")))

        assertEquals(2, merged.size)
        assertEquals("他说：", merged[0].text)
        assertEquals("Hello", merged[1].text)
    }

    @Test
    fun `words keep their timing and receive the label on the first word`() {
        val target = line(
            1400, "Hello world",
            word("Hello", 1400, 1800),
            word(" world", 1800, 2200),
        )
        val merged = SpeakerLabelMerger.merge(listOf(line(1000, "男："), target))

        assertEquals(1, merged.size)
        val line = merged.single()
        assertEquals("男：Hello world", line.text)
        assertEquals("男：Hello", line.words[0].text)
        assertEquals(1400L, line.words[0].startMs)
        assertEquals(1800L, line.words[0].endMs)
        assertEquals(" world", line.words[1].text)
        assertEquals(1800L, line.words[1].startMs)
        assertEquals(2200L, line.words[1].endMs)
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test
    fun `a label with no nearby target is preserved`() {
        val merged = SpeakerLabelMerger.merge(listOf(line(1000, "男："), line(3000, "Hello")))

        assertEquals(2, merged.size)
        assertEquals("男：", merged[0].text)
    }

    private fun line(
        start: Long,
        text: String,
        vararg words: LyricWord,
    ) = LyricLine(startMs = start, endMs = start + 1000, text = text, words = words.toList())

    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start, end)
}
