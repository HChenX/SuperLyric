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
 * 汽水音乐歌词提供者。
 *
 * <h3>取词路径</h3>
 * 只有 Hook 一条，两处锚点：
 * <ul>
 *   <li><b>解析入口</b>（{@code Lyric} 构造器）：宿主把原始正文交给歌词模型时截获，
 *       拿到的是未经加工的 LRC / KRC 原词。</li>
 *   <li><b>数据入口</b>（{@code TrackLyric} 的构造 / setter / getter）：宿主从网络或本地库
 *       装载曲目歌词时截获。汽水是<b>按需解析</b>的 —— 后台播放、快速切歌、UGC 视频条目下
 *       解析入口可能整首都不触发，数据入口是这些场景的唯一来源。</li>
 * </ul>
 *
 * <h3>为什么不用宿主的 {@code Sentence} / {@code Word} 模型</h3>
 * 那是宿主解析之后的产物，字段名混淆、译文被塞进 {@code translationMap}。锚在解析之前，
 * 拿到的是宿主自己拿到的那份正文，宿主改一次模型与本层无关。
 *
 * <h3>标识一致性</h3>
 * 覆写 {@link #buildIdentity} 以 {@code MEDIA_ID} 作为稳定标识（前缀 {@code luna:}）。
 * 不覆写就会让 Hook 上报的曲目标识与元数据上报的标识永不相等，Hook 捕获被当成过期数据
 * 丢弃，整条取词路径静默失效。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.luna.music")
public class SodaProvider extends MusicProvider {
    private static final String TAG = "SodaProvider";

    /** 汽水内部以 Luna 命名；命名空间只用于诊断与跨来源区分 */
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
     * 刻意不挂任何跨源 API 兜底。
     *
     * <p>汽水的歌词由宿主自己按曲目 id 装载，与当前播放严格同源；而跨源兜底只能按
     * 「歌名 + 歌手」去别的库检索，命中即发布 —— 对翻唱、同名曲、UGC 视频条目都会
     * 命中另一份歌词，且它与 Hook 拿到的正文共用同一个身份与播放位置，两套时轴互相顶替，
     * 表现为播到一半歌词和当前曲目对不上。
     *
     * <p>因此这里显式返回 {@code null}，而不是依赖基类默认值：将来基类若给
     * {@code createApiSource()} 加了默认实现，本 Provider 也不会被悄悄接上兜底。
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
     * 所有曲目的身份就会全部相等，编排器按"同一首"处理、永不重新取词——表现为切歌后歌词
     * 一直停在上一首。
     */
    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        String id = usableId(readString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
        if (id.isEmpty()) {
            id = usableId(hookStableId());
        }
        if (!id.isEmpty()) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, id), title, artist, duration);
        }
        return super.buildIdentity(metadata);
    }

    /** 过滤占位标识，见 {@link #buildIdentity} */
    @NonNull
    private static String usableId(String raw) {
        String trimmed = raw != null ? raw.trim() : "";
        switch (trimmed.toLowerCase(java.util.Locale.ROOT)) {
            case "", "0", "-1", "null", "unknown", "none":
                return "";
            default:
                return trimmed;
        }
    }

    private static String readString(@NonNull MediaMetadata metadata, @NonNull String key) {
        String value = metadata.getString(key);
        return value != null ? value : "";
    }
}
