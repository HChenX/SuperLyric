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
import com.hchen.superlyric.kernel.clock.PlaybackClock
import com.hchen.superlyric.kernel.clock.PlaybackStatus
import com.hchen.superlyricapi.model.Lyric

/**
 * 系统服务端的播放时钟与跨行推进器
 *
 * 按「音乐软件仅负责发布完整歌词，系统框架负责分发」的边界，这是整条链路上唯一的进度
 * 来源，宿主侧不发布任何进度包。
 */
class PlaybackTracker @JvmOverloads constructor(
    threadName: String,
    private val timeSource: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /** 进度推进回调；在追踪线程上调用 */
    fun interface OnProgressAdvanceListener {
        fun onProgressAdvance(index: Int, positionMs: Long)
    }

    private val thread = HandlerThread(threadName).apply { start() }
    private val handler = Handler(thread.looper)

    private val clock = PlaybackClock { timeSource() }

    @Volatile
    private var listener: OnProgressAdvanceListener? = null

    /**
     * 当前曲目。必须 volatile：发布线程写入后，追踪线程与构造 State 帧的 binder 线程都要读
     */
    @Volatile
    private var lyric: Lyric? = null

    /** 以下字段只在追踪线程上访问 */
    private var shownIndex = -1
    private var running = false
    private var loopToken = 0L

    fun setOnProgressAdvanceListener(listener: OnProgressAdvanceListener?) {
        this.listener = listener
    }

    /**
     * 绑定当前曲目的全量歌词；传入 null 表示清空
     *
     * 只接受全量文档；单行来源由服务直接生成 FULL 帧，不启动时钟。
     */
    fun setLyrics(lyric: Lyric?) {
        val converted = lyric?.takeIf { it.lineCount > 0 }
        this.lyric = converted
        // 时长与清空都同步生效：构造 FULL 帧的 binder 线程会立刻读 positionNow()/durationMs，
        // 排到追踪线程上执行会让新曲目的首帧拿到上一首的时长或位置
        if (converted == null) {
            clock.reset()
            clock.lyricsEndMs = 0L
        } else {
            clock.durationMs = converted.duration
            // 宿主时长可能短于歌词跨度；位置上界取两者较大值，否则后半段歌词永远不推进
            clock.lyricsEndMs = converted.lines.last().end
        }
        handler.post {
            shownIndex = -1
            if (converted == null) stopLoop()
        }
    }

    /**
     * 同步宿主上报的播放状态
     *
     * 位置回退（seek / 重播）会被识别为新锚点并立即重推行索引，不做"虚假 0ms"抑制。
     */
    fun onPlaybackStateChanged(state: PlaybackState?) {
        if (state == null) return

        val position = state.position
        val speed = state.playbackSpeed
        val updateTime = state.lastPositionUpdateTime
        val status = toStatus(state.state)

        val now = timeSource()
        val previous = clock.positionAt(now)
        val jumpedBackwards = position < previous - JUMP_BACK_TOLERANCE_MS

        // 宿主给的锚点可能过时到没有参考价值；钳到 [now-MAX, now]，避免把
        // “几分钟前的快照”当成当前位置，直接把进度推到曲尾。
        val anchorTime = when {
            updateTime <= 0L -> now
            updateTime > now -> now
            now - updateTime > MAX_ANCHOR_AGE_MS -> now - MAX_ANCHOR_AGE_MS
            else -> updateTime
        }
        // 同步锚定：State/FULL 帧在别的线程上立即读 positionNow()，排队同步会让首帧拿到过期位置
        clock.sync(status, position, speed, anchorTime)

        handler.post {
            if (jumpedBackwards) {
                // 位置回退是合法事件：重置行索引，让下一行立即重推
                shownIndex = -1
            }

            dispatchIfNeeded(clock.positionAt(timeSource()))
            updateLoop()
        }
    }

    /**
     * 当前播放位置（毫秒）
     *
     * 可从任意线程读取，供 v4 State 帧取快照。
     */
    fun positionNow(): Long = clock.positionAt(timeSource())

    /** 当前播放速率（倍速） */
    fun currentSpeed(): Float = clock.currentSpeed

    /**
     * 当前行下标；没有曲目或早于首行开始时间为 -1（与 4.0 的 `Lyric.indexAt` 一致）
     *
     * 与 [dispatchIfNeeded] 使用同一个 Lyric.indexAt，State 帧里的行号与真正触发回调的
     * 行号不会分叉。
     */
    fun indexNow(): Int {
        val current = lyric ?: return -1
        if (current.lineCount == 0) return -1
        return current.indexAt(positionNow())
    }

    /** 停止追踪并清空全部状态 */
    fun stop() {
        lyric = null
        // 同步复位：tracker.stop() 之后紧接着就会构造 State/快照，排队复位会让它读到过期位置
        clock.reset()
        handler.post {
            running = false
            loopToken++
            shownIndex = -1
        }
    }

    private fun updateLoop() {
        val shouldRun = clock.currentStatus == PlaybackStatus.PLAYING && lyric != null
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

        dispatchIfNeeded(clock.positionAt(timeSource()))

        if (running && token == loopToken) {
            handler.postDelayed({ runLoop(token) }, delayUntilNextLine())
        }
    }

    /**
     * 睡到下一行的起点，而不是固定间隔空转
     *
     * 行边界是已知的，按它排程既准又省：一首 4 分钟的歌由约 4800 次唤醒降到与行数同量级。
     * 到末行后退化为固定间隔：此时还可能变的只剩位置跳转，而跳转有自己的入口
     * （[onSessionPositionSynced] / [onPlaybackStateChanged] 都会立即重推）。
     *
     * 只能在追踪线程上调用。
     */
    private fun delayUntilNextLine(): Long {
        val lines = lyric?.lines ?: return MAX_LOOP_SLEEP_MS
        val position = clock.positionAt(timeSource())
        val index = lyric?.indexAt(position) ?: -1
        // 末行之后没有可推的内容；位置跳转有专门的入口，不需要 20Hz 空转。
        val nextStart = lines.getOrNull(index + 1)?.start ?: return MAX_LOOP_SLEEP_MS
        return (nextStart - position).coerceIn(LOOP_INTERVAL_MS, MAX_LOOP_SLEEP_MS)
    }

    /**
     * 外部直接同步播放位置（3.x 发布者仍会上报点位）
     *
     * v4 发布者不再推进度；这条入口只服务 3.x 发布者，时钟仍由系统 MediaSession 的状态
     * 重新锚定。
     */
    fun onSessionPositionSynced(positionMs: Long) {
        val now = timeSource()
        val jumpedBackwards = positionMs < clock.positionAt(now) - JUMP_BACK_TOLERANCE_MS

        // 同步 seek：调用方紧接着可能构造 State 帧，排队 seek 会让它读到过期位置
        clock.seekTo(positionMs, now)

        handler.post {
            if (jumpedBackwards) {
                shownIndex = -1
            }
            dispatchIfNeeded(positionMs)
            updateLoop()
        }
    }

    /**
     * 若当前位置落在新的行上，则回调行号与位置
     *
     * 本方法只能在追踪线程上调用。
     */
    private fun dispatchIfNeeded(positionMs: Long) {
        val current = lyric ?: return
        if (current.lineCount == 0) return

        val index = current.indexAt(positionMs)
        if (index < 0 || index == shownIndex) return
        shownIndex = index

        AndroidLog.logD(
            TAG,
            "Advance line [$index/${current.lineCount}] at ${positionMs}ms: " +
                    "\"${current.lines[index].text}\""
        )
        listener?.onProgressAdvance(index, positionMs)
    }

    /**
     * `FAST_FORWARDING` / `REWINDING` / `SKIPPING_*` 属于"仍在播放、只是位置在动"，
     * 归为 PLAYING；并进 `else` 会让时钟在快进与跳转期间冻结
     *
     * `NONE` 在这里归为 STOPPED，与 `PlaybackStates.toSignal` 判其为 `UNKNOWN` 并不冲突：
     * 这里决定"时钟是否继续走"，那里决定"是否清空并下发停止"。冻结可逆（宿主随后报
     * 任何状态都会重新锚定），清空不可逆，因此同一个"不知道"在本层可按停止处理，
     * 在停止判定里必须保持中性；把这里也改成中性会让时钟在宿主停止上报后继续推进。
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

        /** 超过该幅度的位置回退才判定为 seek / 重播，避免抖动误判 */
        const val JUMP_BACK_TOLERANCE_MS = 1_000L

        /** 宿主锚点超过该年龄即不可信，钳到 now-MAX */
        const val MAX_ANCHOR_AGE_MS = 5 * 60 * 1000L
    }
}
