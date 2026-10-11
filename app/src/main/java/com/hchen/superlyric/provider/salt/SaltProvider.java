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
import androidx.annotation.Nullable;

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

    /**
     * Hook 取词来源；同时用于读取宿主的真实曲目信息。
     *
     * <p>{@code volatile} 是必需的：在 {@code onPackageReady} 的线程上写，在宿主
     * {@code MediaSession} 回调线程上经 {@link #buildIdentity} 读。可见性失败时读到 null，
     * 会静默退化为使用**宿主元数据**——而椒盐会把当前歌词行写进 TITLE，元数据完全不可信。
     * 基类的 {@code hookSource} 出于同一原因也是 volatile。
     */
    @Nullable
    private volatile SaltHookSource saltHookSource;

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        saltHookSource = new SaltHookSource();
        return saltHookSource;
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new KuGouApiSource();
    }

    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String captured = hookStableId();
        boolean hasCaptured = captured != null && !captured.isEmpty();

        // 宿主真实曲目信息优先。椒盐在开启蓝牙歌词后会把元数据的 TITLE 写成当前歌词行
        // 或制作人名单、把 ARTIST 拼成 "歌手 - 曲名"，元数据完全不可信；
        // 而宿主自己的 Song 对象里字段是干净的，且其 id 与 captured 一一对应。
        SaltHookSource.SongInfo info = hasCaptured && saltHookSource != null
            ? saltHookSource.songInfo(captured)
            : null;

        String title = info != null && !info.getTitle().isEmpty()
            ? info.getTitle()
            : readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = info != null && !info.getArtist().isEmpty()
            ? info.getArtist()
            : readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = info != null && info.getDurationMs() > 0L
            ? info.getDurationMs()
            : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        if (hasCaptured) {
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
