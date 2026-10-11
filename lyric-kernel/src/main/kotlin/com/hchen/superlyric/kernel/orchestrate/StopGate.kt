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
 * 播放信号：调用方把宿主的状态常量映射到此处
 *
 * 映射只有一份，在 `:app` 的 `PlaybackSignals`。内核不认识 `android.media.session.PlaybackState`。
 */
enum class PlaybackSignal {
    /** 正在播放 */
    PLAYING,

    /** 暂停。**不是**终止：用户随时可能继续播 */
    PAUSED,

    /** 缓冲、快进、跳转等一切仍在播放流程中的状态 */
    ACTIVE,

    /** 确定已停止 */
    STOPPED,

    /** 因错误而终止 */
    ERROR,

    /**
     * 未知：宿主尚未设置过播放状态
     *
     * 取值语义就是"不知道"，因此**既不排期也不撤销**。把"不知道"当成"已停止"，
     * 与把一次失败的取词当成"平台确定没有歌词"是同一种错误：都会把正常状态判成终态，
     * 而且判错了还会**先清空数据**，等到它恢复正常时已经没有东西可恢复了
     */
    UNKNOWN,

    /** 会话已被销毁：播放源本身不存在了。这是**确定**信号，不是"不知道" */
    SESSION_GONE,
}

/**
 * 停止判定的统一状态机
 *
 * ## 为什么必须有它
 * "宿主是不是真的停了"这件事被**两个进程各自判断**过，而两份实现在最要紧的一格上相反：
 * 宿主进程内的 Provider 把 `STATE_NONE` 当作"没停"（去撤销待确认的停止），
 * system_server 一侧则把它当作"停了"（开始排期）。同一个信号，两个结论。
 *
 * 分叉的根源是"不知道"被当成了证据。因此本状态机把 `UNKNOWN` 定为**中性**。
 *
 * ## 统一的是判定，不是观察
 * 两侧**各持一个实例、各喂各自的观察**，观察点不合并：Provider 看的是宿主进程内
 * `MediaSession.setPlaybackState` 的调用，system_server 看的是 MediaController 回调，
 * 两者覆盖面不同，合并会丢掉其中一侧独有的信号。
 *
 * ## 为什么停止要"确认"而不是立即生效
 * 宿主在过渡期会**瞬发**一整串播放状态：实测 QQ 每次切歌都是
 * `NONE → NONE → STOPPED → NONE → BUFFERING → PLAYING`（全程约 140ms），
 * 网易云在切歌前后也会连发多次停止状态。而"停止"是终态操作——它清空当前音轨、
 * 推进 epoch、并向消费方下发停止包。瞬发状态若被当真，歌词会莫名闪空，
 * 且数据已被清掉，恢复播放时补发不出任何内容。
 *
 * 这**不是无差别去抖**：只有终态信号才需要确认，且窗口很短。
 *
 * ## 线程安全
 * 可被多线程并发调用（服务端一侧的撤销来自任意 Binder 线程，排期来自 system_server 主线程）。
 * 判定的原子性由内部锁保证；已排期任务过期后由令牌作废，不需要调度器支持取消。
 *
 * @param scheduler       排期器；实现方**不必**支持取消
 * @param onConfirmed     窗口内无人撤销时回调；在**调度线程**上执行，不得抛异常
 * @param confirmWindowMs 确认窗口
 *
 * @author 焕晨HChen
 */
class StopGate(
    private val scheduler: Scheduler,
    private val onConfirmed: Runnable,
    private val confirmWindowMs: Long = DEFAULT_CONFIRM_WINDOW_MS,
) {

    /**
     * 排期器
     *
     * 用 [Runnable] 而非 Kotlin 函数类型：本类的另一个调用方是 Java 写的 system_server
     * 服务，`Function0<Unit>` 在 Java 侧要显式 `return Unit.INSTANCE`，不必要地难看
     */
    fun interface Scheduler {
        /** 在 [delayMs] 之后执行 [task] */
        fun schedule(delayMs: Long, task: Runnable)
    }

    /** 一次判定的结果。两侧都应据此留痕——"拦下"与"放行"都要看得见 */
    enum class Decision {
        /** 已排期一次待确认的停止 */
        ARMED,

        /** 撤销了一次待确认的停止 */
        CANCELLED,

        /** 本信号不改变任何状态；含"已有停止在案，不重排" */
        IGNORED,
    }

    private val lock = Any()
    private var token = 0L
    private var armed = false

    /** 当前是否有一次待确认的停止。仅用于诊断留痕，不参与判定 */
    val isPending: Boolean
        get() = synchronized(lock) { armed }

    /**
     * 收到一个播放信号
     *
     * @return 本次判定
     */
    fun onSignal(signal: PlaybackSignal): Decision = when (signal) {
        PlaybackSignal.STOPPED,
        PlaybackSignal.ERROR,
        PlaybackSignal.SESSION_GONE -> arm()

        PlaybackSignal.UNKNOWN -> Decision.IGNORED

        PlaybackSignal.PLAYING,
        PlaybackSignal.PAUSED,
        PlaybackSignal.ACTIVE -> cancel()
    }

    /**
     * 收到"播放仍在继续"的旁证：可用的曲目元数据、新到的歌词、切歌
     *
     * @return 本次判定
     */
    fun onEvidence(): Decision = cancel()

    private fun arm(): Decision {
        val current = synchronized(lock) {
            // 已排期就不再重排：宿主瞬发的是终态与其它状态**交替**的一串，
            // 每收到一个终态就重排窗口的话，一串持续的状态能把确认无限期往后推，
            // 真停止反而永远不生效
            if (armed) return Decision.IGNORED
            armed = true
            ++token
        }

        scheduler.schedule(confirmWindowMs) {
            if (takeConfirmation(current)) onConfirmed.run()
        }
        return Decision.ARMED
    }

    private fun cancel(): Decision = synchronized(lock) {
        if (!armed) return@synchronized Decision.IGNORED
        armed = false
        token++
        Decision.CANCELLED
    }

    /** 令牌仍对得上就把这次排期消费掉；对不上说明期间已被撤销或被新的一次取代 */
    private fun takeConfirmation(current: Long): Boolean = synchronized(lock) {
        if (current != token) return@synchronized false
        armed = false
        true
    }

    companion object {
        /**
         * 停止确认窗口的默认值（毫秒）
         *
         * 宿主瞬发的过渡状态串通常在一两百毫秒内就被后续状态取代（实测 QQ 切歌全程约 140ms），
         * 而真正的停止晚这么一点生效对用户不可见。唯一一处定义，两侧共用。
         */
        const val DEFAULT_CONFIRM_WINDOW_MS = 400L
    }
}
