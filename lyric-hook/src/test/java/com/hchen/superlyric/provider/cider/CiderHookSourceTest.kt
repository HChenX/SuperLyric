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
import com.hchen.superlyric.kernel.orchestrate.Cancellable
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.orchestrate.Scheduler
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
        texts: List<String> = listOf("[00:01.000]Hello world"),
        instrumental: Boolean = false,
    ) = CiderAnchors.Capture(id, texts, instrumental, "Album")

    @Test
    fun `start actively requests the catalog id and parses a synchronous cache payload`() {
        val executor = QueueExecutor()
        val requested = mutableListOf<String>()
        lateinit var source: CiderHookSource
        source = CiderHookSource(executor, requestLyrics = { id ->
            requested += id
            source.onCaptured(capture(id))
        })
        val session = Session()

        source.start(identity("123"), session)
        assertTrue(session.outcomes.isEmpty())
        executor.drain()

        assertEquals(listOf("123"), requested)
        val result = session.outcomes.single() as LyricOutcome.Success
        assertEquals("cider:123", result.doc.lyricId)
        assertEquals("Song", result.doc.title)
        assertEquals("Album", result.doc.album)
        assertEquals("Artist", result.doc.artist)
        assertEquals(5_000L, result.doc.durationMs)
        assertEquals("Hello world", result.doc.lines.single().text)
        assertEquals(1_000L, result.doc.lines.single().startMs)
    }

    @Test
    fun `late results and similar catalog ids cannot attach to the new track`() {
        val executor = QueueExecutor()
        val source = CiderHookSource(executor, {})
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
        val source = CiderHookSource(executor, {})
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
    fun `a source change invalidates queued payloads while waiting for refreshed acquisition`() {
        val executor = QueueExecutor()
        var refreshCount = 0
        val source = CiderHookSource(executor, {}, Runnable { refreshCount++ })
        val old = Session()
        source.start(identity("123"), old)
        source.onCaptured(capture("123"))

        source.onSelectionChanged()
        source.onCaptured(capture("123"))
        executor.drain()

        assertEquals(1, refreshCount)
        assertTrue(old.outcomes.isEmpty())
    }

    @Test
    fun `changing source refreshes the same track before delivering new payloads`() {
        val executor = QueueExecutor()
        val requested = mutableListOf<String>()
        val old = Session()
        val refreshed = Session()
        lateinit var source: CiderHookSource
        source = CiderHookSource(
            executor,
            requested::add,
            Runnable {
                // 刷新回调执行前旧请求已失效；身份不变也要建立新的歌词会话。
                source.onCaptured(capture("123", listOf("[00:01.000]Old pending result")))
                source.start(identity("123"), refreshed)
                source.onCaptured(capture("123", listOf("[00:01.000]Selected source")))
            },
        )
        source.start(identity("123"), old)
        source.onCaptured(capture("123", listOf("[00:01.000]Old source")))

        source.onSelectionChanged()
        executor.drain()

        assertTrue(old.outcomes.isEmpty())
        assertEquals(listOf("123", "123"), requested)
        val result = refreshed.outcomes.single() as LyricOutcome.Success
        assertEquals("Selected source", result.doc.lines.single().text)
        assertEquals("cider:123", result.doc.lyricId)
    }

    @Test
    fun `source changes replace active lyrics with the same track id and do not resurrect playback after stop`() {
        val executor = QueueExecutor()
        val requested = mutableListOf<String>()
        val published = mutableListOf<LyricOutcome.Success>()
        lateinit var orchestrator: LyricOrchestrator
        val source = CiderHookSource(executor, requested::add, Runnable { orchestrator.refreshCurrentTrack() })
        orchestrator = LyricOrchestrator(
            source,
            null,
            object : Scheduler {
                override fun schedule(delayMs: Long, task: () -> Unit) = Cancellable {}
            },
            object : LyricOrchestrator.Listener {
                override fun onLyricResolved(outcome: LyricOutcome.Success) {
                    published += outcome
                }

                override fun onNoLyrics(identity: TrackIdentity?) = error("Unexpected instrumental result")
                override fun onUnavailable(identity: TrackIdentity?) = error("Unexpected unavailable result")
                override fun onIdle() = Unit
            },
        )
        orchestrator.onTrackChanged(identity("123"))
        source.onCaptured(capture("123", listOf("[00:01.000]Initial lyrics")))
        executor.drain()
        assertEquals(LyricOrchestrator.State.ACTIVE, orchestrator.state)

        val choices = listOf("Musixmatch", "Netease", "Community", "Auto", "Apple", "Auto")
        for (choice in choices) {
            source.onCaptured(capture("123", listOf("[00:01.000]Previous queued lyrics")))
            source.onSelectionChanged()
            assertEquals(LyricOrchestrator.State.RESOLVING_HOOK, orchestrator.state)
            source.onCaptured(capture("123", listOf("[00:01.000]$choice")))
            executor.drain()
            assertEquals(LyricOrchestrator.State.ACTIVE, orchestrator.state)
        }

        assertEquals(listOf("Initial lyrics") + choices, published.map { it.doc.lines.single().text })
        assertTrue(published.all { it.doc.lyricId == "cider:123" })
        assertEquals(List(choices.size + 1) { "123" }, requested)

        source.onCaptured(capture("123"))
        orchestrator.onPlaybackStopped()
        source.onSelectionChanged()
        executor.drain()
        assertEquals(LyricOrchestrator.State.IDLE, orchestrator.state)
        assertEquals(choices.size + 1, requested.size)
        assertEquals(choices.size + 1, published.size)
    }

    @Test
    fun `empty malformed and untimed payloads never produce instrumental verdicts`() {
        val executor = QueueExecutor()
        val source = CiderHookSource(executor, {})
        for (texts in listOf(
            emptyList(),
            listOf("", "  "),
            listOf("<tt><body><p begin=\"broken\">"),
            listOf("ordinary untimed text"),
        )) {
            val session = Session()
            source.start(identity("123"), session)
            source.onCaptured(capture("123", texts))
            executor.drain()
            val outcome = session.outcomes.single()
            assertTrue(outcome is LyricOutcome.Unsupported || outcome is LyricOutcome.TransientFailure)
        }
    }

    @Test
    fun `unsupported richsync data does not hide the same response line body`() {
        val source = CiderHookSource(Executor { it.run() }, {})
        val session = Session()
        source.start(identity("123"), session)
        source.onCaptured(
            capture(
                "123",
                listOf(
                    "[{\"ts\":1.0,\"te\":2.0,\"x\":\"richsync\",\"l\":[]}]",
                    "[00:01.000]Line body",
                    "[00:02.000]Unused later candidate",
                ),
            ),
        )

        val result = session.outcomes.single() as LyricOutcome.Success
        assertEquals("Line body", result.doc.lines.single().text)
        assertEquals(1_000L, result.doc.lines.single().startMs)
    }

    @Test
    fun `only a matching explicit instrumental flag produces a no lyrics verdict`() {
        val source = CiderHookSource(Executor { it.run() }, {})
        val session = Session()
        source.start(identity("123"), session)
        source.onCaptured(capture("456", emptyList(), instrumental = true))
        assertTrue(session.outcomes.isEmpty())
        source.onCaptured(capture("123", emptyList(), instrumental = true))
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

    @Test
    fun `raw lrc fractions and offsets use the built in timeline parser`() {
        val source = CiderHookSource(Executor { it.run() }, {})
        val session = Session()
        source.start(identity("123"), session)
        source.onCaptured(
            capture("123", listOf("[offset:+250]\n[00:01.25]one\n[00:03:500]two")),
        )

        val result = session.outcomes.single() as LyricOutcome.Success
        assertEquals(listOf("one", "two"), result.doc.lines.map { it.text })
        assertEquals(listOf(1_500L, 3_750L), result.doc.lines.map { it.startMs })
    }

    @Test
    fun `raw ttml preserves words translation and romanization through the built in parser`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <body><div><p begin="00:00:01.000" end="00:00:03.000"><span begin="00:00:01.000" end="00:00:02.000">你</span><span begin="00:00:02.000" end="00:00:03.000">好</span><span ttm:role="x-translation">Hello</span><span ttm:role="x-roman">ni hao</span></p></div></body></tt>
        """.trimIndent()
        val source = CiderHookSource(Executor { it.run() }, {})
        val session = Session()
        source.start(identity("123"), session)
        source.onCaptured(capture("123", listOf(ttml)))

        val result = session.outcomes.single() as LyricOutcome.Success
        val line = result.doc.lines.single()
        assertEquals("你好", line.text)
        assertEquals("Hello", line.translation)
        assertEquals("ni hao", line.phonetic)
        assertEquals(listOf("你", "好"), line.words.map { it.text })
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals(listOf(1_000L, 2_000L), line.words.map { it.startMs })
        assertEquals(listOf(2_000L, 3_000L), line.words.map { it.endMs })
    }
}
