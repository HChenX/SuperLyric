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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.kernel.orchestrate

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 编排状态机测试。
 *
 * 每个用例对应一条历史缺陷或一条明确的架构约束。
 */
class LyricOrchestratorTest {

    // ---------------------------------------------------------------- 测试替身

    private class FakeScheduler : Scheduler {
        private class Entry(val task: () -> Unit)
        private val entries = ArrayList<Entry>()

        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val entry = Entry(task)
            entries += entry
            return Cancellable { entries.remove(entry) }
        }

        val pending: Int get() = entries.size

        /** 触发当前所有已排期任务（不含触发过程中新排的）。 */
        fun fireAll() {
            entries.toList().forEach { it.task() }
        }
    }

    private class FakeSource(override val sourceName: String) : LyricSource {
        var startCount = 0
            private set
        var cancelCount = 0
            private set

        /** 按创建顺序记录每一次 start 得到的回投通道，以便复现"旧会话迟到回投"。 */
        val sessions = ArrayList<LyricSource.Session>()

        override fun start(identity: TrackIdentity, session: LyricSource.Session) {
            startCount++
            sessions += session
        }

        override fun cancel() {
            cancelCount++
        }

        /** 通过最近一次会话回投。 */
        fun deliver(outcome: LyricOutcome) {
            sessions.lastOrNull()?.deliver(outcome)
        }
    }

    private class RecordingListener : LyricOrchestrator.Listener {
        var resolved: LyricOutcome.Success? = null
        var noLyricsCount = 0
        var unavailableCount = 0
        var idleCount = 0
        var lastNoLyricsIdentity: TrackIdentity? = null
        var lastUnavailableIdentity: TrackIdentity? = null
        val transitions = ArrayList<LyricOrchestrator.State>()

        override fun onLyricResolved(outcome: LyricOutcome.Success) {
            resolved = outcome
        }

        override fun onNoLyrics(identity: TrackIdentity?) {
            noLyricsCount++
            lastNoLyricsIdentity = identity
        }

        override fun onUnavailable(identity: TrackIdentity?) {
            unavailableCount++
            lastUnavailableIdentity = identity
        }

        override fun onIdle() {
            idleCount++
        }

        override fun onStateChanged(state: LyricOrchestrator.State) {
            transitions += state
        }
    }

    // ---------------------------------------------------------------- 辅助

    private val doc = LyricDoc(lines = listOf(LyricLine(0L, 1_000L, "line")))

    private fun success() = LyricOutcome.Success(doc)

    private fun track(id: String, title: String = "Song") =
        TrackIdentity("qq", "qq:$id", title, "Artist", 200_000L)

    private class Fixture(hook: Boolean = true, api: Boolean = true) {
        val scheduler = FakeScheduler()
        val listener = RecordingListener()
        val hookSource = if (hook) FakeSource("hook") else null
        val apiSource = if (api) FakeSource("api") else null

        /** 可推进的假时钟，用于验证"临时身份升级"的时间上界。 */
        var now = 0L
        val orchestrator = LyricOrchestrator(
            hookSource, apiSource, scheduler, listener, nowMs = { now },
        )
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun `hook hit activates immediately and never touches the api`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        assertEquals(LyricOrchestrator.State.RESOLVING_HOOK, f.orchestrator.state)

        f.hookSource!!.deliver(success())

        assertEquals(LyricOrchestrator.State.ACTIVE, f.orchestrator.state)
        assertNotNull(f.listener.resolved)
        assertEquals(0, f.apiSource!!.startCount)
        assertEquals(0, f.scheduler.pending, "命中后必须取消 Hook 超时任务")
    }

    @Test
    fun `hook timeout falls back to the api`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        assertEquals(1, f.scheduler.pending)

        f.scheduler.fireAll()

        assertEquals(LyricOrchestrator.State.FETCHING_API, f.orchestrator.state)
        assertEquals(1, f.apiSource!!.startCount)
    }

    @Test
    fun `late hook capture after stop is discarded`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        val staleSession = f.hookSource!!.sessions.last()

        f.orchestrator.onPlaybackStopped()
        assertEquals(LyricOrchestrator.State.IDLE, f.orchestrator.state)
        assertEquals(1, f.listener.idleCount)
        assertNull(f.orchestrator.activeIdentity, "停止必须清空当前音轨")

        // 宿主在停止之后才异步吐出歌词
        staleSession.deliver(success())

        assertNull(f.listener.resolved, "停止后迟到的捕获不得发布歌词")
        assertEquals(LyricOrchestrator.State.IDLE, f.orchestrator.state)
    }

    @Test
    fun `stale api result from a previous track is discarded`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        f.hookSource!!.deliver(LyricOutcome.NoLyrics("none"))
        val staleSession = f.apiSource!!.sessions.last()

        // 切到下一首
        f.orchestrator.onTrackChanged(track("B"))

        staleSession.deliver(success())

        assertNull(f.listener.resolved, "旧曲目的网络结果不得覆盖新曲目")
    }

    @Test
    fun `repeated report of the same track does not restart resolution`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        f.hookSource!!.deliver(success())
        assertEquals(1, f.hookSource!!.startCount)

        f.orchestrator.onTrackChanged(track("A"))

        assertEquals(1, f.hookSource!!.startCount, "同一首曲目不得重复取词")
        assertEquals(LyricOrchestrator.State.ACTIVE, f.orchestrator.state)
    }

    @Test
    fun `all sources reporting no lyrics concludes with the placeholder path`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        f.hookSource!!.deliver(LyricOutcome.NoLyrics("host says none"))
        f.apiSource!!.deliver(LyricOutcome.NoLyrics("platform says none"))

        assertEquals(LyricOrchestrator.State.NO_LYRICS, f.orchestrator.state)
        assertEquals(1, f.listener.noLyricsCount)
        assertEquals(0, f.listener.unavailableCount)
        // 下游需要音轨信息才能构造纯音乐占位
        assertEquals("qq:A", f.listener.lastNoLyricsIdentity?.stableId)
    }

    @Test
    fun `hook no lyrics with no api source concludes immediately`() {
        val f = Fixture(api = false)

        f.orchestrator.onTrackChanged(track("A"))
        f.hookSource!!.deliver(LyricOutcome.NoLyrics("none"))

        assertEquals(1, f.listener.noLyricsCount)
        assertEquals(LyricOrchestrator.State.NO_LYRICS, f.orchestrator.state)
    }

    @Test
    fun `transient failures never conclude as no lyrics`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        f.hookSource!!.deliver(LyricOutcome.TransientFailure())

        // 三次网络尝试全部失败
        repeat(3) {
            f.apiSource!!.deliver(LyricOutcome.TransientFailure())
            f.scheduler.fireAll()
        }

        assertEquals(0, f.listener.noLyricsCount, "失败绝不能被当成纯音乐")
        assertEquals(1, f.listener.unavailableCount)
        assertEquals(LyricOrchestrator.State.UNAVAILABLE, f.orchestrator.state)
        assertNull(f.listener.resolved)
    }

    @Test
    fun `api is retried with backoff before giving up`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        f.hookSource!!.deliver(LyricOutcome.TransientFailure())
        assertEquals(1, f.apiSource!!.startCount)

        f.apiSource!!.deliver(LyricOutcome.TransientFailure())
        assertEquals(1, f.scheduler.pending, "首次失败应排期一次重试")

        f.scheduler.fireAll()
        assertEquals(2, f.apiSource!!.startCount)

        f.apiSource!!.deliver(success())
        assertNotNull(f.listener.resolved)
        assertEquals(0, f.listener.unavailableCount, "重试成功后不得再报失败")
        assertEquals(LyricOrchestrator.State.ACTIVE, f.orchestrator.state)
    }

    @Test
    fun `transient hook failure with no api source reports unavailable`() {
        val f = Fixture(api = false)

        f.orchestrator.onTrackChanged(track("A"))
        f.hookSource!!.deliver(LyricOutcome.TransientFailure())

        assertEquals(0, f.listener.noLyricsCount)
        assertEquals(1, f.listener.unavailableCount)
        assertEquals(LyricOrchestrator.State.UNAVAILABLE, f.orchestrator.state)
    }

    @Test
    fun `hook timeout with no api source is not treated as no lyrics`() {
        val f = Fixture(api = false)

        f.orchestrator.onTrackChanged(track("A"))
        f.scheduler.fireAll()

        assertEquals(0, f.listener.noLyricsCount, "超时不等于没有歌词")
        assertEquals(1, f.listener.unavailableCount)
    }

    @Test
    fun `api failure before any hook outcome still allows a later api success`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        f.scheduler.fireAll()
        assertEquals(1, f.apiSource!!.startCount)

        f.apiSource!!.deliver(success())

        assertEquals(LyricOrchestrator.State.ACTIVE, f.orchestrator.state)
        assertNotNull(f.listener.resolved)
    }

    @Test
    fun `rapid track switching discards every stale delivery but accepts the current one`() {
        val f = Fixture()

        repeat(10) { index ->
            f.orchestrator.onTrackChanged(track("TRACK$index"))
        }

        val sessions = f.hookSource!!.sessions
        assertEquals(10, sessions.size)

        // 前九首的会话此刻才回投——全部属于过期代际
        sessions.dropLast(1).forEach { it.deliver(success()) }
        assertNull(f.listener.resolved, "连切 10 次后，所有旧会话的结果都必须被丢弃")

        // 当前这首的会话回投应当被接受
        sessions.last().deliver(success())
        assertNotNull(f.listener.resolved)
        assertEquals(LyricOrchestrator.State.ACTIVE, f.orchestrator.state)
    }

    // ---------------------------------------------------------------- 临时身份升级

    /** 降级形态的身份：宿主还没给出权威标识。 */
    private fun provisional(
        title: String = "Song",
        artist: String = "Artist",
        duration: Long = 200_000L,
    ) = TrackIdentity("qq", null, title, artist, duration)

    /** 权威形态的身份：带命名空间稳定标识。 */
    private fun authoritative(
        id: String,
        title: String = "Song",
        artist: String = "Artist",
        duration: Long = 200_000L,
    ) = TrackIdentity("qq", "qq:$id", title, artist, duration)

    @Test
    fun `authoritative id arriving late upgrades in place instead of restarting the resolve`() {
        val f = Fixture()

        // 宿主先给降级形态（实测 QQ 先给数字 songID）
        f.orchestrator.onTrackChanged(provisional())
        assertEquals(1, f.hookSource!!.startCount)

        // 约 30ms 后再给权威形态（songMID）
        f.now = 30L
        f.orchestrator.onTrackChanged(authoritative("004gZy7l1GYJ1Q"))

        assertEquals(
            1, f.hookSource.startCount,
            "拿到更权威的标识不得重启取词，否则在途结果会因代际过期被丢弃",
        )
        assertEquals(LyricOrchestrator.State.RESOLVING_HOOK, f.orchestrator.state)

        // 升级后，先前的会话回投仍然有效
        f.hookSource.deliver(success())
        assertNotNull(f.listener.resolved)
    }

    @Test
    fun `upgrade still applies when the host rewrites the title mid track`() {
        val f = Fixture()

        // 某些宿主会把当前正在唱的歌词行写进 TITLE（椒盐音乐即如此），
        // 因此升级判据刻意不看标题，只认歌手与时长。
        f.orchestrator.onTrackChanged(provisional(title = "后会无期"))
        f.now = 40L
        f.orchestrator.onTrackChanged(
            authoritative("004gZy7l1GYJ1Q", title = "| 我们变成了世上最熟悉的陌生人 |"),
        )

        assertEquals(1, f.hookSource!!.startCount, "标题被宿主改写不应被误判为切歌")
        assertEquals(LyricOrchestrator.State.RESOLVING_HOOK, f.orchestrator.state)
    }

    @Test
    fun `upgrade is refused once a result is already active`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(provisional())
        f.hookSource!!.deliver(success())
        assertEquals(LyricOrchestrator.State.ACTIVE, f.orchestrator.state)

        // 出结果之后不再放宽：宁可多取一次，也不能漏判真实切歌
        f.now = 50L
        f.orchestrator.onTrackChanged(authoritative("NEXT", title = "Another Song"))

        assertEquals(2, f.hookSource.startCount)
    }

    @Test
    fun `upgrade expires outside the provisional grace window`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(provisional())

        // 超出宽限窗口后才到来的权威标识一律按换歌处理
        f.now = 60_000L
        f.orchestrator.onTrackChanged(authoritative("LATER", title = "Another Song"))

        assertEquals(2, f.hookSource!!.startCount)
    }

    @Test
    fun `upgrade is refused when the artist or duration disagrees`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(provisional(artist = "歌手甲", duration = 200_000L))
        f.now = 10L
        f.orchestrator.onTrackChanged(
            authoritative("OTHER", artist = "歌手乙", duration = 200_000L),
        )

        assertEquals(2, f.hookSource!!.startCount, "歌手不符必须按换歌处理")
    }

    @Test
    fun `authoritative to authoritative change is never treated as an upgrade`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(authoritative("A"))
        f.now = 10L
        f.orchestrator.onTrackChanged(authoritative("B"))

        assertEquals(2, f.hookSource!!.startCount)
    }

    @Test
    fun `metadata churn on the same track never restarts a resolve even after no lyrics`() {
        val f = Fixture()

        f.orchestrator.onTrackChanged(track("A"))
        f.scheduler.fireAll()                       // Hook 超时 → 转网络
        f.apiSource!!.deliver(LyricOutcome.NoLyrics("none"))
        assertEquals(LyricOrchestrator.State.NO_LYRICS, f.orchestrator.state)

        // 宿主高频改写元数据（椒盐开启蓝牙歌词后会把当前歌词行写进 TITLE）。
        // 稳定标识没变，就绝不能重启取词——否则会形成
        // "事件 → 取词 → 无歌词 → 事件" 的死循环，每次都下发一次纯音乐占位。
        repeat(5) { index ->
            f.orchestrator.onTrackChanged(track("A", title = "第 $index 句歌词"))
        }

        assertEquals(1, f.hookSource!!.startCount, "同一稳定标识不得重启 Hook 取词")
        assertEquals(1, f.apiSource!!.startCount, "同一稳定标识不得重启网络取词")
    }

    @Test
    fun `a source delivering synchronously still reports states in order`() {
        // 宿主侧存在"按稳定标识暂存、会话建立后补投"的来源，它在 start() 里**同步**回投。
        // 若状态迁移在来源启动之后才发出，监听者会先看到 ACTIVE、再看到过期的
        // RESOLVING_HOOK——内部 state 是对的，但对外回报的顺序是错的。
        val listener = RecordingListener()
        val synchronous = object : LyricSource {
            override val sourceName: String = "sync"

            override fun start(identity: TrackIdentity, session: LyricSource.Session) {
                session.deliver(success())
            }

            override fun cancel() = Unit
        }

        LyricOrchestrator(
            synchronous, null, FakeScheduler(), listener,
        ).onTrackChanged(TrackIdentity("qq", "qq:A", "Song", "Artist", 200_000L))

        assertEquals(
            listOf(LyricOrchestrator.State.RESOLVING_HOOK, LyricOrchestrator.State.ACTIVE),
            listener.transitions,
            "状态回报必须按发生顺序，不得让 ACTIVE 之后又出现 RESOLVING_HOOK",
        )
        assertNotNull(listener.resolved)
    }
}
