/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.provider.cider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CiderAnchorsTest {
    enum class Timing { Word, Line, None }

    class Queue(
        @JvmField val b: String = "123456",
        @JvmField val c: String = "Title",
        @JvmField val d: String = "Artist",
        @JvmField val e: String = "Album",
        @JvmField val g: Long = 200_000L,
    )

    class NativeWord(
        @JvmField val a: Long,
        @JvmField val b: Long,
        @JvmField val c: String,
    )

    class NativeLine(
        @JvmField val a: Long,
        @JvmField val b: Long,
        @JvmField val c: String,
        @JvmField val d: List<NativeWord>,
        @JvmField val e: String?,
        @JvmField val i: Boolean = false,
        @JvmField var j: NativeLine? = null,
    )

    class NativeLyrics(
        @JvmField val a: List<NativeLine>,
        @JvmField val b: Timing = Timing.Word,
    )

    class IncompatibleWord(
        @JvmField val a: Int = 0,
        @JvmField val b: Long = 0L,
        @JvmField val c: String = "",
    )

    private fun reader() = CiderAnchors.ModelReader(
        Queue::class.java, NativeLyrics::class.java, NativeLine::class.java, NativeWord::class.java,
    )

    @Test
    fun `snapshot preserves absolute milliseconds spaces translations and background lines`() {
        val words = mutableListOf(NativeWord(1_000, 1_200, "Hello "), NativeWord(1_300, 1_600, "world"))
        val backgroundWords = mutableListOf(NativeWord(1_100, 1_400, "(oh)"))
        val background = NativeLine(1_100, 1_400, "(oh)", backgroundWords, "和声")
        val lines = mutableListOf(NativeLine(1_000, 2_000, "Hello world", words, "你好", j = background))
        val capture = reader().read(Queue(), NativeLyrics(lines), false)

        words.clear()
        backgroundWords.clear()
        lines.clear()

        assertEquals("123456", capture.catalogId())
        assertEquals("Title", capture.title())
        assertEquals("Artist", capture.artist())
        assertEquals("Album", capture.album())
        assertEquals(200_000L, capture.durationMs())
        assertEquals("Word", capture.timingType())
        val line = capture.lines().single()
        assertEquals("Hello world", line.text())
        assertEquals("你好", line.translation())
        assertEquals(1_000L, line.startMs())
        assertEquals(2_000L, line.endMs())
        assertEquals(listOf("Hello ", "world"), line.words().map { it.text() })
        assertEquals(1_300L, line.words()[1].startMs())
        assertEquals(1_600L, line.words()[1].endMs())
        assertEquals("(oh)", line.background()!!.words().single().text())
        assertEquals("和声", line.background()!!.translation())
        assertThrows(UnsupportedOperationException::class.java) { capture.lines().clear() }
        assertThrows(UnsupportedOperationException::class.java) { line.words().clear() }
    }

    @Test
    fun `posted request requires the exact current catalog id`() {
        val reader = reader()
        assertTrue(reader.belongsTo(Queue(), "123456"))
        assertFalse(reader.belongsTo(Queue(), "123"))
        assertFalse(reader.belongsTo(Queue(), "1234567"))
        assertFalse(reader.belongsTo(Queue(), ""))
        assertFalse(reader.belongsTo(Queue(), null))
        assertFalse(reader.belongsTo(null, "123456"))
    }

    @Test
    fun `changed numeric field signatures fail before installing hooks`() {
        assertThrows(NoSuchFieldException::class.java) {
            CiderAnchors.ModelReader(
                Queue::class.java, NativeLyrics::class.java, NativeLine::class.java, IncompatibleWord::class.java,
            )
        }
    }

    @Test
    fun `snapshot copies only the main and one background layer`() {
        val line = NativeLine(1_000, 2_000, "Hello", emptyList(), null)
        line.j = line
        val capture = reader().read(Queue(), NativeLyrics(listOf(line)), false)
        assertEquals("Hello", capture.lines().single().background()!!.text())
        assertNull(capture.lines().single().background()!!.background())
    }

    @Test
    fun `only the explicit instrumental flag permits a null lyric model`() {
        val capture = reader().read(Queue(), null, true)
        assertTrue(capture.instrumental())
        assertTrue(capture.lines().isEmpty())
        assertEquals("None", capture.timingType())
        assertThrows(IllegalArgumentException::class.java) { reader().read(Queue(), null, false) }
        assertFalse(reader().read(Queue(), NativeLyrics(emptyList(), Timing.None), false).instrumental())
    }

    @Test
    fun `unfinished translation waits but delivers available lyrics at the deadline`() {
        val gate = CiderAnchors.SnapshotGate(4, "123456", 1_000)
        assertEquals(CiderAnchors.SnapshotGate.Decision.WAIT, gate.decide(4, "123456", "123456", false, 1_100))
        assertEquals(CiderAnchors.SnapshotGate.Decision.WAIT, gate.decide(4, "123456", "123456", false, 3_499))
        assertEquals(CiderAnchors.SnapshotGate.Decision.DELIVER, gate.decide(4, "123456", "123456", false, 3_500))
    }

    @Test
    fun `completed and unavailable jobs preserve the current lyric snapshot`() {
        val gate = CiderAnchors.SnapshotGate(4, "123456", 1_000)
        assertEquals(CiderAnchors.SnapshotGate.Decision.DELIVER, gate.decide(4, "123456", "123456", true, 1_100))
        assertEquals(CiderAnchors.SnapshotGate.Decision.DELIVER, gate.decide(4, "123456", "123456", null, 1_100))
    }

    @Test
    fun `newer setter triggers and changed playback invalidate waiting snapshots`() {
        val gate = CiderAnchors.SnapshotGate(4, "123456", 1_000)
        assertEquals(CiderAnchors.SnapshotGate.Decision.DROP, gate.decide(5, "123456", "123456", true, 1_100))
        assertEquals(CiderAnchors.SnapshotGate.Decision.DROP, gate.decide(4, "654321", "654321", true, 1_100))
        assertEquals(CiderAnchors.SnapshotGate.Decision.DROP, gate.decide(4, "123456", "654321", true, 1_100))
    }
}
