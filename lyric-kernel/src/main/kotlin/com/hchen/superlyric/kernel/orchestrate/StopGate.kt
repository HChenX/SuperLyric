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
 * 映射只有一份，在 `:app` 的 `PlaybackStates.toSignal`。内核不认识 `android.media.session.PlaybackState`。
 */
enum class PlaybackSignal {
    /** 正在播放 */
    PLAYING,

    /** 暂停；不是终止，播放可能继续 */
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
     * 取值语义就是"不知道"，因此既不排期也不撤销。把"不知道"当成"已停止"，
     * 与把一次失败的取词当成"平台确定没有歌词"是同一种错误：都会把正常状态判成终态，
     * 且判错会先清空数据，状态恢复后内容已不可恢复
     */
    UNKNOWN,

    /** 会话已被销毁：播放源本身不存在。这是确定信号，不是"不知道" */
    SESSION_GONE,
}

/**
 * 停止判定的统一状态机
 *
 * Provider 与服务各自观察，但共用这里的判定；`UNKNOWN` 保持中性，避免把"不知道"
 * 当成停止。终态信号需要短窗口确认，防止过渡状态清空正在播放的歌词。
 * 判定线程安全；过期任务由令牌作废，不要求调度器支持取消。
 */
class StopGate(
    private val scheduler: Scheduler,
    private val onConfirmed: Runnable,
    private val confirmWindowMs: Long = DEFAULT_CONFIRM_WINDOW_MS,
) {

    /**
     * 排期器
     *
     * 使用 [Runnable] 而非 Kotlin 函数类型：本类的另一个调用方是 Java 写的 system_server
     * 服务，`Function0<Unit>` 在 Java 侧需要显式 `return Unit.INSTANCE`
     */
    fun interface Scheduler {
        /** 在 [delayMs] 之后执行 [task] */
        fun schedule(delayMs: Long, task: Runnable)
    }

    /** 一次判定的结果，两侧都应记录判定，包括拦截与放行 */
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

    /** 当前是否有一次待确认的停止；仅用于诊断留痕，不参与判定 */
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
            // 已排期就不再重排：宿主会瞬发终态与其它状态交替的一串，
            // 每收到一个终态都重排窗口会把确认无限期往后推，真正的停止一直不生效
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

    /** 令牌仍对得上就消费本次排期；对不上说明期间已被撤销或被新的排期取代 */
    private fun takeConfirmation(current: Long): Boolean = synchronized(lock) {
        if (current != token) return@synchronized false
        armed = false
        true
    }

    companion object {
        /**
         * 停止确认窗口的默认值（毫秒）
         *
         * 宿主瞬发的过渡状态串通常在一两百毫秒内被后续状态取代（QQ 切歌全程约 140ms），
         * 停止晚这么一点生效对用户不可见。唯一一处定义，两侧共用。
         */
        const val DEFAULT_CONFIRM_WINDOW_MS = 400L
    }
}
