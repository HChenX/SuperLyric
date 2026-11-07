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
package com.hchen.superlyric.provider.base

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 取词执行的代际槽位；网络取词共享一个有界线程池，过期结果由编排器丢弃。
 */
class FetchSlot(private val executor: Executor = FETCH_POOL) {

    private val sequence = AtomicLong()
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    /** 开始新请求并使旧请求失效。 */
    fun open(): Long = sequence.incrementAndGet()

    /** 取消在途请求。 */
    fun invalidate() {
        sequence.incrementAndGet()
        // 取消在途 Call，而不是只丢弃结果：过期代际不再继续跑 2 URL × 2 profile。
        for (call in calls) {
            runCatching { call.cancel() }
        }
        calls.clear()
    }

    /** 登记可取消的 OkHttp 调用。 */
    fun <T> withCall(call: Call, block: () -> T): T {
        calls.add(call)
        try {
            return block()
        } finally {
            calls.remove(call)
        }
    }

    /** 是否为最新代际。 */
    fun isCurrent(token: Long): Boolean = sequence.get() == token

    /** 在共享池执行。 */
    fun execute(task: Runnable) {
        executor.execute(task)
    }

    /** 开启代际并在共享池执行；异常归类为 [LyricOutcome.TransientFailure]。 */
    fun launch(
        tag: String,
        session: LyricSource.Session,
        resolve: (Long) -> LyricOutcome,
    ) {
        val token = open()
        execute {
            if (!isCurrent(token)) return@execute
            val outcome = runCatching { resolve(token) }.getOrElse {
                AndroidLog.logW(tag, "Fetch failed: " + it.message)
                LyricOutcome.TransientFailure(it)
            }
            if (isCurrent(token)) session.deliver(outcome)
        }
    }

    private companion object {
        /** 固定 6 线程，防止快速连切时无限建线程；取词受 HTTP 超时约束。 */
        val FETCH_POOL: Executor = Executors.newFixedThreadPool(6) { runnable ->
            Thread(runnable, "SuperLyric-Fetch").apply { isDaemon = true }
        }
    }
}
