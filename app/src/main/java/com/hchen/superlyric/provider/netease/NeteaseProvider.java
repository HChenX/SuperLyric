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
package com.hchen.superlyric.provider.netease;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.shared.HostIdentitySource;

/**
 * 网易云音乐歌词提供者
 *
 * <p>该 Provider 不提供 Hook 来源，只走官方接口：由 {@code MediaSession} 提供曲目标识，
 * 模块在后台拉取原生 YRC/LRC 并自行解析。宿主 9.6+ 的歌词模型混淆严重且与状态栏、
 * 界面强耦合，Hook 路径的收益低于其脆弱性。
 */
@HookThis(targetPackage = "com.netease.cloudmusic")
public class NeteaseProvider extends MusicProvider {
    private static final String TAG = "NeteaseProvider";

    private final NeteaseIdentitySource neteaseIdentity = new NeteaseIdentitySource(TAG);

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HostIdentitySource createIdentitySource() {
        return neteaseIdentity;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(null, new NeteaseApiSource());
    }
}
