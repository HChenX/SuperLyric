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

import android.media.MediaMetadata
import android.media.session.PlaybackState

import com.hchen.hooktool.hook.AbsHook
import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.bridge.LyricPublishBridge
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.publisher.AbsPublisher
import com.hchen.superlyric.utils.LyricDiag

import io.github.libxposed.api.XposedModuleInterface

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 音乐应用 Provider 的统一骨架。
 *
 * <h3>一个 Provider 需要写什么</h3>
 * <ol>
 *   <li>类上加 {@code @HookThis(targetPackage = "…")}；</li>
 *   <li>[createHookSource] / [createApiSource]：至少实现一个；</li>
 *   <li>[buildIdentity]：声明如何从 `MediaSession` 元数据得到稳定标识。</li>
 * </ol>
 *
 * 其余（切歌感知、双轨降级、超时、重试、包装发布）全部由基类与
 * [LyricOrchestrator] 承担。
 *
 * <h3>职责边界</h3>
 * Provider 只发布**全量歌词**与**停止**两种包；进度推进由系统框架侧的唯一时钟负责。
 *
 * <h3>为什么带 {@code @HookThis} 注解的具体 Provider 必须用 Java 写</h3>
 * `@HookThis` 是 `SOURCE` 级注解，由 `:processor` 的 Java 注解处理器消费，
 * 而本项目通过 `annotationProcessor` 接入。**Kotlin 类不会经过 Java 注解处理器**，
 * 因此用 Kotlin 写的 Provider 不会出现在生成的 `HookMaps` 中，整个模块都不会被挂载。
 * 本基类用 Kotlin 是为了简洁，具体 Provider 用 Java 是为了能被处理器看到。
 *
 * @author 焕晨HChen
 */
abstract class MusicProvider : AbsPublisher() {

    private var hookSource: HookLyricSource? = null

    /** 上一次已输出日志的元数据；宿主会高频重发同一份，用它降噪。 */
    private var lastSeenIdentity: TrackIdentity? = null

    /** 上一次已转储全部元数据键的稳定标识。 */
    private var lastDumpedStableId: String? = null

    /** 待确认停止的令牌；任何撤销都会让它自增，从而使已排期的确认失效。 */
    @Volatile
    private var stopToken: Long = 0L

    /** 当前是否有一次待确认的停止（仅用于诊断输出，不参与判定）。 */
    @Volatile
    private var pendingStop: Boolean = false

    private val stopConfirmer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SuperLyric-StopConfirm").apply { isDaemon = true }
        }

    /** 由基类在 [onPackageReady] 中构建；Provider 的子类不应自行创建编排器。 */
    protected lateinit var orchestrator: LyricOrchestrator
        private set

    /** 日志标签，同时作为 [TrackIdentity.source]。 */
    protected abstract val providerTag: String

    /** Hook 取词来源；返回 null 表示该应用没有可用的 Hook 路径。 */
    protected open fun createHookSource(): HookLyricSource? = null

    /** 网络 API 取词来源；返回 null 表示该应用没有可用的兜底路径。 */
    protected open fun createApiSource(): LyricSource? = null

    /** 编排参数；需要更长的 Hook 等待窗口或更多次重试时覆写。 */
    protected open fun orchestratorConfig(): LyricOrchestrator.Config = LyricOrchestrator.Config()

    /**
     * 从 `MediaSession` 元数据构建音轨身份。
     *
     * 默认实现取 `MEDIA_ID` 作为稳定标识、缺失时退化为标题 + 歌手 + 时长。
     * 能拿到平台侧不可变标识（songMID / 音频 hash / rid）的 Provider **应当**覆写，
     * 否则 Hook 上报的标识与元数据上报的标识表示法不一致，捕获会被当成过期数据丢弃。
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
        )

        hookMediaSession()

        AndroidLog.logD(
            providerTag,
            "Provider ready for ${param.packageName}: hook=${hookSource?.sourceName ?: "none"}, " +
                "api=${apiSource?.sourceName ?: "none"}",
        )
    }

    /**
     * 默认的切歌与播放状态感知：Hook 系统 `MediaSession`。
     *
     * 只挂两个点——`setMetadata` 与 `setPlaybackState`，这是能同时覆盖
     * "切歌"与"停止"的最小点位集合。
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
                    onPlaybackStateChanged(state)
                }
            },
        )
    }

    /**
     * 播放状态变化。
     *
     * <h3>为什么停止必须"确认"后才生效</h3>
     * 宿主在过渡期会**瞬发** {@code STATE_STOPPED}：实测网易云在一次切歌前后
     * 连发多次停止状态，随后又回到正常播放。而 `onPlaybackStopped` 是终态操作——
     * 它清空当前音轨、推进 epoch，并向系统下发**停止**包（服务端据此清空活跃发布者
     * 并广播停播）。瞬发停止若被当真，歌词会莫名闪空。
     *
     * 因此这里只登记一次"待确认的停止"，由**两个条件**撤销：
     * <ol>
     *   <li>随后到来的任何**其它播放状态**；</li>
     *   <li>随后到来的**可用元数据**（即曲目信息，见 [onMetadataChanged]）——
     *       宿主还在宣布曲目，就说明播放没停。</li>
     * </ol>
     * 第 2 条是必需的：真机上风暴里的几次 STOPPED 之间**没有别的播放状态**，
     * 只看第 1 条挡不住。
     *
     * <p>这**不是无差别去抖**——只有真正的终态信号才需要确认，且窗口很短。
     */
    private fun onPlaybackStateChanged(state: PlaybackState) {
        if (state.state != PlaybackState.STATE_STOPPED) {
            cancelPendingStop("playback state ${state.state}")
            return
        }

        AndroidLog.logD(providerTag, "STATE_STOPPED received; awaiting confirmation")
        pendingStop = true
        val token = ++stopToken
        stopConfirmer.schedule({
            if (token == stopToken) {
                pendingStop = false
                AndroidLog.logD(providerTag, "Playback stopped (confirmed)")
                orchestrator.onPlaybackStopped()
            }
        }, STOP_CONFIRM_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * 撤销待确认的停止。
     *
     * 撤销与确认都必须留下日志：否则"从未收到 STOPPED"与"收到但每次都被撤销"
     * 在日志上完全一样，无法判断这条防线到底有没有生效。
     */
    private fun cancelPendingStop(reason: String) {
        val hadPending = pendingStop
        pendingStop = false
        stopToken++
        if (hadPending) {
            AndroidLog.logD(providerTag, "Pending stop cancelled by $reason")
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadata) {
        val identity = buildIdentity(metadata)

        // 空元数据在过渡期很常见。它既不是"切歌"也不是"停止"，只是宿主还没填好，
        // 因此这里直接忽略：切歌由带标识的元数据触发，停止由 STATE_STOPPED 判定。
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

    private companion object {
        /**
         * 停止状态的确认窗口。
         *
         * 宿主过渡期的瞬发 STOPPED 通常几十毫秒内就被后续状态取代；
         * 而真正的停止晚 400ms 生效对用户不可见。
         */
        const val STOP_CONFIRM_MS = 400L
    }
}
