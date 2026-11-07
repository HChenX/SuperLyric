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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build

import com.hchen.hooktool.hook.AbsHook
import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.shared.BuildConfig
import com.hchen.superlyric.bridge.LyricPublishBridge
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.orchestrate.PlaybackSignal
import com.hchen.superlyric.kernel.orchestrate.StopEvidence
import com.hchen.superlyric.kernel.orchestrate.StopGate
import com.hchen.superlyric.publisher.AbsPublisher
import com.hchen.superlyric.shared.HostIdentitySource
import com.hchen.superlyric.shared.hostString
import com.hchen.superlyric.shared.toTrackIdentity
import com.hchen.superlyric.utils.LyricDiag
import com.hchen.superlyric.utils.PlaybackStates

import io.github.libxposed.api.XposedModuleInterface

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 音乐应用 Provider 骨架：子类只提供身份源与取词计划。
 *
 * 具体 Provider 必须是 Java，@HookThis 才会进入 HookMaps。
 */
abstract class MusicProvider : AbsPublisher() {

    @Volatile
    private var nativeSource: LyricSource? = null

    @Volatile
    private var genericSource: LyricSource? = null

    /** 宿主身份来源；由 hooks-id 实现，Provider 子类在 [createIdentitySource] 中返回 */
    @Volatile
    protected var identitySource: HostIdentitySource? = null
        private set

    /** 上一次已记录的身份（含 catalogId），用于发布快照与恢复；日志仍按五字段判重。 */
    @Volatile
    private var lastSeenIdentity: TrackIdentity? = null

    /** 上一次已转储全部元数据键的稳定标识；同样在回调线程读写 */
    @Volatile
    private var lastDumpedStableId: String? = null

    private val stopConfirmer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SuperLyric-StopConfirm").apply { isDaemon = true }
        }

    /** 停止排期期间只要出现继续播放的证据，本次确认就不成立。 */
    private val stopEvidence = StopEvidence()

    /** 与 system_server 共用判定，但观察点独立。 */
    private val stopGate = StopGate(
        scheduler = StopGate.Scheduler { delayMs, task ->
            stopConfirmer.schedule({
                try {
                    task.run()
                } catch (t: Throwable) {
                    // stopConfirmer 会丢弃 Future 的异常；未记录时停止确认失败无法从日志发现。
                    AndroidLog.logE(providerTag, "Stop confirmation task failed", t)
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        },
        onConfirmed = Runnable {
            // 与证据记账互斥地判定：StopGate 的令牌只能证明排期，无法证明确认之后没有新发布
            if (!stopEvidence.isStillValid()) return@Runnable
            AndroidLog.logD(providerTag, "Playback stopped (confirmed)")
            orchestrator.onPlaybackStopped()
        },
    )

    /** 注入线程写，宿主 MediaSession 回调线程读。 */
    @Volatile
    protected lateinit var orchestrator: LyricOrchestrator
        private set

    /** 日志标签，同时作为 [TrackIdentity.source] */
    protected abstract val providerTag: String

    /** 宿主身份来源；返回 null 表示该应用没有 Hook 身份路径 */
    protected open fun createIdentitySource(): HostIdentitySource? = null

    /** 取词计划；返回空计划表示该应用没有任何来源 */
    protected open fun createLyricPlan(): LyricPlan = LyricPlan(null, null)

    /** 编排参数；需要更长的 Hook 等待窗口或更多次重试时覆写 */
    protected open fun orchestratorConfig(): LyricOrchestrator.Config = LyricOrchestrator.Config()

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        identitySource = createIdentitySource()?.also { source ->
            runCatching { source.install() }
                .onFailure { AndroidLog.logE(providerTag, "Failed to install identity source", it) }
        }
        val plan = createLyricPlan()
        nativeSource = plan.native
        genericSource = plan.generic
        installSource(plan.native)
        installSource(plan.generic)

        orchestrator = LyricOrchestrator(
            plan.native,
            plan.generic,
            ExecutorScheduler("$providerTag-Scheduler"),
            LyricPublishBridge { lastSeenIdentity },
            orchestratorConfig(),
            diagnostics = { reason -> AndroidLog.logD(providerTag, reason) },
        )

        hookMediaSession()

        AndroidLog.logD(
            providerTag,
            "Provider ready for ${param.packageName}: native=${plan.native?.sourceName ?: "none"}, " +
                    "generic=${plan.generic?.sourceName ?: "none"}",
        )
    }

    private fun installSource(source: LyricSource?) {
        (source as? InstallableLyricSource)?.let {
            runCatching { it.install() }
                .onFailure { error -> AndroidLog.logE(providerTag, "Failed to install lyric source", error) }
        }
    }

    private fun notifyApplicationCreated(source: LyricSource?, context: Context) {
        val installable = source as? InstallableLyricSource ?: return
        runCatching { installable.onApplicationCreated(context) }
            .onFailure { error ->
                AndroidLog.logE(
                    providerTag,
                    "Failed to initialize ${installable.sourceName} on application create",
                    error,
                )
            }
    }

    /**
     * 默认的切歌与播放状态感知：Hook 系统 `MediaSession`
     *
     * 只挂 `setMetadata` 与 `setPlaybackState` 两个点，同时覆盖切歌与停止。
     */
    private fun hookMediaSession() {
        "android.media.session.MediaSession".hookMethod(
            "setMetadata",
            "android.media.MediaMetadata",
            object : AbsHook() {
                override fun after() {
                    val metadata = getArg(0) as? MediaMetadata ?: return
                    onMetadataChanged(metadata)
                }
            },
        )

        "android.media.session.MediaSession".hookMethod(
            "setPlaybackState",
            "android.media.session.PlaybackState",
            object : AbsHook() {
                override fun after() {
                    val state = getArg(0) as? PlaybackState ?: return
                    // 保留宿主 MediaSession 及其最近一次状态，诊断期用于模拟跳转播放位置
                    // （MediaSession 没有 getPlaybackState()，只能自行记录）
                    hostSession = getThisObject() as? MediaSession
                    hostPlaybackState = state
                    // 播放进程才有会话；onApplicationCreated 只在主进程触发，这里补注册
                    ensureSeekSimulator()
                    onPlaybackStateChanged(state)
                }
            },
        )
    }

    /**
     * 播放状态变化。
     *
     * 停止必须确认后才生效：宿主在过渡期会瞬发 STOPPED，随后又回到播放。判定规则与
     * system_server 侧一致，见 StopGate；元数据到达同样算“仍在播放”的证据。
     */
    private fun onPlaybackStateChanged(state: PlaybackState) {
        val signal = PlaybackStates.toSignal(state.state)

        // 非终态信号先记证据，再交给 StopGate 判定。
        when (signal) {
            PlaybackSignal.PLAYING, PlaybackSignal.PAUSED, PlaybackSignal.ACTIVE ->
                stopEvidence.record()

            PlaybackSignal.STOPPED, PlaybackSignal.ERROR, PlaybackSignal.SESSION_GONE,
            PlaybackSignal.UNKNOWN -> Unit
        }

        when (stopGate.onSignal(signal)) {
            StopGate.Decision.ARMED -> {
                stopEvidence.snapshot()
                AndroidLog.logD(providerTag, "$signal received; awaiting confirmation")
            }

            StopGate.Decision.CANCELLED ->
                AndroidLog.logD(providerTag, "Pending stop cancelled by $signal")

            // 只有存在待确认停止时才记录。
            StopGate.Decision.IGNORED ->
                if (stopGate.isPending) {
                    AndroidLog.logD(providerTag, "$signal ignored while a stop is pending")
                }
        }

        // 宿主恢复播放时通常不重发 metadata，需要主动重建停止后的身份。
        if ((signal == PlaybackSignal.PLAYING || signal == PlaybackSignal.ACTIVE) &&
            !stopGate.isPending &&
            orchestrator.state == LyricOrchestrator.State.IDLE
        ) {
            val identity = lastSeenIdentity
            if (identity != null) {
                AndroidLog.logD(providerTag, "Playback resumed after stop; restarting resolution")
                orchestrator.onTrackChanged(identity)
            }
        }
    }

    /** 撤销待确认的停止并记录结果。 */
    private fun cancelPendingStop(reason: String) {
        // 元数据到达同样是“播放仍在继续”的证据，先记账，再读取 StopGate 的判定
        stopEvidence.record()
        if (stopGate.onEvidence() == StopGate.Decision.CANCELLED) {
            AndroidLog.logD(providerTag, "Pending stop cancelled by $reason")
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadata) {
        val identity = identitySource?.current(metadata) ?: metadata.toTrackIdentity(
            providerTag,
            metadata.hostString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                .trim()
                .takeIf { it.isNotEmpty() },
        )

        // 过渡期空元数据既不是切歌也不是停止。
        if (identity.title.isEmpty() && !identity.hasStableId) {
            AndroidLog.logD(providerTag, "Ignoring metadata without title or stable id")
            return
        }

        val previous = lastSeenIdentity
        val changed = previous == null || previous != identity
        val catalogChanged = previous != null && previous.catalogId != identity.catalogId
        if (changed || catalogChanged) {
            lastSeenIdentity = identity
        }
        if (changed) {
            AndroidLog.logD(
                providerTag,
                "Metadata changed: id=${identity.stableId}, title=\"${identity.title}\", " +
                        "artist=\"${identity.artist}\", duration=${identity.durationMs}ms",
            )
        }

        // 每首曲目只转储一次元数据；无 stableId 时用标题/歌手/时长区分。
        val dumpKey = identity.stableId
            ?: (identity.title + "|" + identity.artist + "|" + identity.durationMs)
        if (dumpKey != lastDumpedStableId) {
            lastDumpedStableId = dumpKey
            LyricDiag.dumpMetadata(providerTag, metadata)
        }

        // 可用元数据同样证明播放仍在继续。
        cancelPendingStop("track metadata")

        orchestrator.onTrackChanged(identity)
    }

    override fun onApplicationCreated(context: Context) {
        super.onApplicationCreated(context)
        notifyApplicationCreated(nativeSource, context)
        notifyApplicationCreated(genericSource, context)
        if (BuildConfig.DEBUG) registerSeekSimulator(context)
    }

    /** 仅 debug 构建：用宿主 MediaSession 模拟跳转，便于测试 seek。 */
    private fun registerSeekSimulator(context: Context) {
        if (seekReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val target = intent?.getLongExtra(EXTRA_SEEK_POSITION, -1L) ?: -1L
                if (target >= 0L) simulateSeek(target)
            }
        }
        val filter = IntentFilter(ACTION_DEBUG_SEEK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            seekReceiverRegistered = true
        } catch (t: Throwable) {
            // 诊断能力失败不得影响宿主
            AndroidLog.logW(providerTag, "Failed to register seek simulator", t)
        }
    }

    /** 多进程宿主中，首次播放状态回调所在进程才持有会话。 */
    private fun ensureSeekSimulator() {
        if (!BuildConfig.DEBUG || seekReceiverRegistered) return
        val context = AbsPublisher.getAppContext() ?: return
        registerSeekSimulator(context)
    }

    private fun simulateSeek(positionMs: Long) {
        val session = hostSession
        val current = hostPlaybackState
        if (session == null || current == null) {
            // 本进程不持有会话；多进程宿主中常见。
            AndroidLog.logW(
                providerTag,
                "Seek simulator reached a process without host session/state " +
                        "(session=${session != null}, state=${current != null})",
            )
            return
        }

        AndroidLog.logI(providerTag, "Simulating seek to ${positionMs}ms")
        session.setPlaybackState(
            PlaybackState.Builder(current)
                .setState(current.state, positionMs, current.playbackSpeed)
                .build()
        )
    }

    private companion object {
        /** 宿主自己的 MediaSession；仅诊断期用于模拟跳转播放位置。进程级共享，接收器与 Hook 可能不在同一实例 */
        @Volatile
        var hostSession: MediaSession? = null

        /** 宿主最近一次上报的播放状态；`MediaSession` 不提供读取接口，只能缓存。进程级共享 */
        @Volatile
        var hostPlaybackState: PlaybackState? = null

        /** 诊断广播接收器只在每个进程注册一次 */
        @Volatile
        var seekReceiverRegistered = false

        /** 诊断期模拟跳转播放位置的广播 action */
        const val ACTION_DEBUG_SEEK = "com.hchen.superlyric.DEBUG.SEEK"

        const val EXTRA_SEEK_POSITION = "position"
    }
}
