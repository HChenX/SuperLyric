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
import com.hchen.superlyric.BuildConfig
import com.hchen.superlyric.bridge.LyricPublishBridge
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.orchestrate.PlaybackSignal
import com.hchen.superlyric.kernel.orchestrate.StopEvidence
import com.hchen.superlyric.kernel.orchestrate.StopGate
import com.hchen.superlyric.publisher.AbsPublisher
import com.hchen.superlyric.utils.LyricDiag
import com.hchen.superlyric.utils.PlaybackSignals

import io.github.libxposed.api.XposedModuleInterface

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 音乐应用 Provider 的统一骨架
 *
 * 一个 Provider 只需实现三处：
 * 1. 类上的 `@HookThis(targetPackage = "…")`
 * 2. [createHookSource] / [createApiSource]：至少实现一个
 * 3. [buildIdentity]：从 `MediaSession` 元数据构建稳定标识
 *
 * 切歌感知、双轨降级、超时、重试与发布包装由基类与 [LyricOrchestrator] 处理。
 * Provider 只发布全量歌词与停止包，进度推进由系统框架侧的唯一时钟负责。
 *
 * 具体 Provider 必须用 Java：`@HookThis` 是 `SOURCE` 级注解，由 `:processor` 的
 * Java 注解处理器消费；Kotlin 类不经过该处理器，不会进入生成的 `HookMaps`，
 * 模块不会被挂载。
 *
 * @author 焕晨HChen
 */
abstract class MusicProvider : AbsPublisher() {

    /**
     * Hook 取词来源
     *
     * 必须 volatile：在 `onPackageReady` 线程写，在宿主 `MediaSession` 回调线程读
     * （子类经 [hookStableId] 或自己的旁路字段读取同一状态）。可见性失败时读到 null，
     * 会退化为使用宿主元数据，而宿主元数据不可信。
     */
    @Volatile
    private var hookSource: HookLyricSource? = null

    /**
     * 上一次已输出日志的元数据；宿主会高频重发同一份，用它降噪
     *
     * `@Volatile`：与 [hookSource] 相同，在宿主 `MediaSession` 回调线程读写。
     */
    @Volatile
    private var lastSeenIdentity: TrackIdentity? = null

    /** 上一次已转储全部元数据键的稳定标识；同样在回调线程读写 */
    @Volatile
    private var lastDumpedStableId: String? = null

    /** 宿主自己的 MediaSession；仅诊断期用于模拟跳转播放位置 */
    @Volatile
    private var hostSession: MediaSession? = null

    /** 宿主最近一次上报的播放状态；`MediaSession` 不提供读取接口，只能缓存 */
    @Volatile
    private var hostPlaybackState: PlaybackState? = null

    private val stopConfirmer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SuperLyric-StopConfirm").apply { isDaemon = true }
        }

    /**
     * “播放仍在继续”的证据计数（见 [StopEvidence]）
     *
     * 确认在 [stopConfirmer] 线程执行，撤销在宿主 `MediaSession` 回调线程执行。
     * StopGate 只保证排期时无人撤销，无法保证之后无人发布：撤销若落在“令牌已确认消费、
     * 回调尚未执行”之间，会读到 `armed == false` 而放行，随后
     * [LyricOrchestrator.onPlaybackStopped] 无条件推进 epoch、清空并取消两个来源，
     * 清掉刚切到的新曲目。
     *
     * 因此排期时调用 [StopEvidence.snapshot]，确认前调用 [StopEvidence.isStillValid]。
     * system_server 侧对应 `SuperLyricService#mStopEvidence`。
     */
    private val stopEvidence = StopEvidence()

    /**
     * 停止判定
     *
     * 与 system_server 侧共用 [StopGate]，观察点各自独立：本侧观察宿主进程内
     * `MediaSession.setPlaybackState` 的调用。两侧不合并观察点，因为覆盖面不同，
     * 合并会丢失单侧独有信号。
     */
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

    /**
     * 由基类在 [onPackageReady] 中构建；Provider 子类不应自行创建编排器
     *
     * `@Volatile`：注入线程写，宿主 `MediaSession` 回调线程读；
     * [hookSource] / [hostSession] / [hostPlaybackState] 同理。
     */
    @Volatile
    protected lateinit var orchestrator: LyricOrchestrator
        private set

    /** 日志标签，同时作为 [TrackIdentity.source] */
    protected abstract val providerTag: String

    /** Hook 取词来源；返回 null 表示该应用没有可用的 Hook 路径 */
    protected open fun createHookSource(): HookLyricSource? = null

    /** 网络 API 取词来源；返回 null 表示该应用没有可用的兜底路径 */
    protected open fun createApiSource(): LyricSource? = null

    /** 编排参数；需要更长的 Hook 等待窗口或更多次重试时覆写 */
    protected open fun orchestratorConfig(): LyricOrchestrator.Config = LyricOrchestrator.Config()

    /**
     * 从 `MediaSession` 元数据构建音轨身份
     *
     * 默认实现取 `MEDIA_ID` 作为稳定标识，缺失时退化为标题 + 歌手 + 时长。
     * 能拿到平台侧不可变标识（songMID / 音频 hash / rid）的 Provider 应当覆写；
     * 否则 Hook 与元数据上报的标识表示法不一致，捕获会被当作过期数据丢弃。
     */
    protected open fun buildIdentity(metadata: MediaMetadata): TrackIdentity {
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()
        return TrackIdentity(
            source = providerTag,
            stableId = mediaId?.takeIf { it.isNotEmpty() },
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)

        hookSource = createHookSource()?.also { source ->
            // 定位与挂载失败只记录日志，绝不影响宿主稳定性
            runCatching { source.install() }
                .onFailure { AndroidLog.logE(providerTag, "Failed to install hook source", it) }
        }
        val apiSource = createApiSource()

        orchestrator = LyricOrchestrator(
            hookSource,
            apiSource,
            ExecutorScheduler("$providerTag-Scheduler"),
            LyricPublishBridge(),
            orchestratorConfig(),
            diagnostics = { reason -> AndroidLog.logD(providerTag, reason) },
        )

        hookMediaSession()

        AndroidLog.logD(
            providerTag,
            "Provider ready for ${param.packageName}: hook=${hookSource?.sourceName ?: "none"}, " +
                    "api=${apiSource?.sourceName ?: "none"}",
        )
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
                    onPlaybackStateChanged(state)
                }
            },
        )
    }

    /**
     * 播放状态变化
     *
     * 停止必须确认后才生效：宿主在过渡期会瞬发 `STATE_STOPPED`，网易云在一次切歌前后
     * 会连发多次停止状态，随后回到正常播放。而 `onPlaybackStopped` 是终态操作，
     * 会清空当前音轨、推进 epoch 并向系统下发停止包；瞬发停止被当作终态时，歌词会短暂清空。
     *
     * 判定规则与 system_server 侧一致，见 [StopGate]：终态排期、旁证撤销、`NONE` 中性。
     * 本侧的旁证包括随后到达的其它播放状态与可用元数据；后者必需，因为网易云停止风暴中的
     * 几次 STOPPED 之间可能没有别的播放状态。
     */
    private fun onPlaybackStateChanged(state: PlaybackState) {
        val signal = PlaybackSignals.of(state.state)

        // 非终态信号一律记一笔证据，且必须在判定之前记录：判定为 `IGNORED` 的情形
        // （令牌已被确认消费）正是需要拦截的，此时 StopGate 已放行，只有这笔账能拦住。
        // 这里与 StopGate 的分类有意重复：那里决定是否排期，这里回答是否算证据；
        // `UNKNOWN` 在两处都不算（见 [PlaybackSignals]）
        when (signal) {
            PlaybackSignal.PLAYING, PlaybackSignal.PAUSED, PlaybackSignal.ACTIVE ->
                recordStopEvidence()

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

            // `UNKNOWN` 与“已排期在案”都落在这里。`UNKNOWN` 在过渡期会成串出现，
            // 因此只在确有停止悬着时留痕
            StopGate.Decision.IGNORED ->
                if (stopGate.isPending) {
                    AndroidLog.logD(providerTag, "$signal ignored while a stop is pending")
                }
        }

        // 停止确认会让编排器清空当前身份并回到 IDLE。宿主 resume 时通常只发
        // setPlaybackState(PLAYING)，不重发同一份 metadata；只靠 onMetadataChanged
        // 重启取词会让界面停在停止包清空后的空白态，直到下一次切歌。
        if ((signal == PlaybackSignal.PLAYING || signal == PlaybackSignal.ACTIVE) &&
            !stopGate.isPending &&
            this::orchestrator.isInitialized &&
            orchestrator.state == LyricOrchestrator.State.IDLE
        ) {
            val identity = lastSeenIdentity
            if (identity != null) {
                AndroidLog.logD(providerTag, "Playback resumed after stop; restarting resolution")
                orchestrator.onTrackChanged(identity)
            }
        }
    }

    /**
     * 记一笔“播放仍在继续”的证据
     *
     * 与确认的读取互斥，见 [stopEvidence]。
     */
    private fun recordStopEvidence() {
        stopEvidence.record()
    }

    /**
     * 撤销待确认的停止
     *
     * 撤销与确认都必须留下日志：否则“从未收到 STOPPED”与“收到但每次都被撤销”
     * 在日志上无法区分，无法判断这条防线是否生效。
     */
    private fun cancelPendingStop(reason: String) {
        // 元数据到达同样是“播放仍在继续”的证据，先记账，再读取 StopGate 的判定
        recordStopEvidence()
        if (stopGate.onEvidence() == StopGate.Decision.CANCELLED) {
            AndroidLog.logD(providerTag, "Pending stop cancelled by $reason")
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadata) {
        val identity = buildIdentity(metadata)

        // 空元数据在过渡期很常见，既不是切歌也不是停止，只是宿主尚未填好，
        // 因此直接忽略：切歌由带标识的元数据触发，停止由 [StopGate] 按播放信号判定。
        if (identity.title.isEmpty() && !identity.hasStableId) {
            AndroidLog.logD(providerTag, "Ignoring metadata without title or stable id")
            return
        }

        if (identity != lastSeenIdentity) {
            lastSeenIdentity = identity
            AndroidLog.logD(
                providerTag,
                "Metadata changed: id=${identity.stableId}, title=\"${identity.title}\", " +
                        "artist=\"${identity.artist}\", duration=${identity.durationMs}ms",
            )
        }

        // 每首曲目只转储一次全部元数据键，用于排查曲名被宿主改写等问题。
        // 没有 stableId 的宿主不能退化为 null==null，否则整个进程只转储第一首。
        val dumpKey = identity.stableId
            ?: (identity.title + "|" + identity.artist + "|" + identity.durationMs)
        if (dumpKey != lastDumpedStableId) {
            lastDumpedStableId = dumpKey
            LyricDiag.dumpMetadata(providerTag, metadata)
        }

        // 收到可用曲目信息说明播放仍在继续，撤销过渡期那次 STOPPED。
        // 这是挡住停止风暴的关键：网易云停止风暴中的几次 STOPPED 之间可能只有元数据，
        // 没有其它播放状态。
        cancelPendingStop("track metadata")

        orchestrator.onTrackChanged(identity)
    }

    /** 供子类读取当前 Hook 侧已知的稳定标识 */
    protected fun hookStableId(): String? = hookSource?.currentStableId()

    override fun onApplicationCreated(context: Context) {
        super.onApplicationCreated(context)
        if (BuildConfig.DEBUG) registerSeekSimulator(context)
    }

    /**
     * 仅诊断期：注册广播接收器，用于模拟宿主跳转播放位置
     *
     * 验收清单中的大幅拖动与拖回开头需要改变播放位置，而 adb 无法 seek。本模块持有宿主
     * [MediaSession] 引用，可替它发布带新位置的 `PlaybackState`；对下游时钟与行索引而言
     * 与用户拖动等价。
     *
     * `adb shell am broadcast -a com.hchen.superlyric.DEBUG.SEEK --el position 60000`
     *
     * 只在 debug 构建注册。刻意未加权限：`adb shell am broadcast` 以 shell uid 发出，
     * 加权限或不导出都会使诊断失效。风险是 debug 构建下任意应用也能触发一次位置跳转；
     * 发布构建不注册该接收器，实机用户不受影响。
     */
    private fun registerSeekSimulator(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val target = intent?.getLongExtra(EXTRA_SEEK_POSITION, -1L) ?: -1L
                if (target >= 0L) simulateSeek(target)
            }
        }
        val filter = IntentFilter(ACTION_DEBUG_SEEK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun simulateSeek(positionMs: Long) {
        val session = hostSession
        val current = hostPlaybackState
        if (session == null || current == null) {
            // 广播已到达本进程，但本进程不持有宿主会话或状态；多进程宿主中常见。
            // 记录后返回，避免被误判为通道未生效。
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
        /** 诊断期模拟跳转播放位置的广播 action */
        const val ACTION_DEBUG_SEEK = "com.hchen.superlyric.DEBUG.SEEK"

        const val EXTRA_SEEK_POSITION = "position"
    }
}
