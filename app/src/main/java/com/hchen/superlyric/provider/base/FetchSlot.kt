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

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次取词请求的执行槽位
 *
 * 取词是同步网络调用（默认 connect 4s / read 6s / call 12s，Spotify 使用更长时限）。
 * 若每个来源各用一个单线程执行器，切歌时新曲目的 `start()` 会排在上一首在途请求之后；
 * 单个慢响应最长可阻塞后续切歌一个读超时。编排器按 epoch 丢弃过期结果，因此这里允许
 * 每首歌并发执行。线程池固定 6 线程、队列无界，线程数有上界，单次任务受 callTimeout 约束。
 * 解析宿主报文或本地文件的来源仍走各自单线程，以保持同一曲目的处理顺序。
 *
 * 令牌用于判定请求归属。若这些来源共用一个 `cancelled` 布尔，新的 `start()` 会重置它，
 * 使上一首在途请求的过期结果被交付。`LyricSource.cancel` 约定取消后不再交付，
 * 过期令牌可在本地预筛掉这类投递。
 *
 * 令牌只做预筛，权威判定在消费方。`isCurrent(token)` 与随后的 `deliver(outcome)` 之间
 * 存在窗口，[open] / [invalidate] 可能落入其中；本地检查只过滤明显过期的投递，
 * 最终由编排器的 epoch 丢弃。不要为此在交付路径加锁。
 */
class FetchSlot(private val executor: Executor = FETCH_POOL) {

    private val sequence = AtomicLong()
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    /** 开始一次新请求，并使已有请求失效 */
    fun open(): Long = sequence.incrementAndGet()

    /** 作废当前请求（对应 `LyricSource.cancel`） */
    fun invalidate() {
        sequence.incrementAndGet()
        // 取消在途 Call，而不是只丢弃结果：过期代际不再继续跑 2 URL × 2 profile。
        for (call in calls) {
            runCatching { call.cancel() }
        }
        calls.clear()
    }

    /** 登记一次 OkHttp 调用，使其可被 [invalidate] 取消 */
    fun <T> withCall(call: Call, block: () -> T): T {
        calls.add(call)
        try {
            return block()
        } finally {
            calls.remove(call)
        }
    }

    /** [token] 是否仍是最新的一次请求 */
    fun isCurrent(token: Long): Boolean = sequence.get() == token

    /** 在共享取词池上执行一次取词 */
    fun execute(task: Runnable) {
        executor.execute(task)
    }

    private companion object {
        /**
         * 所有来源共用的取词线程池
         *
         * 固定 6 线程：快速连切时每首歌各占一条线程跑完整条网络链（KuGou 最坏 5×12s），
         * 线程与 FD 会短时堆积。队列仍无界，线程数有上界；取词本身有 HTTP 超时，
         * 任务不会永久占用。
         */
        val FETCH_POOL: Executor = Executors.newFixedThreadPool(6) { runnable ->
            Thread(runnable, "SuperLyric-Fetch").apply { isDaemon = true }
        }
    }
}
