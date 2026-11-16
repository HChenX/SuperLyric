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
package com.hchen.superlyric.provider.cider

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

class CiderHookSourceTest {

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private class Session : LyricSource.Session {
        val outcomes = mutableListOf<LyricOutcome>()

        override fun deliver(outcome: LyricOutcome) {
            outcomes += outcome
        }
    }

    private fun identity(id: String) =
        TrackIdentity("CiderProvider", "cider:$id", "Song", "Artist", 5_000L)

    private fun capture(
        id: String,
        timing: String = "Word",
        empty: Boolean = false,
        translation: String? = "你好世界",
        instrumental: Boolean = false,
    ) =
        CiderAnchors.Capture(
            id, "Host song", "Host artist", "Album", 5_000L, timing,
            if (empty) emptyList() else listOf(
                CiderAnchors.Line(
                    "Hello world", translation, 1_000L, 3_000L,
                    listOf(
                        CiderAnchors.Word("Hello ", 1_000L, 2_000L),
                        CiderAnchors.Word("world", 2_000L, 3_000L),
                    ),
                    false, null,
                ),
            ),
            instrumental,
        )

    @Test
    fun `start actively requests the catalog id and accepts a synchronous cache snapshot`() {
        val executor = QueueExecutor()
        val requested = mutableListOf<String>()
        lateinit var source: CiderHookSource
        source = CiderHookSource(executor) { id ->
            requested += id
            source.onCaptured(capture(id))
        }
        val session = Session()

        source.start(identity("123"), session)
        assertTrue(session.outcomes.isEmpty())
        executor.drain()

        assertEquals(listOf("123"), requested)
        val result = session.outcomes.single() as LyricOutcome.Success
        assertEquals("cider:123", result.doc.lyricId)
        assertEquals("Song", result.doc.title)
        assertEquals("Album", result.doc.album)
        assertEquals("你好世界", result.doc.lines.single().translation)
        assertEquals("Hello world", result.doc.lines.single().words.joinToString("") { it.text })
    }

    @Test
    fun `late results and similar catalog ids cannot attach to the new track`() {
        val executor = QueueExecutor()
        val source = CiderHookSource(executor) {}
        val old = Session()
        val current = Session()
        source.start(identity("123"), old)
        source.onCaptured(capture("123"))
        source.start(identity("1234"), current)
        source.onCaptured(capture("123"))
        source.onCaptured(capture("1234"))

        executor.drain()

        assertTrue(old.outcomes.isEmpty())
        assertEquals("cider:1234", (current.outcomes.single() as LyricOutcome.Success).doc.lyricId)
    }

    @Test
    fun `same track restart and cancel invalidate queued parsing`() {
        val executor = QueueExecutor()
        val source = CiderHookSource(executor) {}
        val old = Session()
        val restarted = Session()
        source.start(identity("123"), old)
        source.onCaptured(capture("123"))
        source.start(identity("123"), restarted)
        executor.drain()
        assertTrue(old.outcomes.isEmpty())
        assertTrue(restarted.outcomes.isEmpty())

        source.onCaptured(capture("123"))
        source.cancel()
        executor.drain()
        assertTrue(restarted.outcomes.isEmpty())
    }

    @Test
    fun `empty and untimed host models allow fallback without instrumental verdicts`() {
        val executor = QueueExecutor()
        val source = CiderHookSource(executor) {}
        for (snapshot in listOf(capture("123", "None"), capture("123", empty = true))) {
            val session = Session()
            source.start(identity("123"), session)
            source.onCaptured(snapshot)
            executor.drain()
            assertTrue(session.outcomes.single() is LyricOutcome.Unsupported)
        }
    }

    @Test
    fun `absent host translation still delivers native lyrics`() {
        val source = CiderHookSource(Executor { it.run() }) {}
        val session = Session()
        source.start(identity("123"), session)
        source.onCaptured(capture("123", translation = null))

        val result = session.outcomes.single() as LyricOutcome.Success
        assertEquals("Hello world", result.doc.lines.single().text)
        assertEquals(null, result.doc.lines.single().translation)
    }

    @Test
    fun `only a matching explicit instrumental flag produces a no lyrics verdict`() {
        val source = CiderHookSource(Executor { it.run() }) {}
        val session = Session()
        source.start(identity("123"), session)
        source.onCaptured(capture("456", empty = true, instrumental = true))
        assertTrue(session.outcomes.isEmpty())
        source.onCaptured(capture("123", empty = true, instrumental = true))
        assertTrue(session.outcomes.single() is LyricOutcome.NoLyrics)
    }

    @Test
    fun `missing ids do not load the host or guess a matching lyric`() {
        val requests = mutableListOf<String>()
        val source = CiderHookSource(Executor { it.run() }, requests::add)
        val session = Session()

        source.start(identity("123").copy(stableId = null), session)
        source.onCaptured(capture("123"))
        source.onCaptured(capture("0"))

        assertTrue(requests.isEmpty())
        assertTrue(session.outcomes.single() is LyricOutcome.Unsupported)
    }
}
