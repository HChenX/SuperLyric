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

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.shared.HostIdentitySource;

/**
 * Spotify 歌词提供者
 *
 * <p>只有网络取词一条路径：Spotify 的歌词不经过任何 Java 侧模型（由原生播放器渲染），
 * 没有可 Hook 的歌词载体。唯一可行路径是拉取官方 {@code color-lyrics/v2} 接口，鉴权
 * 复用宿主自己已经发出的会话头，模块不持有任何凭证。
 *
 */
@HookThis(targetPackage = "com.spotify.music")
public class SpotifyProvider extends MusicProvider {
    private static final String TAG = "SpotifyProvider";

    private final SpotifySessionHeaders sessionHeaders = new SpotifySessionHeaders();
    private final SpotifyIdentitySource spotifyIdentity = new SpotifyIdentitySource(TAG, sessionHeaders);

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HostIdentitySource createIdentitySource() {
        return spotifyIdentity;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(null, new SpotifyApiSource());
    }
}
