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
package com.hchen.superlyric.provider.spotify;

import android.media.MediaMetadata;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Spotify 歌词提供者。
 *
 * <h3>为什么是纯网络取词</h3>
 * Spotify 的歌词不经过任何 Java 侧模型（由原生播放器渲染），没有可 Hook 的歌词载体。
 * 唯一可行的路径是拉取官方 {@code color-lyrics/v2} 接口——这条路径本身是**稳定**的，
 * 需要解决的只是鉴权：复用宿主自己已经发出的会话头即可，模块不持有任何凭证。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.spotify.music")
public class SpotifyProvider extends MusicProvider {
    private static final String TAG = "SpotifyProvider";
    private static final String NAMESPACE = "spotify";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        // 会话头捕获要尽早挂载，否则会错过宿主启动期的请求
        SpotifyAnchors.installHeaderCapture();
        super.onPackageReady(param);
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new SpotifyApiSource();
    }

    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

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
