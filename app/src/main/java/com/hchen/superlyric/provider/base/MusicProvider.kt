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

import io.github.libxposed.api.XposedModuleInterface

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

        AndroidLog.logI(
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
                    if (state.state == PlaybackState.STATE_STOPPED) {
                        AndroidLog.logD(providerTag, "Playback stopped")
                        orchestrator.onPlaybackStopped()
                    }
                }
            },
        )
    }

    private fun onMetadataChanged(metadata: MediaMetadata) {
        val identity = buildIdentity(metadata)

        // 空元数据在过渡期很常见。此处刻意**不**判定为停止——
        // 那会让歌词在切歌途中闪断。真正的停止由 STATE_STOPPED 判定。
        if (identity.title.isEmpty() && !identity.hasStableId) {
            AndroidLog.logD(providerTag, "Ignoring metadata without title or stable id")
            return
        }

        AndroidLog.logD(
            providerTag,
            "Metadata changed: id=${identity.stableId}, title=\"${identity.title}\", " +
                "artist=\"${identity.artist}\", duration=${identity.durationMs}ms",
        )
        orchestrator.onTrackChanged(identity)
    }

    /** 供子类读取当前 Hook 侧已知的稳定标识。 */
    protected fun hookStableId(): String? = hookSource?.currentStableId()
}
