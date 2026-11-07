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
package com.hchen.superlyric.provider.miui;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.shared.HostIdentitySource;

/**
 * 小米音乐歌词提供者。
 *
 * <p>小米音乐使用 QQ 音乐引擎，宿主 {@code SongInfo} 带 QQ songID，歌词由
 * {@code QQNetwork} 拉取。该 Provider 只从宿主取曲目标识，不解析宿主歌词模型。
 *
 * <p>稳定标识沿用 {@code MediaSession} 的 {@code MEDIA_ID}；songID 只用于取词，避免
 * {@code SongInfo} 先于元数据更新时把身份切到下一首。
 *
 */
@HookThis(targetPackage = "com.miui.player")
public final class MiuiProvider extends MusicProvider {
    private static final String TAG = "MiuiProvider";

    private final MiuiIdentitySource miuiIdentity = new MiuiIdentitySource(TAG);

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HostIdentitySource createIdentitySource() {
        return miuiIdentity;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(
            new MiuiNativeFetchSource(miuiIdentity),
            new MiuiApiSource(miuiIdentity)
        );
    }
}
