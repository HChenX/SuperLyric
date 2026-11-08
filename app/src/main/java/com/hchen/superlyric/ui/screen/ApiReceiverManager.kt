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
package com.hchen.superlyric.ui.screen

import android.os.SystemClock
import android.util.Log
import com.hchen.superlyricapi.LyricListener
import com.hchen.superlyricapi.LyricView
import com.hchen.superlyricapi.Receiver
import com.hchen.superlyricapi.SuperLyric
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.Lyric
import com.hchen.superlyricapi.model.PlaybackState
import com.hchen.superlyricapi.model.Stop
import com.hchen.superlyricapi.model.StopReason
import com.hchen.superlyricapi.model.Word
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * API 接收器与状态管理单例
 *
 * 接收端支持多个 [LyricListener]；本看板只注册自己的那一个，注册与注销按生命周期
 * 配对，避免后台继续收帧。
 */
internal object ApiReceiverManager {
    val receiverFlow = MutableStateFlow(ApiReceiverState())
    val pausedFlow = MutableStateFlow(false)

    private const val TAG = "ApiReceiverManager"
    private const val SOURCE_SERVICE = "super_lyric.v4"
    private const val SOURCE_LEGACY = "super_lyric"
    private const val SOURCE_MOCK = "MockDebugPublisher"

    private const val EVENT_FULL = "FULL"
    private const val EVENT_STATE = "STATE"
    private const val EVENT_STOP = "STOP"
    private const val EVENT_PULL = "PULL_LATEST"

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val totalPackets = AtomicInteger(0)
    private val fullPackets = AtomicInteger(0)
    private val statePackets = AtomicInteger(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 最近一次注册得到的服务名
     *
     * 仅在帧未携带 publisher 时作为兜底。
     */
    @Volatile
    private var sourceLabel = SOURCE_SERVICE

    /**
     * 收包计数与状态赋值的临界区
     *
     * 计数与随后的 `receiverFlow.value` 赋值必须原子完成：回调由 Binder 线程池并发
     * 进入，若 T2 取到更大的序号却先赋值，看板上的 `packetSeq` 会倒退。锁只包住
     * 这两步，不包耗时操作
     */
    private val publishLock = Any()

    private val mListener = object : LyricListener {
        override fun onLyric(view: LyricView) {
            if (pausedFlow.value) return
            handleIncomingLyric(view)
        }

        override fun onStop(stop: Stop) {
            if (pausedFlow.value) return
            handleIncomingStop(stop)
        }
    }

    fun ensureRegistered() {
        var registeredNow = false
        try {
            if (!SuperLyric.isAvailable()) return
            // 每次回到前台都重新 register：同一 transport 时该调用幂等，
            // 服务重启后新的 transport 会自动重绑
            val result = SuperLyric.receiver().register(mListener)
            registeredNow = result == Receiver.RegisterResult.OK
            if (!registeredNow) {
                Log.w(TAG, "Register result: $result")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register receiver", t)
        }
        if (registeredNow) {
            sourceLabel = if (SuperLyric.serverApiVersion() > 0) SOURCE_SERVICE else SOURCE_LEGACY
        }
        // register 不触发任何帧，也不补发 STOP：每次回到前台或切到本页都主动拉取一次，
        // 补上后台期间错过的 FULL 与 STOP
        pullLatest()
    }

    fun unregister() {
        try {
            // 接收端支持多个监听器；本类只注册一个，进程内也没有其它消费者，
            // 因此用 unregisterAll 断开整个连接
            SuperLyric.receiver().unregisterAll()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to unregister receiver", t)
        }
    }

    private fun handleIncomingLyric(view: LyricView) {
        val time = LocalTime.now().format(TIME_FORMAT)

        synchronized(publishLock) {
            // STATE 帧复用同一个 Lyric 实例，引用变化即表示 FULL 帧。
            // 必须在锁内读取：并发 STOP 可能已经替换了 receiverFlow。
            val previous = receiverFlow.value.lyric
            val isFull = previous == null || view.lyric !== previous
            val seq = totalPackets.incrementAndGet()
            if (isFull) {
                fullPackets.incrementAndGet()
            } else {
                statePackets.incrementAndGet()
            }
            receiverFlow.value = ApiReceiverState(
                lyric = view.lyric,
                index = view.index,
                position = view.position,
                speed = view.speed,
                playbackState = view.state,
                source = view.publisher ?: sourceLabel,
                eventType = if (isFull) EVENT_FULL else EVENT_STATE,
                receiveTime = time,
                packetSeq = seq,
                fullCount = fullPackets.get(),
                stateCount = statePackets.get(),
            )
        }
    }

    private fun handleIncomingStop(stop: Stop) {
        val time = LocalTime.now().format(TIME_FORMAT)

        synchronized(publishLock) {
            // 与 onLyric 共用同一临界区：在锁外读 current 会用旧帧覆盖并发发布的新帧。
            val current = receiverFlow.value
            val seq = totalPackets.incrementAndGet()
            receiverFlow.value = ApiReceiverState(
                speed = 0f,
                source = stop.publisher ?: current.source ?: sourceLabel,
                eventType = EVENT_STOP,
                receiveTime = time,
                packetSeq = seq,
                fullCount = fullPackets.get(),
                stateCount = statePackets.get(),
                playbackState = stop.stopPlaybackState(),
                positionTimestamp = SystemClock.elapsedRealtime(),
            )
        }
    }

    fun pullLatest(onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                val view = SuperLyric.receiver().latest()
                if (view != null) {
                    val time = LocalTime.now().format(TIME_FORMAT)
                    synchronized(publishLock) {
                        val seq = totalPackets.incrementAndGet()
                        fullPackets.incrementAndGet()
                        receiverFlow.value = ApiReceiverState(
                            lyric = view.lyric,
                            index = view.index,
                            position = view.position,
                            speed = view.speed,
                            playbackState = view.state,
                            source = view.publisher ?: sourceLabel,
                            eventType = EVENT_PULL,
                            receiveTime = time,
                            packetSeq = seq,
                            fullCount = fullPackets.get(),
                            stateCount = statePackets.get(),
                        )
                    }
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(true)
                    }
                } else {
                    // 会话结束时 latest() 返回 null，STOP 记录在 lastStop；
                    // 冷启动靠它恢复已停止状态，而不是留空
                    val stop = SuperLyric.receiver().lastStop()
                    if (stop != null) {
                        handleIncomingStop(stop)
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke(true)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke(false)
                        }
                    }
                }
            } catch (e: Throwable) {
                // 必须留痕：否则无法区分服务端没有数据（正常空态）与 IPC 调用异常
                Log.w(TAG, "Failed to pull the latest lyric", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun injectMockLyric() {
        val lines = listOf(
            Line(
                "看蔚蓝的天空", 0, 2600,
                listOf(
                    Word("看", 0, 400),
                    Word("蔚", 400, 800),
                    Word("蓝", 800, 1200),
                    Word("的", 1200, 1500),
                    Word("天", 1500, 2000),
                    Word("空", 2000, 2600),
                ),
                "Look at the azure sky",
                "kan wei lan de tian kong",
            ),
            Line(
                "白云在轻轻飘动", 2700, 5600,
                listOf(
                    Word("白", 2700, 3100),
                    Word("云", 3100, 3500),
                    Word("在", 3500, 3800),
                    Word("轻", 3800, 4200),
                    Word("轻", 4200, 4600),
                    Word("飘", 4600, 5000),
                    Word("动", 5000, 5600),
                ),
                "White clouds drift softly by",
                null,
            ),
            Line("耳边回响着熟悉的旋律", 5700, 9000),
            Line("那是我们写下的歌", 9100, 13000),
            Line("记录下青春最初的颜色", 13100, 17500),
            Line("穿越时空的思念依然如昨", 17600, 22000),
        )
        val lyric = Lyric(
            "mock_song_001",
            "晴天之歌",
            "SuperLyric",
            "Demo Album",
            22_000L,
            lines,
        )
        publishMock(lyric, 0, 600L, PlaybackState.PLAYING, EVENT_FULL)
    }

    fun advanceMockProgress() {
        val current = receiverFlow.value
        val lyric = current.lyric ?: run {
            injectMockLyric()
            return
        }
        val lines = lyric.lines
        if (lines.isEmpty()) {
            injectMockLyric()
            return
        }

        val nextIdx = (current.index + 1).mod(lines.size)
        val nextLine = lines[nextIdx]
        publishMock(lyric, nextIdx, nextLine.start + 100, PlaybackState.PLAYING, EVENT_STATE)
    }

    fun advanceMockWordProgress(stepMs: Long = 400) {
        val current = receiverFlow.value
        val lyric = current.lyric ?: run {
            injectMockLyric()
            return
        }
        val lines = lyric.lines
        if (lines.isEmpty()) {
            injectMockLyric()
            return
        }

        val safeIdx = if (current.index in lines.indices) current.index else 0
        val line = lines[safeIdx]
        val position = if (current.position >= 0) current.position else line.start
        val (nextPos, nextIdx) = if (position + stepMs >= line.end && safeIdx + 1 < lines.size) {
            val nextLine = lines[safeIdx + 1]
            nextLine.start + 100 to safeIdx + 1
        } else {
            position + stepMs to safeIdx
        }

        publishMock(lyric, nextIdx, nextPos, PlaybackState.PLAYING, EVENT_STATE)
    }

    fun injectMockStop() {
        val current = receiverFlow.value
        val time = LocalTime.now().format(TIME_FORMAT)
        synchronized(publishLock) {
            val seq = totalPackets.incrementAndGet()
            receiverFlow.value = current.copy(
                eventType = EVENT_STOP,
                receiveTime = time,
                packetSeq = seq,
                playbackState = PlaybackState.STOPPED,
                positionTimestamp = SystemClock.elapsedRealtime(),
            )
        }
    }

    fun reset() {
        totalPackets.set(0)
        fullPackets.set(0)
        statePackets.set(0)
        receiverFlow.value = ApiReceiverState()
    }

    private fun publishMock(
        lyric: Lyric,
        index: Int,
        position: Long,
        playbackState: PlaybackState?,
        eventType: String,
    ) {
        val time = LocalTime.now().format(TIME_FORMAT)
        synchronized(publishLock) {
            val seq = totalPackets.incrementAndGet()
            if (eventType == EVENT_FULL) {
                fullPackets.incrementAndGet()
            } else {
                statePackets.incrementAndGet()
            }
            receiverFlow.value = ApiReceiverState(
                lyric = lyric,
                index = index,
                position = position,
                speed = 1f,
                playbackState = playbackState,
                source = SOURCE_MOCK,
                eventType = eventType,
                receiveTime = time,
                packetSeq = seq,
                fullCount = fullPackets.get(),
                stateCount = statePackets.get(),
            )
        }
    }

    private fun Stop.stopPlaybackState(): PlaybackState? = when (reason) {
        StopReason.CLEARED -> null
        StopReason.ERROR -> PlaybackState.ERROR
        else -> PlaybackState.STOPPED
    }
}

internal data class ApiReceiverState(
    val lyric: Lyric? = null,
    val index: Int = -1,
    val position: Long = 0L,
    val speed: Float = 1f,
    val playbackState: PlaybackState? = null,
    val source: String? = null,
    val eventType: String? = null,
    val receiveTime: String? = null,
    val packetSeq: Int = 0,
    val fullCount: Int = 0,
    val stateCount: Int = 0,
    val positionTimestamp: Long = SystemClock.elapsedRealtime(),
)
