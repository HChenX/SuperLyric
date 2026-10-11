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
package com.hchen.superlyric.kernel.orchestrate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 停止判定状态机。
 *
 * 这几条不是随手写的样例，而是这条策略的定义——两侧（宿主进程内的 Provider 与
 * system_server 内的服务）都按它执行。尤其是 `UNKNOWN` 那一条：此前两侧对它
 * 判得**相反**，一侧当成"没停"、一侧当成"停了"。
 */
class StopGateTest {

    private val scheduler = FakeScheduler()
    private var confirmed = 0
    private val gate = StopGate(
        scheduler = scheduler,
        onConfirmed = Runnable { confirmed++ },
        confirmWindowMs = 400L,
    )

    @Test
    fun `a terminal signal confirms once the window elapses`() {
        assertEquals(StopGate.Decision.ARMED, gate.onSignal(PlaybackSignal.STOPPED))
        assertTrue(gate.isPending)
        assertEquals(0, confirmed, "窗口内不得提前生效")

        scheduler.fireAll()

        assertEquals(1, confirmed)
        assertFalse(gate.isPending)
    }

    @Test
    fun `the configured window is handed to the scheduler`() {
        gate.onSignal(PlaybackSignal.STOPPED)

        assertEquals(listOf(400L), scheduler.delays)
    }

    @Test
    fun `error and session gone are terminal too`() {
        assertEquals(StopGate.Decision.ARMED, gate.onSignal(PlaybackSignal.ERROR))
        scheduler.fireAll()
        assertEquals(1, confirmed)

        assertEquals(StopGate.Decision.ARMED, gate.onSignal(PlaybackSignal.SESSION_GONE))
        scheduler.fireAll()
        assertEquals(2, confirmed)
    }

    @Test
    fun `an unknown signal neither arms nor cancels`() {
        // NONE 的字面含义是"宿主尚未设置过播放状态"。把它当成"已停止"，与把一次失败的
        // 取词当成"平台确定没有歌词"是同一种错误
        assertEquals(StopGate.Decision.IGNORED, gate.onSignal(PlaybackSignal.UNKNOWN))
        assertFalse(gate.isPending, "不知道不能启动排期")
        assertTrue(scheduler.delays.isEmpty(), "不该排任何东西")

        // 有停止悬着时，它也不能撤销——撤销要有"播放仍在继续"的正面证据
        gate.onSignal(PlaybackSignal.STOPPED)
        assertEquals(StopGate.Decision.IGNORED, gate.onSignal(PlaybackSignal.UNKNOWN))
        assertTrue(gate.isPending)

        scheduler.fireAll()
        assertEquals(1, confirmed)
    }

    @Test
    fun `an active signal cancels a pending stop`() {
        gate.onSignal(PlaybackSignal.STOPPED)

        assertEquals(StopGate.Decision.CANCELLED, gate.onSignal(PlaybackSignal.PLAYING))
        assertFalse(gate.isPending)

        scheduler.fireAll()
        assertEquals(0, confirmed, "过期的排期不得生效")
    }

    @Test
    fun `pause and buffering cancel as well`() {
        gate.onSignal(PlaybackSignal.STOPPED)
        assertEquals(StopGate.Decision.CANCELLED, gate.onSignal(PlaybackSignal.PAUSED))

        gate.onSignal(PlaybackSignal.STOPPED)
        assertEquals(StopGate.Decision.CANCELLED, gate.onSignal(PlaybackSignal.ACTIVE))
    }

    @Test
    fun `evidence cancels a pending stop`() {
        // 通行的曲目元数据、新到的歌词、切歌都是"播放仍在继续"的证据
        gate.onSignal(PlaybackSignal.STOPPED)

        assertEquals(StopGate.Decision.CANCELLED, gate.onEvidence())
        scheduler.fireAll()
        assertEquals(0, confirmed)
    }

    @Test
    fun `cancelling with nothing pending is a no-op`() {
        assertEquals(StopGate.Decision.IGNORED, gate.onEvidence())
        assertEquals(StopGate.Decision.IGNORED, gate.onSignal(PlaybackSignal.PLAYING))
    }

    @Test
    fun `a repeated terminal signal does not push the window back`() {
        // 宿主瞬发的是终态与其它状态**交替**的一串。每收到一个终态就重排窗口的话，
        // 一串持续的状态能把确认无限期往后推，真停止反而永远不生效
        assertEquals(StopGate.Decision.ARMED, gate.onSignal(PlaybackSignal.STOPPED))
        assertEquals(StopGate.Decision.IGNORED, gate.onSignal(PlaybackSignal.STOPPED))
        assertEquals(StopGate.Decision.IGNORED, gate.onSignal(PlaybackSignal.ERROR))

        assertEquals(1, scheduler.delays.size, "只该排一次")
    }

    @Test
    fun `only the surviving schedule fires when a stop is re-armed`() {
        gate.onSignal(PlaybackSignal.STOPPED)
        gate.onSignal(PlaybackSignal.PLAYING)
        gate.onSignal(PlaybackSignal.STOPPED)

        // 两个任务都在队列里，先到的那个已经作废
        scheduler.fireAll()

        assertEquals(1, confirmed)
    }

    @Test
    fun `a new stop can be confirmed after a previous one already was`() {
        gate.onSignal(PlaybackSignal.STOPPED)
        scheduler.fireAll()

        assertEquals(StopGate.Decision.ARMED, gate.onSignal(PlaybackSignal.STOPPED))
        scheduler.fireAll()

        assertEquals(2, confirmed)
    }

    /** 手动触发排期任务的假调度器：不发线程、不看时间，由用例决定何时到期 */
    private class FakeScheduler : StopGate.Scheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<Runnable>()

        override fun schedule(delayMs: Long, task: Runnable) {
            delays += delayMs
            tasks += task
        }

        /** 触发**全部**已排期任务，含已被撤销的过期任务 */
        fun fireAll() {
            val pending = tasks.toList()
            tasks.clear()
            pending.forEach(Runnable::run)
        }
    }
}
