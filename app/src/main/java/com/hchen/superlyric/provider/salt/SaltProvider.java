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
package com.hchen.superlyric.provider.salt;

import android.media.MediaMetadata;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.provider.kugou.KuGouApiSource;

/**
 * 椒盐音乐歌词提供者。
 *
 * <h3>取词路径</h3>
 * <ul>
 *   <li><b>Hook 路径（准确优先）</b>：单锚点读取 {@code MusicController} 设置歌词时携带的
 *       {@code LyricsDocument.sourceText}——宿主的**原始歌词文本**，未经任何内部裁切。
 *       不需要反射 {@code LyricsLine} / {@code LyricsCell}。</li>
 *   <li><b>API 路径（兜底）</b>：复用酷狗开放接口做跨源检索。</li>
 * </ul>
 *
 * <h3>关于标题污染</h3>
 * 椒盐音乐的状态栏歌词功能会**把当前歌词行写进 {@code MediaMetadata.METADATA_KEY_TITLE}**，
 * 使标题高频变动、无法用于标识曲目。本实现因此优先采用 Hook 侧捕获的显式曲目标识；
 * 只有在完全没有标识时才退回标题比对，并依赖 {@link TrackIdentity} 的时长约束降低误判。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.salt.music")
public class SaltProvider extends MusicProvider {
    private static final String TAG = "SaltProvider";
    private static final String NAMESPACE = "salt";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        return new SaltHookSource();
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new KuGouApiSource();
    }

    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        String captured = hookStableId();
        if (captured != null && !captured.isEmpty()) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, captured), title, artist, duration);
        }

        String mediaId = readString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID).trim();
        if (!mediaId.isEmpty()) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, mediaId), title, artist, duration);
        }

        return super.buildIdentity(metadata);
    }

    private static String readString(@NonNull MediaMetadata metadata, @NonNull String key) {
        String value = metadata.getString(key);
        return value != null ? value : "";
    }
}
