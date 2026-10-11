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
 * 一个 Provider 只需写三处：
 * 1. 类上加 `@HookThis(targetPackage = "…")`
 * 2. [createHookSource] / [createApiSource]：至少实现一个
 * 3. [buildIdentity]：声明如何从 `MediaSession` 元数据得到稳定标识
 *
 * 其余（切歌感知、双轨降级、超时、重试、包装发布）全部由基类与 [LyricOrchestrator] 承担。
 * Provider 只发布**全量歌词**与**停止**两种包，进度推进由系统框架侧的唯一时钟负责
 *
 * ## 为什么具体 Provider 必须用 Java 写
 * `@HookThis` 是 `SOURCE` 级注解，由 `:processor` 的 Java 注解处理器消费，而本项目通过
 * `annotationProcessor` 接入。**Kotlin 类不会经过 Java 注解处理器**，因此用 Kotlin 写的
 * Provider 不会出现在生成的 `HookMaps` 中，整个模块都不会被挂载。本基类用 Kotlin 只为简洁
 *
 * @author 焕晨HChen
 */
abstract class MusicProvider : AbsPublisher() {

    /**
     * Hook 取词来源
     *
     * 必须 volatile：在 `onPackageReady` 的线程上写，在宿主 `MediaSession` 回调线程上读
     * （子类经 [hookStableId] 或自己的旁路字段读同一份状态）。可见性失败时读到 null，
     * 会静默退化为"直接用宿主元数据"——而宿主元数据是不可信的
     */
    @Volatile
    private var hookSource: HookLyricSource? = null

    /** 上一次已输出日志的元数据；宿主会高频重发同一份，用它降噪。 */
    private var lastSeenIdentity: TrackIdentity? = null

    /** 上一次已转储全部元数据键的稳定标识。 */
    private var lastDumpedStableId: String? = null

    /** 宿主自己的 MediaSession；仅诊断期用于模拟跳转播放位置 */
    @Volatile
    private var hostSession: MediaSession? = null

    /** 宿主最近一次上报的播放状态；`MediaSession` 未提供读取接口，只能自己缓存 */
    @Volatile
    private var hostPlaybackState: PlaybackState? = null

    private val stopConfirmer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SuperLyric-StopConfirm").apply { isDaemon = true }
        }

    /**
     * "播放仍在继续"的证据计数（见 [StopEvidence]）
     *
     * ## 为什么 StopGate 自己不够
     * 确认跑在 [stopConfirmer] 线程上，撤销跑在宿主 `MediaSession` 回调线程上。StopGate 只能
     * 保证"**排期时**没有人撤销"，保证不了"此后也没人发布"：撤销若落在"令牌已被确认消费、
     * 回调还没执行"这段里，它会读到 `armed == false` 而放行，随后 [LyricOrchestrator.onPlaybackStopped]
     * 就把**刚切过去的新曲目**连人带取词一起清掉——它是无条件的：推进 epoch、清空、取消两个来源。
     *
     * 因此排期时 [StopEvidence.snapshot]、确认前 [StopEvidence.isStillValid]。
     * system_server 一侧同理，见 `SuperLyricService#mStopEvidence`
     */
    private val stopEvidence = StopEvidence()

    /**
     * 停止判定
     *
     * 与 system_server 侧**共用同一套判定**（[StopGate]），只有观察点是各自的：
     * 本侧看的是宿主进程内 `MediaSession.setPlaybackState` 的调用。
     * 两侧不合并观察点——覆盖面不同，合并会丢掉其中一侧独有的信号
     */
    private val stopGate = StopGate(
        scheduler = StopGate.Scheduler { delayMs, task ->
            stopConfirmer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        },
        onConfirmed = Runnable {
            // 与证据的记账互斥地判定：StopGate 的令牌那一关证明不了"确认成立之后没人发布"
            if (!stopEvidence.isStillValid()) return@Runnable
            AndroidLog.logD(providerTag, "Playback stopped (confirmed)")
            orchestrator.onPlaybackStopped()
        },
    )

    /**
     * 由基类在 [onPackageReady] 中构建；Provider 的子类不应自行创建编排器
     *
     * `@Volatile`：在注入线程上写，在宿主 `MediaSession` 回调线程上读。同文件的
     * [hookSource] / [hostSession] / [hostPlaybackState] 出于同一原因也是 volatile
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
     * 默认实现取 `MEDIA_ID` 作为稳定标识、缺失时退化为标题 + 歌手 + 时长。
     * 能拿到平台侧不可变标识（songMID / 音频 hash / rid）的 Provider **应当**覆写，
     * 否则 Hook 上报的标识与元数据上报的标识表示法不一致，捕获会被当成过期数据丢弃
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
     * 只挂两个点 —— `setMetadata` 与 `setPlaybackState`，这是能同时覆盖"切歌"与"停止"的
     * 最小点位集合
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
                    // 留着宿主 MediaSession 及其最近一次状态，诊断期用它模拟"跳转播放位置"
                    // （MediaSession 没有 getPlaybackState()，只能自己记着）
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
     * ## 为什么停止必须"确认"后才生效
     * 宿主在过渡期会**瞬发** `STATE_STOPPED`：实测网易云在一次切歌前后连发多次停止状态，
     * 随后又回到正常播放。而 `onPlaybackStopped` 是终态操作 —— 它清空当前音轨、推进 epoch，
     * 并向系统下发**停止**包。瞬发停止若被当真，歌词会莫名闪空
     *
     * 判定规则与 system_server 侧**完全一致**，见 [StopGate]：终态排期、旁证撤销、`NONE` 中性。
     * 本侧的旁证有两条：随后到来的**其它播放状态**，以及随后到来的**可用元数据**。
     * 后者是必需的——实测网易云风暴里的几次 STOPPED 之间**没有别的播放状态**，
     * 只看播放状态挡不住
     */
    private fun onPlaybackStateChanged(state: PlaybackState) {
        val signal = PlaybackSignals.of(state.state)

        // 非终态信号一律记一笔证据，且必须在判定**之前**记：判定为 `IGNORED` 的那一半
        // （令牌已被确认消费）正是要拦的情形——此时 StopGate 已经放行，只有这笔账拦得住。
        // 这里与 StopGate 的分类**有意重复**：那里决定"要不要排期"，这里回答"算不算证据"，
        // 而 `UNKNOWN` 在两处都是"不算"（见 [PlaybackSignals]）
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

            // `UNKNOWN` 与"已排期在案"都落在这里。前者在过渡期会成串出现，无差别输出会刷屏，
            // 因此只在**确实有停止悬着**这个需要判断的时刻留痕
            StopGate.Decision.IGNORED ->
                if (stopGate.isPending) {
                    AndroidLog.logD(providerTag, "$signal ignored while a stop is pending")
                }
        }
    }

    /**
     * 记一笔"播放仍在继续"的证据
     *
     * 与确认的读取互斥，见 [stopEvidence]
     */
    private fun recordStopEvidence() {
        stopEvidence.record()
    }

    /**
     * 撤销待确认的停止
     *
     * 撤销与确认都必须留下日志：否则"从未收到 STOPPED"与"收到但每次都被撤销"
     * 在日志上完全一样，无法判断这条防线到底有没有生效
     */
    private fun cancelPendingStop(reason: String) {
        // 元数据到达同样是"播放仍在继续"的证据，先记账再看 StopGate 的判定
        recordStopEvidence()
        if (stopGate.onEvidence() == StopGate.Decision.CANCELLED) {
            AndroidLog.logD(providerTag, "Pending stop cancelled by $reason")
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadata) {
        val identity = buildIdentity(metadata)

        // 空元数据在过渡期很常见。它既不是"切歌"也不是"停止"，只是宿主还没填好，
        // 因此这里直接忽略：切歌由带标识的元数据触发，停止由 [StopGate] 按播放信号判定。
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

        // 每首曲目只转储一次全部元数据键，用于排查"曲名被宿主改写"这类问题
        if (identity.stableId != lastDumpedStableId) {
            lastDumpedStableId = identity.stableId
            LyricDiag.dumpMetadata(providerTag, metadata)
        }

        // 收到可用的曲目信息，说明播放仍在继续——先前那次 STOPPED 属过渡期瞬发，撤销它。
        // 这是挡住"停止风暴"的关键条件：实测网易云风暴里的几次 STOPPED **之间并没有别的
        // 播放状态**（夹在中间的是元数据），所以只盯着播放状态是挡不住的。
        cancelPendingStop("track metadata")

        orchestrator.onTrackChanged(identity)
    }

    /** 供子类读取当前 Hook 侧已知的稳定标识。 */
    protected fun hookStableId(): String? = hookSource?.currentStableId()

    override fun onApplicationCreated(context: Context) {
        super.onApplicationCreated(context)
        if (BuildConfig.DEBUG) registerSeekSimulator(context)
    }

    /**
     * 仅诊断期：注册广播接收器，用于**模拟宿主跳转播放位置**
     *
     * 验收清单里的「大幅拖动 / 拖回开头」需要改变播放位置，而 adb 层面无法 seek。
     * 本模块本就持有宿主 [MediaSession] 的引用，因此可以替它发布一次带新位置的
     * `PlaybackState` —— 对下游（系统框架的时钟与行索引）而言与用户拖动完全等价
     *
     * ```
     * adb shell am broadcast -a com.hchen.superlyric.DEBUG.SEEK --el position 60000
     * ```
     *
     * 只在 debug 构建注册。**刻意未加权限**：`adb shell am broadcast` 以 shell uid 发出，
     * 加了权限或改为不导出都会让这条诊断失效。代价是 debug 构建下任意应用也能触发一次
     * 位置跳转——发布构建不注册本接收器，故实机用户不受影响
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
            // 广播确实到达了本进程，但本进程不持有宿主的会话/状态——
            // 多进程宿主里这是常见情况，必须能看出来，否则会误判成"通道没生效"
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
        /** 诊断期模拟跳转播放位置的广播 action。 */
        const val ACTION_DEBUG_SEEK = "com.hchen.superlyric.DEBUG.SEEK"

        const val EXTRA_SEEK_POSITION = "position"
    }
}
