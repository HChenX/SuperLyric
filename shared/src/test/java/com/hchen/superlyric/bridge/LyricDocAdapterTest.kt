@file:Suppress("DEPRECATION")

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
 * v4 投影与 3.x 边界转换
 *
 * 核心会话只保存 4.0 Lyric；3.x 载荷只在发布或接收边界出现。
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

    private fun lyric(): com.hchen.superlyricapi.model.Lyric =
        LyricDocAdapter.toLyric(doc)!!

    @Test
    fun `v4 projection keeps every channel in its own field`() {
        val lyric = lyric()

        assertEquals("qq:x", lyric.id)
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
    fun `v4 projection round trips through the kernel model`() {
        val restored = LyricDocAdapter.fromLyric(lyric())

        assertEquals(doc.lines, restored.lines)
        assertEquals(doc.title, restored.title)
        assertEquals(doc.artist, restored.artist)
        assertEquals(doc.album, restored.album)
        assertEquals(doc.durationMs, restored.durationMs)
        assertEquals(doc.lyricId, restored.lyricId)
    }

    @Test
    fun `a blank title stays an empty string in the v4 model`() {
        val lyric = LyricDocAdapter.toLyric(doc.copy(title = ""))!!

        assertEquals("", lyric.title)
        assertEquals("", LyricDocAdapter.fromLyric(lyric).title)
    }

    @Test
    fun `v4 projection keeps an unknown duration and fills only the required id`() {
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

    @Test
    fun `a system duration only fills an unknown v4 duration`() {
        val unknown = LyricDocAdapter.toLyric(doc.copy(durationMs = 0L))!!
        assertEquals(180_000L, LyricDocAdapter.withDuration(unknown, 180_000L).duration)

        val known = lyric()
        assertEquals(2_000L, LyricDocAdapter.withDuration(known, 180_000L).duration)
    }

    @Test
    fun `legacy full payload carries all lines and the current row`() {
        val payload = LyricDocAdapter.toLegacyFull(lyric(), 1, 1_500L)

        assertTrue(payload.hasAllLyrics())
        assertTrue(payload.isFullPayload())
        assertEquals(2, payload.allLyricsCount)
        assertEquals(1, payload.currentLyricIndex)
        assertEquals(1_500L, payload.position)
        assertEquals("World", payload.lyric!!.text)
        assertEquals("T", payload.title)
        assertEquals("qq:x", payload.lyricId)
    }

    @Test
    fun `legacy line payload omits all lyrics and carries sidecars`() {
        val payload = LyricDocAdapter.toLegacyLine(lyric(), 0, 750L)

        assertNotNull(payload)
        assertFalse(payload!!.hasAllLyrics())
        assertEquals(0, payload.currentLyricIndex)
        assertEquals(750L, payload.position)
        assertEquals("Hello", payload.lyric!!.text)
        assertEquals("你好", payload.translation!!.text)
        assertEquals("he-lou", payload.secondary!!.text)
    }

    @Test
    fun `legacy line payload omits absent sidecars`() {
        val payload = LyricDocAdapter.toLegacyLine(lyric(), 1, 1_200L)

        assertNotNull(payload)
        assertFalse(payload!!.hasTranslation())
        assertFalse(payload.hasSecondary())
    }

    @Test
    fun `legacy full payload before the first line is metadata only`() {
        val payload = LyricDocAdapter.toLegacyFull(lyric(), -1, 250L)

        assertFalse(payload.hasAllLyrics())
        assertEquals(-1, payload.currentLyricIndex)
        assertNull(payload.lyric)
        assertNull(payload.translation)
        assertNull(payload.secondary)
        assertEquals(250L, payload.position)
        assertEquals("T", payload.title)
        assertEquals("A", payload.artist)
        assertEquals("qq:x", payload.lyricId)
    }

    @Test
    fun `out of range legacy line yields null`() {
        assertNull(LyricDocAdapter.toLegacyLine(lyric(), 5, 0L))
        assertNull(LyricDocAdapter.toLegacyLine(lyric(), -1, 0L))
    }

    @Test
    fun `legacy full projection allocates fresh instances`() {
        val first = LyricDocAdapter.toLegacyFull(lyric(), 0, 0L)
        val second = LyricDocAdapter.toLegacyFull(lyric(), 0, 0L)

        assertNotSame(first, second)
        assertNotSame(first.allLyrics!![0], second.allLyrics!![0])
        assertNotSame(first.allLyrics!![0].words!![0], second.allLyrics!![0].words!![0])
    }

    @Test
    fun `legacy full projection does not leak source mutation`() {
        val payload = LyricDocAdapter.toLegacyFull(lyric(), 0, 0L)
        payload.allLyrics!![0].setTranslation("mutated")

        assertEquals("你好", LyricDocAdapter.toLegacyFull(lyric(), 0, 0L).allLyrics!![0].translation)
    }

    @Test
    fun `placeholder projects as a full legacy payload`() {
        val lyric = LyricDocAdapter.toLyric(
            PureMusicDoc.create(title = "T", artist = "A", durationMs = 120_000L)
        )!!
        val payload = LyricDocAdapter.toLegacyFull(lyric, 0, 0L)

        assertTrue(payload.hasAllLyrics())
        assertEquals(1, payload.allLyricsCount)
        assertEquals(PureMusicDoc.TEXT, payload.allLyrics!![0].text)
        assertEquals(120_000L, payload.duration)
    }

    @Test
    fun `a legacy full payload converts to a v4 document`() {
        val payload = LyricDocAdapter.toLegacyFull(lyric(), 0, 0L)
        val restored = LyricDocAdapter.fromLegacy(payload)

        assertNotNull(restored)
        assertEquals(2, restored!!.lines.size)
        assertEquals("Hello", restored.lines[0].text)
        assertEquals("你好", restored.lines[0].translation)
        assertEquals("he-lou", restored.lines[0].phonetic)
        assertEquals("T", restored.title)
        assertEquals("qq:x", restored.id)
    }

    @Test
    fun `a legacy single line payload converts to a one line v4 document`() {
        // 3.5 以下只有当前句：不得伪造全量行
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

        val restored = LyricDocAdapter.fromLegacy(delta)

        assertNotNull(restored)
        assertEquals(1, restored!!.lines.size)
        assertEquals("World", restored.lines[0].text)
        assertEquals("世界", restored.lines[0].translation)
        assertEquals("wa-rudo", restored.lines[0].phonetic)
        assertEquals(1_000L, restored.lines[0].start)
        assertEquals("T", restored.title)
        assertEquals("qq:x", restored.id)
    }

    @Test
    fun `null or stop legacy payload yields no v4 document`() {
        assertNull(LyricDocAdapter.fromLegacy(null))
        assertNull(LyricDocAdapter.fromLegacy(SuperLyricData().setTitle("T").setArtist("A")))
    }
}
