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
import androidx.compose.foundation.layout.size
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricCache
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * API 接收器与状态管理单例
 */
internal object ApiReceiverManager {
    val receiverFlow = MutableStateFlow(ApiReceiverState())
    val pausedFlow = MutableStateFlow(false)

    private var isRegistered = false

    /**
     * 收包计数
     *
     * 必须原子：[mReceiver] 的回调由 Binder 线程池并发进入，`++` 会丢更新，
     * 让"全量/增量"的统计与展示用的 `packetSeq` 一起失真
     */
    private val totalPackets = AtomicInteger(0)
    private val fullPackets = AtomicInteger(0)
    private val progressPackets = AtomicInteger(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val mReceiver = object : ISuperLyricReceiver.Stub() {
        override fun onLyric(publisher: String?, data: SuperLyricData?) {
            if (pausedFlow.value) return
            handleIncomingLyric(publisher, data)
        }

        override fun onStop(publisher: String?, data: SuperLyricData?) {
            if (pausedFlow.value) return
            handleIncomingStop(publisher, data)
        }
    }

    fun ensureRegistered() {
        try {
            if (!SuperLyricHelper.isAvailable()) return
            val currentlyRegistered = SuperLyricHelper.isReceiverRegistered(mReceiver)
            if (!currentlyRegistered) {
                SuperLyricHelper.registerReceiver(mReceiver)
            }
            isRegistered = true
        } catch (e: Throwable) {
            android.util.Log.e("ApiReceiverManager", "Failed to register receiver", e)
            isRegistered = false
        }
        if (receiverFlow.value.data == null) {
            pullLatest()
        }
    }

    fun unregister() {
        try {
            if (SuperLyricHelper.isAvailable() && SuperLyricHelper.isReceiverRegistered(mReceiver)) {
                SuperLyricHelper.unregisterReceiver(mReceiver)
            }
        } catch (e: Throwable) {
            android.util.Log.e("ApiReceiverManager", "Failed to unregister receiver", e)
        } finally {
            isRegistered = false
        }
    }

    private fun handleIncomingLyric(publisher: String?, data: SuperLyricData?) {
        val seq = totalPackets.incrementAndGet()
        val isFull = data != null && data.hasAllLyrics()
        if (isFull) {
            fullPackets.incrementAndGet()
        } else {
            progressPackets.incrementAndGet()
        }

        // 使用官方 SuperLyricCache 统一解析增量与全量歌词
        val effectiveData = SuperLyricCache.resolve(data) ?: data
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        receiverFlow.value = ApiReceiverState(
            publisher = publisher,
            data = effectiveData,
            eventType = "ON_LYRIC",
            receiveTime = time,
            packetSeq = seq,
            fullCount = fullPackets.get(),
            progressCount = progressPackets.get(),
            allLyricsCache = effectiveData?.allLyrics
        )
    }

    private fun handleIncomingStop(publisher: String?, data: SuperLyricData?) {
        val seq = totalPackets.incrementAndGet()
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val effectiveData = SuperLyricCache.resolve(data) ?: data
        receiverFlow.value = ApiReceiverState(
            publisher = publisher,
            data = effectiveData,
            eventType = "ON_STOP",
            receiveTime = time,
            packetSeq = seq,
            fullCount = fullPackets.get(),
            progressCount = progressPackets.get(),
            allLyricsCache = effectiveData?.allLyrics
        )
    }

    fun pullLatest(onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                val latest = SuperLyricHelper.getLatestLyric()
                if (latest != null) {
                    val effectiveData = SuperLyricCache.resolve(latest) ?: latest
                    val seq = totalPackets.incrementAndGet()
                    if (latest.hasAllLyrics()) fullPackets.incrementAndGet() else progressPackets.incrementAndGet()
                    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                    receiverFlow.value = ApiReceiverState(
                        publisher = "SuperLyricService",
                        data = effectiveData,
                        eventType = "PULL_LATEST",
                        receiveTime = time,
                        packetSeq = seq,
                        fullCount = fullPackets.get(),
                        progressCount = progressPackets.get(),
                        allLyricsCache = effectiveData.allLyrics
                    )
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(true)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun injectMockLyric() {
        val mockWords1 = arrayOf(
            SuperLyricWord("看", 0, 400),
            SuperLyricWord("蔚", 400, 800),
            SuperLyricWord("蓝", 800, 1200),
            SuperLyricWord("的", 1200, 1500),
            SuperLyricWord("天", 1500, 2000),
            SuperLyricWord("空", 2000, 2600)
        )
        val line1 = SuperLyricLine("看蔚蓝的天空", mockWords1, 0, 2600)
        val line2 = SuperLyricLine(
            "白云在轻轻飘动",
            arrayOf(
                SuperLyricWord("白", 2700, 3100),
                SuperLyricWord("云", 3100, 3500),
                SuperLyricWord("在", 3500, 3800),
                SuperLyricWord("轻", 3800, 4200),
                SuperLyricWord("轻", 4200, 4600),
                SuperLyricWord("飘", 4600, 5000),
                SuperLyricWord("动", 5000, 5600)
            ),
            2700,
            5600
        )
        val line3 = SuperLyricLine("耳边回响着熟悉的旋律", null, 5700, 9000)
        val line4 = SuperLyricLine("那是我们写下的歌", null, 9100, 13000)
        val line5 = SuperLyricLine("记录下青春最初的颜色", null, 13100, 17500)
        val line6 = SuperLyricLine("穿越时空的思念依然如昨", null, 17600, 22000)

        val allLines = arrayOf(line1, line2, line3, line4, line5, line6)
        val mockData = SuperLyricData()
            .setTitle("晴天之歌")
            .setArtist("SuperLyric")
            .setAlbum("Demo Album")
            .setLyricId("mock_song_001")
            .setDuration(22000)
            .setPosition(600)
            .setCurrentLyricIndex(0)
            .setLyric(line1)
            .setTranslation(SuperLyricLine("Look at the azure sky", null, 0, 2600))
            .setSecondary(SuperLyricLine("kan wei lan de tian kong", null, 0, 2600))
            .setAllLyrics(allLines)

        handleIncomingLyric("MockDebugPublisher", mockData)
    }

    fun advanceMockProgress() {
        val current = receiverFlow.value
        val data = current.data ?: run {
            injectMockLyric()
            return
        }
        val lines = current.allLyricsCache ?: data.allLyrics
        if (lines.isNullOrEmpty()) {
            injectMockLyric()
            return
        }
        val curIdx = data.currentLyricIndex
        val nextIdx = (curIdx + 1) % lines.size
        val nextLine = lines[nextIdx]
        val nextPos = nextLine.startTime + 100

        val progressData = SuperLyricData()
            .setTitle(data.title)
            .setArtist(data.artist)
            .setAlbum(data.album)
            .setLyricId(data.lyricId)
            .setDuration(data.duration)
            .setPosition(nextPos)
            .setCurrentLyricIndex(nextIdx)
            .setLyric(nextLine)

        if (nextIdx == 0) {
            progressData.setTranslation(SuperLyricLine("Look at the azure sky", null, 0, 2600))
            progressData.setSecondary(SuperLyricLine("kan wei lan de tian kong", null, 0, 2600))
        } else if (nextIdx == 1) {
            progressData.setTranslation(SuperLyricLine("White clouds drift softly by", null, 2700, 5600))
        }

        handleIncomingLyric(current.publisher ?: "MockDebugPublisher", progressData)
    }

    fun advanceMockWordProgress(stepMs: Long = 400) {
        val current = receiverFlow.value
        val data = current.data ?: run {
            injectMockLyric()
            return
        }
        val lines = current.allLyricsCache ?: data.allLyrics
        if (lines.isNullOrEmpty()) {
            injectMockLyric()
            return
        }
        val curIdx = data.currentLyricIndex
        val safeIdx = if (curIdx in lines.indices) curIdx else 0
        val curLine = lines[safeIdx]
        val curPos = if (data.position >= 0) data.position else curLine.startTime

        val (nextPos, nextIdx) = if (curPos + stepMs >= curLine.endTime && safeIdx + 1 < lines.size) {
            val nLine = lines[safeIdx + 1]
            Pair(nLine.startTime + 100, safeIdx + 1)
        } else {
            Pair(curPos + stepMs, safeIdx)
        }

        val targetLine = if (nextIdx in lines.indices) lines[nextIdx] else curLine

        val progressData = SuperLyricData()
            .setTitle(data.title)
            .setArtist(data.artist)
            .setAlbum(data.album)
            .setLyricId(data.lyricId)
            .setDuration(if (data.hasDuration() && data.duration > 0) data.duration else 0)
            .setPosition(nextPos)
            .setCurrentLyricIndex(nextIdx)
            .setLyric(targetLine)

        if (data.hasTranslation()) progressData.setTranslation(data.translation)
        if (data.hasSecondary()) progressData.setSecondary(data.secondary)

        handleIncomingLyric(current.publisher ?: "MockDebugPublisher", progressData)
    }

    fun injectMockStop() {
        val current = receiverFlow.value
        handleIncomingStop(current.publisher ?: "MockDebugPublisher", current.data)
    }

    fun reset() {
        totalPackets.set(0)
        fullPackets.set(0)
        progressPackets.set(0)
        SuperLyricCache.clear()
        receiverFlow.value = ApiReceiverState()
    }
}

internal data class ApiReceiverState(
    val publisher: String? = null,
    val data: SuperLyricData? = null,
    val eventType: String? = null,
    val receiveTime: String? = null,
    val packetSeq: Int = 0,
    val fullCount: Int = 0,
    val progressCount: Int = 0,
    val allLyricsCache: Array<SuperLyricLine>? = null,
    val positionTimestamp: Long = SystemClock.elapsedRealtime()
)
