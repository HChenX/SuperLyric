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

/**
 * "播放仍在继续"的证据计数
 *
 * ## 它补的是 [StopGate] 补不了的那一半
 * `StopGate` 只能保证"**排期时**没有人撤销"，保证不了"此后也没人发布"。停止确认跑在调度线程上，
 * 而证据（新歌词、曲目元数据、其它播放状态）来自宿主的回调线程或 system_server：
 *
 * ```
 * 确认线程：takeConfirmation() → 令牌已消费 → …… → 清空显示
 * 证据线程：                 ↑ 这里到达的撤销会读到 armed == false 而放行
 * ```
 *
 * 于是"确认成立之后到达的证据"没人拦得住，刚发布的内容会被清掉。因此排期时 [snapshot]、
 * 确认前 [isStillValid]：期间记过任何一笔证据，这次确认就不成立。
 *
 * ## 为什么按包/按来源各持一个实例
 * 计数属于"一次排期"，不属于曲目或会话：会话会被淘汰重建，计数跟着重建就会归零，
 * 与快照一比成了相等——判别力恰好归零。
 *
 * ## 线程安全
 * 内部一把锁，[record] / [snapshot] / [isStillValid] 都可并发调用。判定与记账之间不得插入
 * 别的工作，否则又会把窗口挪回来。
 *
 * @author 焕晨HChen
 */
class StopEvidence {

    private val lock = Any()
    private var evidence = 0L
    private var armedAt = 0L

    /** 记一笔"播放仍在继续"的证据。 */
    fun record() {
        synchronized(lock) { evidence++ }
    }

    /** 记下这次停止排期时刻的计数。 */
    fun snapshot() {
        synchronized(lock) { armedAt = evidence }
    }

    /**
     * 这次确认是否仍然成立
     *
     * @return 自 [snapshot] 之后没有记过任何证据时为 `true`
     */
    fun isStillValid(): Boolean = synchronized(lock) { evidence == armedAt }
}
