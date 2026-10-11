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
package com.hchen.superlyric.provider.base

import com.hchen.superlyric.kernel.orchestrate.Cancellable
import com.hchen.superlyric.kernel.orchestrate.Scheduler
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 基于单线程调度器的 [Scheduler] 实现。
 *
 * 编排器的超时与退避任务量极小（每首歌至多几次），单线程足够，
 * 且能保证同一首歌内的定时任务按序执行。
 */
class ExecutorScheduler(threadName: String = "SuperLyric-Scheduler") : Scheduler {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val future: ScheduledFuture<*> = executor.schedule(
            {
                runCatching { task() }
            },
            delayMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
        return Cancellable { future.cancel(false) }
    }

    /** 释放线程；宿主进程退出时无需显式调用，此处仅供测试与显式清理使用。 */
    fun shutdown() {
        executor.shutdownNow()
    }
}
