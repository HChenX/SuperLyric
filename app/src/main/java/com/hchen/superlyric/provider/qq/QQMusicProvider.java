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
package com.hchen.superlyric.provider.qq;

import android.media.MediaMetadata;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * QQ 音乐歌词提供者。
 *
 * <h3>取词路径</h3>
 * <ul>
 *   <li><b>Hook 路径（准确优先）</b>：只锚定 {@code LyricLoadBean} 的构造入口，
 *       取回宿主自己解析出的官方 songMID / songID；歌词本体由模块向官方接口
 *       拉取原生 QRC 报文并自行解密，**不触碰** {@code com.lyricengine.base.*} 的混淆行/词模型。</li>
 *   <li><b>API 路径（兜底）</b>：歌名 + 歌手检索，采纳门槛已收紧。</li>
 * </ul>
 *
 * <h3>为什么不做末端反射</h3>
 * QQ 20.9+ 的歌词模型深度混淆且结构随版本变动，末端反射是最脆弱的一环。
 * 官方 QRC 接口返回的是**协议级**数据，跨版本稳定；宿主模型怎么改都不影响本实现。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.tencent.qqmusic")
public class QQMusicProvider extends MusicProvider {
    private static final String TAG = "QQMusicProvider";
    private static final String NAMESPACE = "qq";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        return new QQHookSource();
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new QQApiSource();
    }

    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        // 优先用宿主捕获的 songMID：它与 Hook 路径落在同一命名空间，两者才可比
        String capturedMid = hookStableId();
        if (capturedMid != null && !capturedMid.isEmpty()) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, capturedMid), title, artist, duration);
        }

        // 退回 MediaSession 上报的 MEDIA_ID。它可能是 songMID，也可能是纯数字 songID；
        // 后者不作为稳定标识——宿主会在同一首曲目上从 songID 切到 songMID，
        // 那会被误判为切歌（详见 QQIdentity#isNumericId）。
        String mediaId = readString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID).trim();
        if (!mediaId.isEmpty() && !QQIdentity.isNumericId(mediaId)) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, mediaId), title, artist, duration);
        }

        // 没有可用稳定标识时，改用标题 + 歌手 + 时长判定同一性
        return new TrackIdentity(TAG, null, title, artist, duration);
    }

    private static String readString(@NonNull MediaMetadata metadata, @NonNull String key) {
        String value = metadata.getString(key);
        return value != null ? value : "";
    }
}
