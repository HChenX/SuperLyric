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

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次取词的执行槽位
 *
 * ## 为什么各来源不能各用一个单线程 executor
 * 取词是**同步网络调用**（connect 4s / read 6s / call 12s，Spotify 更大）。单个来源用一个
 * 单线程 executor 串行执行的话，切歌时新曲目的 `start()` 会排在上一首**在途**请求之后：
 * 一个慢响应能把后面每一次切歌都堵住，表现为"切歌后歌词迟迟不来"，最长可达一个读超时。
 *
 * 编排器本来就按 epoch 丢弃过期结果，因此这里允许并发——**每首歌各跑各的线程**。
 * 池用无界 cached 池：任务的产生者是"用户切歌"，单次任务时长又有 callTimeout 兜底，
 * 并发度天然只有个位数，再设上限反而会在极端情况下重新引入排队。
 *
 * 只做**解析**的来源（宿主的报文、本地歌词文件）不适用这里：它们本来就是毫秒级，
 * 单线程还能顺带保证同曲目的处理顺序。
 *
 * ## 为什么需要令牌
 * "这次交付还算不算数"必须按**请求**判断。此前这些来源共用一个 `cancelled` 布尔：
 * 新的 `start()` 会把它重置为 false，于是一首旧曲目在途的请求会把自己**过期**的结果
 * 交付出去。编排器虽然按 epoch 丢弃，但 `LyricSource.cancel` 的约定是"取消后不再交付"，
 * 而且这条路径会让取消之后的线程继续做无用功。
 *
 * ## 令牌只做预筛，权威判定在消费方
 * `isCurrent(token)` 与随后的 `deliver(outcome)` 之间有窗口，[open] / [invalidate] 可以落在
 * 其中任意一刻——**本地无法消除**，除非把交付本身放进锁里（那会让投递在持锁状态下回调编排器，
 * 得不偿失）。因此约定是：本地这次检查只是省掉明显过期的投递，真正"迟到投递无害"的保证在
 * 消费方——编排器按 epoch 丢弃过期结果（`Session.deliver` 内部带着那次 `start` 的 epoch）。
 * 谁要收紧这条，只能去改 epoch 那一层，别在这里加锁。
 */
class FetchSlot(private val executor: Executor = FETCH_POOL) {

    private val sequence = AtomicLong()

    /** 开始一次新请求，并作废此前所有请求。 */
    fun open(): Long = sequence.incrementAndGet()

    /** 作废当前请求（对应 `LyricSource.cancel`）。 */
    fun invalidate() {
        sequence.incrementAndGet()
    }

    /** [token] 是否仍是最新的一次请求。 */
    fun isCurrent(token: Long): Boolean = sequence.get() == token

    /** 在共享取词池上执行一次取词。 */
    fun execute(task: Runnable) {
        executor.execute(task)
    }

    private companion object {
        /**
         * 所有来源共用的取词线程池
         *
         * 线程空闲 60 秒即回收，因此稳态下没有常驻线程；每个任务都有 HTTP 超时兜底，
         * 不会永久占用线程
         */
        val FETCH_POOL: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "SuperLyric-Fetch").apply { isDaemon = true }
        }
    }
}
