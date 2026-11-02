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
package com.hchen.superlyric.provider.soda;

import android.media.MediaMetadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;


/**
 * 汽水音乐歌词提供者
 */
@HookThis(targetPackage = "com.luna.music")
public class SodaProvider extends MusicProvider {
    private static final String TAG = "SodaProvider";

    /**
     * 汽水内部以 Luna 命名；命名空间只用于诊断与跨来源区分
     */
    private static final String NAMESPACE = "luna";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        return new SodaHookSource();
    }

    /**
     * 刻意不挂任何跨源 API 兜底
     *
     * <p>汽水的歌词由宿主自己按曲目 id 装载，与当前播放严格同源；跨源兜底只能按歌名 +
     * 歌手去别的库检索，命中即发布，对翻唱、同名曲、UGC 视频条目都会命中另一份歌词，
     * 且它与 Hook 拿到的正文共用同一身份与播放位置，两套时轴互相顶替，表现为播到一半
     * 歌词与当前曲目对不上。
     *
     * <p>因此显式返回 {@code null} 而不依赖基类默认值：基类若为 {@code createApiSource()}
     * 提供默认实现，该 Provider 也不会被接上兜底。
     */
    @Nullable
    @Override
    protected LyricSource createApiSource() {
        return null;
    }

    /**
     * 歌词由宿主从网络取回后交给歌词模型，慢于元数据更新，因此把 Hook 等待窗口放宽
     *
     * <p>没有网络来源时超时只会把状态落到 {@code UNAVAILABLE}（不发布任何占位），
     * 迟到的 Hook 结果仍会被接受；放宽只是避免这段空窗被下游读成"查不到"
     */
    @NonNull
    @Override
    protected LyricOrchestrator.Config orchestratorConfig() {
        return new LyricOrchestrator.Config(1_500L, 3, 500L, 3_000L);
    }

    /**
     * 从元数据构建音轨身份
     *
     * <p>标识取 {@code MEDIA_ID}，缺失时退回 Hook 截获的曲目标识，两者都拿不到才交给基类的
     * 组合判据（标题 + 歌手 + 时长）。
     *
     * <p>占位值必须当成"没有标识"：{@code MEDIA_ID} 若被写成 {@code 0} / {@code -1} 这类常量，
     * 所有曲目的身份就会全部相等，编排器按"同一首"处理、永不重新取词，表现为切歌后歌词
     * 一直停在上一首。
     */
    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String id = TrackIdentity.usableId(
            metadataString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
        if (id == null) {
            id = TrackIdentity.usableId(hookStableId());
        }
        return trackIdentity(metadata, TrackIdentity.stableId(NAMESPACE, id));
    }
}
