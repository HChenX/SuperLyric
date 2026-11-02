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
 * QQ 音乐歌词提供者
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
        String mediaId = metadataString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID).trim();

        // 锚点捕获的 songMID 与 Hook 路径落在同一命名空间，两者才可比。锚点可能落后：
        // 切歌时它若没有触发，capturedMid 会停在上一首，新曲目被构造成上一首的 mid，
        // 编排器判为同一首、不触发切歌。取更可信的那个，判据见 QQIdentity#preferredMid
        String mid = QQIdentity.preferredMid(hookStableId(), mediaId);
        if (mid != null) {
            return trackIdentity(metadata, TrackIdentity.stableId(NAMESPACE, mid));
        }

        // 退回 MEDIA_ID。纯数字 songID 不作为稳定标识：宿主会在同一首曲目上从 songID
        // 切到 songMID，那会被误判为切歌（详见 QQIdentity#isNumericId）。
        if (!mediaId.isEmpty() && !QQIdentity.isNumericId(mediaId)) {
            return trackIdentity(metadata, TrackIdentity.stableId(NAMESPACE, mediaId));
        }

        return trackIdentity(metadata, null);
    }
}
