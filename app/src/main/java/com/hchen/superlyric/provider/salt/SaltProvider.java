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
 * 椒盐音乐歌词提供者
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
     * Hook 取词来源；同时用于读取宿主的真实曲目信息
     *
     * <p>{@code volatile} 是必需的：在 {@code onPackageReady} 的线程上写，在宿主
     * {@code MediaSession} 回调线程上经 {@link #buildIdentity} 读。可见性失败时读到 null，
     * 会静默退化为使用宿主元数据：而椒盐会把当前歌词行写进 TITLE，元数据完全不可信。
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
            : metadataString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = info != null && !info.getArtist().isEmpty()
            ? info.getArtist()
            : metadataString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = info != null && info.getDurationMs() > 0L
            ? info.getDurationMs()
            : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        if (hasCaptured) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, captured), title, artist, duration);
        }

        String mediaId = metadataString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID).trim();
        if (!mediaId.isEmpty()) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, mediaId), title, artist, duration);
        }

        return super.buildIdentity(metadata);
    }
}
