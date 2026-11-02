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
 * 它补的是 [StopGate] 补不了的那一半：StopGate 只能保证"排期时没有人撤销"，
 * 保证不了"此后也没有人发布"。停止确认跑在调度线程上，而证据（新歌词、曲目元数据、
 * 其它播放状态）来自宿主的回调线程或 system_server。确认消费令牌之后到达的撤销会读到
 * armed == false 而放行，"确认成立之后到达的证据"因此无人拦截，刚发布的内容会被清掉。
 * 排期时调用 [snapshot]、确认前调用 [isStillValid]：期间记过任何一笔证据，
 * 本次确认就不成立。
 */
class StopEvidence {

    private val lock = Any()
    private var evidence = 0L
    private var armedAt = 0L

    /** 记一笔"播放仍在继续"的证据 */
    fun record() {
        synchronized(lock) { evidence++ }
    }

    /** 记录本次停止排期时刻的计数 */
    fun snapshot() {
        synchronized(lock) { armedAt = evidence }
    }

    /**
     * 本次确认是否仍然成立
     *
     * @return 自 [snapshot] 之后没有记过任何证据时为 `true`
     */
    fun isStillValid(): Boolean = synchronized(lock) { evidence == armedAt }
}
