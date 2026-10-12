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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.provider.mobilemusic

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.merge.TimeText
import com.hchen.superlyric.kernel.orchestrate.Cancellable
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.orchestrate.Scheduler
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.parse.MobileMusicLyricParser.NativeLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

import java.util.concurrent.Executor

class MobileMusicHookSourceTest {

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
        val delivered = ArrayList<LyricOutcome>()

        override fun deliver(outcome: LyricOutcome) {
            delivered += outcome
        }
    }

    private fun identity(id: String) =
        TrackIdentity("MobileMusicProvider", "migu:getSongId:$id", "Song $id", "Artist", 5_000L)

    private fun snapshot(
        id: String,
        translation: String? = null,
        offset: Long = 0L,
        unavailable: String? = null,
    ) = MobileMusicAnchors.Capture(
        identity(id),
        "Album",
        listOf(NativeLine(1_000L, 2_000L, "Hello", null, listOf("Hello"), listOf(1_000L))),
        translation?.let { listOf(TimeText(1_000L, it)) }.orEmpty(),
        offset,
        unavailable,
    )

    @Test
    fun `a first native result cannot match provisional metadata by title alone`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        for (weak in listOf(
            identity("A").copy(stableId = null, artist = ""),
            identity("A").copy(stableId = null, durationMs = 0L),
        )) {
            val session = Session()
            source.start(weak, session)
            source.onCaptured(snapshot("A"))
            executor.drain()
            assertTrue(session.delivered.isEmpty())
        }
    }

    @Test
    fun `a snapshot without a stable id cannot weaken an authoritative request to title only matching`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        for (weak in listOf(
            identity("A").copy(stableId = null, artist = ""),
            identity("A").copy(stableId = null, durationMs = 0L),
        )) {
            val session = Session()
            source.start(identity("A"), session)
            val captured = snapshot("A")
            source.onCaptured(
                MobileMusicAnchors.Capture(
                    weak,
                    captured.album(),
                    captured.lines(),
                    captured.translations(),
                    captured.offsetMs(),
                    captured.unavailableReason(),
                ),
            )
            executor.drain()
            assertTrue(session.delivered.isEmpty())
        }
    }

    @Test
    fun `lyrics captured before the media session are replayed for the same stable id`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        source.onCaptured(snapshot("A"))
        val session = Session()
        source.start(identity("A"), session)
        executor.drain()
        val doc = (session.delivered.single() as LyricOutcome.Success).doc
        assertEquals("Song A", doc.title)
        assertEquals("migu:getSongId:A", doc.lyricId)
        assertEquals("Album", doc.album)
    }

    @Test
    fun `a stale snapshot is never relabelled as the next track`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        source.onCaptured(snapshot("A"))
        val session = Session()
        source.start(identity("B"), session)
        executor.drain()
        assertTrue(session.delivered.isEmpty())
        source.onCaptured(snapshot("B"))
        executor.drain()
        assertEquals("Song B", (session.delivered.single() as LyricOutcome.Success).doc.title)
    }

    @Test
    fun `queued work is discarded after a track switch and after cancellation`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        val old = Session()
        source.start(identity("A"), old)
        source.onCaptured(snapshot("A"))
        val next = Session()
        source.start(identity("B"), next)
        executor.drain()
        assertTrue(old.delivered.isEmpty())
        assertTrue(next.delivered.isEmpty())
        source.onCaptured(snapshot("B"))
        source.cancel()
        executor.drain()
        assertTrue(next.delivered.isEmpty())
    }

    @Test
    fun `the first document retains translation and offset while later captures do not republish`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        val session = Session()
        source.start(identity("A"), session)
        source.onCaptured(snapshot("A", translation = "你好", offset = 200L))
        executor.drain()
        source.onCaptured(snapshot("A", translation = "Later", offset = 400L))
        executor.drain()
        assertEquals(1, session.delivered.size)
        val doc = (session.delivered.single() as LyricOutcome.Success).doc
        assertEquals("你好", doc.lines.single().translation)
        assertEquals(800L, doc.lines.single().startMs)
    }

    @Test
    fun `an unavailable snapshot does not consume the first native result`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        val session = Session()
        source.start(identity("A"), session)
        source.onCaptured(snapshot("A", unavailable = "host lyric unavailable"))
        executor.drain()
        assertTrue(session.delivered.isEmpty())
        source.onCaptured(snapshot("A", translation = "你好"))
        executor.drain()
        assertEquals(1, session.delivered.size)
        assertEquals("你好", (session.delivered.single() as LyricOutcome.Success).doc.lines.single().translation)
    }

    @Test
    fun `native capture uses the existing orchestrator delivery and cancellation contract`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        val timeouts = ArrayList<() -> Unit>()
        val published = ArrayList<LyricOutcome.Success>()
        var unavailable = 0
        val scheduler = object : Scheduler {
            override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
                timeouts += task
                return Cancellable { timeouts.remove(task) }
            }
        }
        val listener = object : LyricOrchestrator.Listener {
            override fun onLyricResolved(outcome: LyricOutcome.Success) {
                published += outcome
            }

            override fun onNoLyrics(identity: TrackIdentity?) = Unit

            override fun onUnavailable(identity: TrackIdentity?) {
                unavailable++
            }

            override fun onIdle() = Unit
        }
        val orchestrator = LyricOrchestrator(source, null, scheduler, listener)
        orchestrator.onTrackChanged(identity("A"))
        source.onCaptured(snapshot("A", unavailable = "download pending"))
        executor.drain()
        assertEquals(LyricOrchestrator.State.RESOLVING_HOOK, orchestrator.state)
        source.onCaptured(snapshot("A", translation = "你好"))
        executor.drain()
        assertEquals(LyricOrchestrator.State.ACTIVE, orchestrator.state)
        assertEquals("你好", published.single().doc.lines.single().translation)
        assertTrue(timeouts.isEmpty())
        source.onCaptured(snapshot("A", offset = 200L))
        executor.drain()
        assertEquals(1, published.size)

        orchestrator.onTrackChanged(identity("B"))
        executor.drain()
        timeouts.toList().forEach { it() }
        assertEquals(LyricOrchestrator.State.UNAVAILABLE, orchestrator.state)
        source.onCaptured(snapshot("B"))
        executor.drain()
        assertEquals(1, published.size)
        assertEquals(1, unavailable)

        orchestrator.onPlaybackStopped()
        source.onCaptured(snapshot("A", translation = "你好"))
        executor.drain()
        assertEquals(LyricOrchestrator.State.IDLE, orchestrator.state)
        assertEquals(1, published.size)
    }

    @Test
    fun `the host download error placeholder is never published as a no lyrics verdict`() {
        val executor = QueueExecutor()
        val source = MobileMusicHookSource(MobileMusicAnchors(), executor)
        val session = Session()
        source.start(identity("A"), session)
        source.onCaptured(snapshot("A", unavailable = "host lyric unavailable"))
        executor.drain()
        assertTrue(session.delivered.isEmpty())
    }
}
