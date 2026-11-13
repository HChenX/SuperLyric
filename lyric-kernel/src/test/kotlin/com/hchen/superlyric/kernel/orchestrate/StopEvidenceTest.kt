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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 停止确认的证据计数
 *
 * 这些用例固定 StopGate 令牌覆盖不到的那一半：确认成立之后到达的证据只能靠计数拦截。
 * 宿主进程内的 Provider 与 system_server 内的服务共用本类。
 */
class StopEvidenceTest {

    @Test
    fun `a confirmation with no evidence in between still holds`() {
        val evidence = StopEvidence()

        evidence.record()
        evidence.snapshot()

        assertTrue(evidence.isStillValid())
    }

    @Test
    fun `evidence arriving after the snapshot invalidates the confirmation`() {
        // 确认消费 StopGate 令牌之后到达的证据只能靠计数拦截，
        // 否则刚发布的歌词会被随后的停止清空
        val evidence = StopEvidence()

        evidence.snapshot()
        evidence.record()

        assertFalse(evidence.isStillValid())
    }

    @Test
    fun `a later snapshot re-arms a new confirmation`() {
        val evidence = StopEvidence()

        evidence.snapshot()
        evidence.record()
        assertFalse(evidence.isStillValid())

        // 每次排期重新取快照，新的确认只比较本次快照之后的证据
        evidence.snapshot()
        assertTrue(evidence.isStillValid())
    }

    @Test
    fun `evidence recorded before the snapshot never invalidates it`() {
        val evidence = StopEvidence()

        repeat(5) { evidence.record() }
        evidence.snapshot()

        assertTrue(evidence.isStillValid(), "排期之前的历史证据与这次确认无关")
    }

    @Test
    fun `confirmation executes the stop action only while the confirmation holds`() {
        val evidence = StopEvidence()
        evidence.snapshot()

        val actions = AtomicInteger()
        assertTrue(evidence.confirmIfStillValid { actions.incrementAndGet() })
        assertEquals(1, actions.get())

        evidence.record()
        assertFalse(evidence.confirmIfStillValid { actions.incrementAndGet() })
        assertEquals(1, actions.get(), "失效的确认不得执行停止动作")
    }

    @Test
    fun `evidence arriving during the confirmation cannot overtake the stop action`() {
        val evidence = StopEvidence()
        evidence.snapshot()

        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val actionFinished = CountDownLatch(1)
        val confirmation = Thread {
            evidence.confirmIfStillValid {
                actionStarted.countDown()
                releaseAction.await()
            }
            actionFinished.countDown()
        }
        confirmation.start()
        assertTrue(actionStarted.await(2, TimeUnit.SECONDS))

        val recordFinished = CountDownLatch(1)
        val recorder = Thread {
            evidence.record()
            recordFinished.countDown()
        }
        recorder.start()

        // 动作持有证据锁时 record 必须等待；否则判定与动作不在同一临界区
        assertFalse(recordFinished.await(200, TimeUnit.MILLISECONDS))

        releaseAction.countDown()
        assertTrue(actionFinished.await(2, TimeUnit.SECONDS))
        assertTrue(recordFinished.await(2, TimeUnit.SECONDS))

        // 证据记账发生在确认之后，排在后面的确认必须被它撤销
        assertFalse(evidence.isStillValid())
    }
}
