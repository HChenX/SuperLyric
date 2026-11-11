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
package com.hchen.superlyric.provider.kuwo;

import androidx.annotation.NonNull;

import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.provider.kugou.KuGouApiSource;
import com.hchen.superlyric.shared.HostIdentitySource;


/**
 * 酷我音乐歌词提供者基类。
 *
 * <p>手机版与车机版共用取词和身份逻辑，仅目标包名不同。主路径走 Hook：锚点位于宿主把
 * 明文 LRCX 交给歌词引擎的入口，逐字信息在 {@code <v1,v2>} 标签中。Hook 拿不到时按
 * 曲名、歌手与时长检索酷狗曲库兜底。
 *
 * <p>只发布全量歌词，进度由系统时钟负责。身份由 hooks-id 以宿主 {@code rid} 构造。
 *
 */
abstract class KuwoBaseProvider extends MusicProvider {

    /**
     * Hook 取词的等待上限；见 {@link #orchestratorConfig}
     */
    private static final long HOOK_TIMEOUT_MS = 3_000L;

    private static final int DEFAULT_MAX_API_ATTEMPTS = 3;

    private static final long DEFAULT_RETRY_BASE_DELAY_MS = 500L;

    /**
     * 允许“标题后补”原地生效的窗口
     *
     * <p>比基类默认的 3s 宽：酷我曲目探针要等宿主取词入口被调用才拿到干净标题；
     * 取词入口调用较晚时可在 4.2s 后才拿到标题，超出默认窗口会被当成换歌，
     * 清屏后重新取词同一首。有声书换集是分钟级事件，10s 窗口不会吞掉真实换集。
     */
    private static final long DEFAULT_PROVISIONAL_GRACE_MS = 10_000L;

    private final String providerTag;
    private final KuwoIdentitySource kuwoIdentity;

    protected KuwoBaseProvider(@NonNull String providerTag) {
        this.providerTag = providerTag;
        this.kuwoIdentity = new KuwoIdentitySource(providerTag);
    }

    @NonNull
    @Override
    protected HostIdentitySource createIdentitySource() {
        return kuwoIdentity;
    }

    @NonNull
    @Override
    protected String getProviderTag() {
        return providerTag;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(new KuwoHookSource(kuwoIdentity), new KuGouApiSource());
    }

    /**
     * Hook 等待窗口放宽到 3 秒
     *
     * <p>默认 800ms 对酷我不够：宿主把解密后的 LRCX 交给渲染层的耗时在 0.5~1.2s 之间波动，
     * 内容越大越慢。
     *
     * <p>窗口到点后编排器先按“暂时失败”降级到酷狗检索；检索失败才落到不可用。
     * 迟到的 Hook 结果仍会被采纳：超时只说明本次未等到，不是 Hook 失败。
     * 放大到 3 秒是为了让多数曲目一次等到，减少“先无歌词、随后补上”的闪烁；
     * 无歌词曲目的降级因此更慢。
     */
    @NonNull
    @Override
    protected LyricOrchestrator.Config orchestratorConfig() {
        // Java 只能按位置传参：要覆盖第四项，前三项必须一并给出。
        // 第二、三项取 LyricOrchestrator.Config 的默认值，标题宽限使用本类常量
        return new LyricOrchestrator.Config(
            HOOK_TIMEOUT_MS,
            DEFAULT_MAX_API_ATTEMPTS,
            DEFAULT_RETRY_BASE_DELAY_MS,
            DEFAULT_PROVISIONAL_GRACE_MS
        );
    }

}
