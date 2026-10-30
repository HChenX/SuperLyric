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
 * 适配器的字段映射与深拷贝语义
 *
 * 译文与罗马音各走独立通道；每次投影都分配新实例，不跨 Binder 共享可变对象。
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
        // 增量包不带 allLyrics，只有顶层译文与次歌词通道；只读全量通道会解出空文档，
        // 行、译文、次歌词一起丢失（全量包则在每行上携带译文与次歌词）
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

    @Test
    fun `full projection carries the current line and position`() {
        val first = LyricDocAdapter.toFullData(doc, 0, 750L)

        assertEquals(2, first.allLyricsCount)
        assertEquals(0, first.currentLyricIndex)
        assertEquals(750L, first.position)
        assertEquals("Hello", first.lyric!!.text)
        assertEquals("你好", first.translation!!.text)
        assertEquals("he-lou", first.secondary!!.text)

        val second = LyricDocAdapter.toFullData(doc, 1, 1_500L)

        assertEquals(1, second.currentLyricIndex)
        assertEquals(1_500L, second.position)
        assertEquals("World", second.lyric!!.text)
        assertFalse(second.hasTranslation())
        assertFalse(second.hasSecondary())
    }

    @Test
    fun `a system duration only fills an unknown document duration`() {
        assertEquals(
            180_000L,
            LyricDocAdapter.withDuration(doc.copy(durationMs = 0L), 180_000L).durationMs
        )
        assertEquals(2_000L, LyricDocAdapter.withDuration(doc, 180_000L).durationMs)
    }

    @Test
    fun `v4 projection keeps every channel in its own field`() {
        val lyric = LyricDocAdapter.toLyric(doc)
        assertNotNull(lyric)

        assertEquals("qq:x", lyric!!.id)
        assertEquals(2_000L, lyric.duration)
        assertEquals("T", lyric.title)
        assertEquals("A", lyric.artist)
        assertEquals("Al", lyric.album)
        assertEquals(2, lyric.lines.size)
        assertEquals(1_000L, lyric.lines[0].end)
        assertEquals("你好", lyric.lines[0].translation)
        assertEquals("he-lou", lyric.lines[0].phonetic)
        assertEquals("llo", lyric.lines[0].words!![1].text)
        assertEquals(400L, lyric.lines[0].words!![1].start)
    }

    @Test
    fun `a blank title stays an empty string in the v4 model`() {
        val lyric = LyricDocAdapter.toLyric(doc.copy(title = ""))!!

        assertEquals("", lyric.title)
        assertEquals("", LyricDocAdapter.fromLyric(lyric).title)
    }

    @Test
    fun `v4 projection round trips through the kernel model`() {
        val lyric = LyricDocAdapter.toLyric(doc)!!
        val restored = LyricDocAdapter.fromLyric(lyric)

        assertEquals(doc.lines, restored.lines)
        assertEquals(doc.title, restored.title)
        assertEquals(doc.artist, restored.artist)
        assertEquals(doc.album, restored.album)
        assertEquals(doc.durationMs, restored.durationMs)
        assertEquals(doc.lyricId, restored.lyricId)
    }

    @Test
    fun `v4 projection keeps an unknown duration and fills only the required id`() {
        // duration = 0 在 4.0 中表示未知，只能补 id，不得伪造时长
        val source = doc.copy(lyricId = null, durationMs = 0L)

        val lyric = LyricDocAdapter.toLyric(source)!!

        assertEquals(0L, lyric.duration)
        assertEquals("sl:T##A", lyric.id)
        assertEquals(lyric.id, LyricDocAdapter.toLyric(source)!!.id)
    }

    @Test
    fun `empty document has no v4 projection`() {
        assertNull(LyricDocAdapter.toLyric(LyricDoc.EMPTY))
    }

    @Test
    fun `null v4 document yields an empty kernel document`() {
        assertTrue(LyricDocAdapter.fromLyric(null).isEmpty)
    }
}
