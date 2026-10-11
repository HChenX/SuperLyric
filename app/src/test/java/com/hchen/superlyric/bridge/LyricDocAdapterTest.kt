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
package com.hchen.superlyric.bridge

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.model.PureMusicDoc
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 适配器的字段映射与深拷贝语义。
 *
 * 这一层最容易出的错是"翻译与罗马音串了通道"以及"跨 Binder 共享了可变实例"，
 * 两者都会在真机上表现为难查的显示异常，因此必须有断言兜住。
 */
class LyricDocAdapterTest {

    private val doc = LyricDoc(
        lines = listOf(
            LyricLine(
                startMs = 0L,
                endMs = 1_000L,
                text = "Hello",
                translation = "你好",
                phonetic = "he-lou",
                words = listOf(
                    LyricWord("He", 0L, 400L),
                    LyricWord("llo", 400L, 1_000L),
                ),
            ),
            LyricLine(startMs = 1_000L, endMs = 2_000L, text = "World"),
        ),
        title = "T",
        artist = "A",
        album = "Al",
        durationMs = 2_000L,
        lyricId = "qq:x",
    )

    @Test
    fun `full data round trips through the adapter`() {
        val restored = LyricDocAdapter.toDoc(LyricDocAdapter.toFullData(doc))

        assertEquals(2, restored.lines.size)
        assertEquals("Hello", restored.lines[0].text)
        assertEquals("你好", restored.lines[0].translation)
        assertEquals("he-lou", restored.lines[0].phonetic)
        assertEquals(2, restored.lines[0].words.size)
        assertEquals(LyricWord("llo", 400L, 1_000L), restored.lines[0].words[1])
        assertEquals("World", restored.lines[1].text)
        assertEquals("T", restored.title)
        assertEquals("A", restored.artist)
        assertEquals("Al", restored.album)
        assertEquals(2_000L, restored.durationMs)
        assertEquals("qq:x", restored.lyricId)
    }

    @Test
    fun `full payload is classified as a full payload`() {
        val data = LyricDocAdapter.toFullData(doc)

        assertTrue(data.hasAllLyrics())
        assertTrue(data.isFullPayload())
        assertEquals(2, data.allLyricsCount)
        assertEquals(0, data.currentLyricIndex)
        assertEquals("Hello", data.lyric!!.text)
    }

    @Test
    fun `every call allocates fresh instances`() {
        val first = LyricDocAdapter.toFullData(doc)
        val second = LyricDocAdapter.toFullData(doc)

        assertNotSame(first, second)
        assertNotSame(first.allLyrics!![0], second.allLyrics!![0])
        assertNotSame(first.allLyrics!![0].words!![0], second.allLyrics!![0].words!![0])
    }

    @Test
    fun `mutating a produced payload cannot leak back into the source model`() {
        val data = LyricDocAdapter.toFullData(doc)
        data.allLyrics!![0].setTranslation("mutated")

        val fresh = LyricDocAdapter.toFullData(doc)

        assertEquals("你好", fresh.allLyrics!![0].translation)
        assertEquals("你好", doc.lines[0].translation)
    }

    @Test
    fun `line payload carries index position and both sidecar channels`() {
        val payload = LyricDocAdapter.toLineData(doc, 0, 750L)

        assertNotNull(payload)
        assertFalse(payload!!.hasAllLyrics(), "增量包不得携带全量歌词")
        assertEquals(0, payload.currentLyricIndex)
        assertEquals(750L, payload.position)
        assertEquals("Hello", payload.lyric!!.text)
        assertEquals("你好", payload.translation!!.text)
        assertEquals("he-lou", payload.secondary!!.text)
    }

    @Test
    fun `line payload omits sidecar channels that are absent`() {
        val payload = LyricDocAdapter.toLineData(doc, 1, 1_200L)

        assertNotNull(payload)
        assertFalse(payload!!.hasTranslation())
        assertFalse(payload.hasSecondary())
    }

    @Test
    fun `out of range line index yields null instead of throwing`() {
        assertNull(LyricDocAdapter.toLineData(doc, 5, 0L))
        assertNull(LyricDocAdapter.toLineData(doc, -1, 0L))
    }

    @Test
    fun `placeholder document is published as a well formed full payload`() {
        val data = LyricDocAdapter.toFullData(
            PureMusicDoc.create(title = "T", artist = "A", durationMs = 120_000L)
        )

        assertTrue(data.hasAllLyrics())
        assertTrue(data.isFullPayload())
        assertEquals(1, data.allLyricsCount)
        assertEquals(PureMusicDoc.TEXT, data.allLyrics!![0].text)
        assertEquals(120_000L, data.duration)
    }

    @Test
    fun `null input yields an empty document`() {
        assertTrue(LyricDocAdapter.toDoc(null).isEmpty)
    }

    @Test
    fun `a delta payload falls back to the single line channel`() {
        // 增量包不带 allLyrics，只有顶层译文/次歌词这一条通道。只读全量通道会解出空文档，
        // 行、译文、次歌词一起丢光（全量包则会把每行的译文/次歌词随行过线，两条都在 wire 上）
        val delta = SuperLyricData()
            .setTitle("T")
            .setArtist("A")
            .setAlbum("Al")
            .setLyricId("qq:x")
            .setDuration(2_000L)
            .setCurrentLyricIndex(1)
            .setPosition(1_400L)
            .setLyric(SuperLyricLine("World", null, 1_000L, 2_000L))
            .setTranslation(SuperLyricLine("世界", null, 1_000L, 2_000L))
            .setSecondary(SuperLyricLine("wa-rudo", null, 1_000L, 2_000L))

        val restored = LyricDocAdapter.toDoc(delta)

        assertEquals(1, restored.lines.size)
        assertEquals("World", restored.lines[0].text)
        assertEquals("世界", restored.lines[0].translation)
        assertEquals("wa-rudo", restored.lines[0].phonetic)
        assertEquals(1_000L, restored.lines[0].startMs)
        assertEquals("T", restored.title)
        assertEquals("qq:x", restored.lyricId)
    }

    @Test
    fun `a stop payload still yields an empty document`() {
        // 回退不得把"停止"变成"有一行歌词"：停止包只有元数据，没有任何行
        val stop = SuperLyricData().setTitle("T").setArtist("A")

        assertTrue(LyricDocAdapter.toDoc(stop).isEmpty)
    }
}
