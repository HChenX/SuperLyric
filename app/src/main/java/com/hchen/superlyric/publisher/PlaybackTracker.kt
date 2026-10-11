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
package com.hchen.superlyric.publisher

import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.bridge.LyricDocAdapter
import com.hchen.superlyric.kernel.clock.PlaybackClock
import com.hchen.superlyric.kernel.clock.PlaybackStatus
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyricapi.SuperLyricData

/**
 * 系统服务端的播放时钟与跨行推进器
 *
 * ## 职责归属
 * 按项目核心理念「音乐软件仅负责发布完整歌词，系统框架负责分发」，
 * **这是整条链路上唯一的进度来源**。宿主侧不发布任何进度包。
 *
 * ## 设计要点
 * - **漂移**：进度以宿主自报的 `lastPositionUpdateTime` 为锚（见 [PlaybackClock]）。
 *   若改以"收到回调的时刻"为锚，漂移会恒等于快照年龄。
 * - **速率为 0**：PLAYING 而速率为 0 时由 [PlaybackClock] 视为冻结；
 *   否则时钟会表现为"运行却停住"。
 * - **重播与拖回开头**：位置回退是合法事件（seek / 重播），据此重新锚定并重置行索引即可，
 *   **不做"虚假 0ms"抑制**——抑制会让合法的重播把时钟留在曲尾。
 * - **竞态**：**所有**对行索引与时钟的修改都收敛到内部 HandlerThread 上执行。
 *
 * ## 时间源
 * 时钟必须与 `PlaybackState.getLastPositionUpdateTime()` 同基，因此显式使用
 * `SystemClock.elapsedRealtime()`——不能用内核的默认时间源（纳秒时钟），
 * 否则两者相减毫无意义。
 *
 * @author 焕晨HChen
 */
class PlaybackTracker(threadName: String) {

    /** 进度推进回调；在追踪线程上调用。 */
    fun interface OnProgressAdvanceListener {
        fun onProgressAdvance(index: Int, progress: SuperLyricData)
    }

    private val thread = HandlerThread(threadName).apply { start() }
    private val handler = Handler(thread.looper)

    private val clock = PlaybackClock { SystemClock.elapsedRealtime() }

    @Volatile
    private var listener: OnProgressAdvanceListener? = null

    /** 以下字段只在追踪线程上访问。 */
    private var doc: LyricDoc? = null
    private var shownIndex = -1
    private var running = false
    private var loopToken = 0L

    fun setOnProgressAdvanceListener(listener: OnProgressAdvanceListener?) {
        this.listener = listener
    }

    /**
     * 绑定当前曲目的全量歌词。传入 null 表示清空。
     *
     * <p>换歌时必须调用本方法；它会重置行索引并重新开始推演。
     */
    fun setLyrics(data: SuperLyricData?) {
        val converted = LyricDocAdapter.toDoc(data).takeIf { !it.isEmpty }
        handler.post {
            doc = converted
            shownIndex = -1
            if (converted == null) {
                clock.reset()
                stopLoop()
            } else {
                clock.durationMs = converted.durationMs
            }
        }
    }

    /**
     * 同步宿主上报的播放状态。
     *
     * <p>位置回退（seek / 重播）会被识别为新锚点并立即重推行索引，
     * **不做任何"虚假 0ms"抑制**。
     */
    fun onPlaybackStateChanged(state: PlaybackState?) {
        if (state == null) return

        val position = state.position
        val speed = state.playbackSpeed
        val updateTime = state.lastPositionUpdateTime
        val status = toStatus(state.state)

        handler.post {
            val previous = clock.positionAt(SystemClock.elapsedRealtime())
            val jumpedBackwards = position < previous - JUMP_BACK_TOLERANCE_MS

            clock.sync(status, position, speed, updateTime)

            if (jumpedBackwards) {
                // 位置回退是合法事件：重置行索引，让下一行立即重推
                shownIndex = -1
            }

            dispatchIfNeeded(clock.positionAt(SystemClock.elapsedRealtime()))
            updateLoop()
        }
    }

    /**
     * 外部直接同步播放位置（例如服务端从系统媒体会话轮询到的值）。
     */
    fun onPositionSynced(positionMs: Long) {
        handler.post {
            val now = SystemClock.elapsedRealtime()
            if (positionMs < clock.positionAt(now) - JUMP_BACK_TOLERANCE_MS) {
                shownIndex = -1
            }
            clock.seekTo(positionMs, now)
            dispatchIfNeeded(positionMs)
            updateLoop()
        }
    }

    /** 停止追踪并清空全部状态。 */
    fun stop() {
        handler.post {
            running = false
            loopToken++
            shownIndex = -1
            doc = null
            clock.reset()
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun updateLoop() {
        val shouldRun = clock.currentStatus == PlaybackStatus.PLAYING && doc != null
        if (shouldRun && !running) {
            running = true
            val token = ++loopToken
            handler.post { runLoop(token) }
        } else if (!shouldRun) {
            running = false
            loopToken++
        }
    }

    private fun stopLoop() {
        running = false
        loopToken++
    }

    private fun runLoop(token: Long) {
        if (!running || token != loopToken) return

        dispatchIfNeeded(clock.positionAt(SystemClock.elapsedRealtime()))

        if (running && token == loopToken) {
            handler.postDelayed({ runLoop(token) }, delayUntilNextLine())
        }
    }

    /**
     * 睡到下一行的起点，而不是固定间隔空转
     *
     * 行边界是**已知的**，按它排程既准又省：一首 4 分钟的歌由约 4800 次唤醒降到与行数同量级。
     * 到末行后退化为固定间隔——此时还可能变的只剩位置跳转，而跳转有自己的入口
     * （[onPositionSynced] / [onPlaybackStateChanged] 都会立即重推）。
     *
     * 只能在追踪线程上调用。
     */
    private fun delayUntilNextLine(): Long {
        val lines = doc?.lines ?: return LOOP_INTERVAL_MS
        val position = clock.positionAt(SystemClock.elapsedRealtime())
        val index = PlaybackClock.lineIndexAt(lines, position)
        val nextStart = lines.getOrNull(index + 1)?.startMs ?: return LOOP_INTERVAL_MS
        return (nextStart - position).coerceIn(LOOP_INTERVAL_MS, MAX_LOOP_SLEEP_MS)
    }

    /**
     * 若当前位置落在新的行上，则构造极轻量的增量包并回调。
     *
     * <p>本方法**只能**在追踪线程上调用。
     */
    private fun dispatchIfNeeded(positionMs: Long) {
        val current = doc ?: return
        if (current.isEmpty) return

        val index = PlaybackClock.lineIndexAt(current.lines, positionMs)
        if (index < 0 || index == shownIndex) return
        shownIndex = index

        val payload = LyricDocAdapter.toLineData(current, index, positionMs) ?: return

        AndroidLog.logD(
            TAG,
            "Advance line [$index/${current.lines.size}] at ${positionMs}ms: " +
                "\"${current.lines[index].text}\""
        )
        listener?.onProgressAdvance(index, payload)
    }

    /**
     * `FAST_FORWARDING` / `REWINDING` / `SKIPPING_*` 属于"仍在播放、只是位置在动"，
     * 归为 PLAYING。把它们并进 `else` 会让时钟在快进/跳转期间冻结
     *
     * ## 这里把 `NONE` 归为 STOPPED，与 `PlaybackSignals` 把它判为 `UNKNOWN` 并不冲突
     * 两处回答的是不同的问题：这里决定"时钟要不要继续走"，那里决定"要不要清空并下发停止"。
     * 冻结是**可逆**的（宿主随后报任何状态都会重新锚定），而清空不可逆——所以同一个
     * "不知道"，在本层可以按停止处理，在停止判定里必须保持中性。**不要**为了"统一"把
     * 这里也改成中性：那会让时钟在宿主不再上报时一直往前爬
     */
    private fun toStatus(state: Int): PlaybackStatus = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> PlaybackStatus.PLAYING

        PlaybackState.STATE_PAUSED -> PlaybackStatus.PAUSED

        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> PlaybackStatus.BUFFERING

        else -> PlaybackStatus.STOPPED
    }

    private companion object {
        const val TAG = "PlaybackTracker"

        /** 按行边界排程时的最小时延；也用于"没有下一行"时的退化间隔 */
        const val LOOP_INTERVAL_MS = 50L

        /** 按行边界排程时的最大时延：行间隔再长也要定期醒来看一眼 */
        const val MAX_LOOP_SLEEP_MS = 1_000L

        /** 超过该幅度的位置回退才判定为 seek / 重播，避免抖动误判。 */
        const val JUMP_BACK_TOLERANCE_MS = 1_000L
    }
}
