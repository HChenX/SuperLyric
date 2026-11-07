/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.data;

import java.util.Set;

/**
 * 受支持的音乐应用包名集合；UI 标签表仍留在 :app。
 */
public final class SupportedApps {
    public static final Set<String> PACKAGES = Set.of(
        "com.apple.android.music",
        "cn.wenyu.bodian",
        "com.kugou.android",
        "com.kugou.android.lite",
        "cn.kuwo.player",
        "cn.kuwo.kwmusiccar",
        "com.miui.player",
        "cmccwm.mobilemusic",
        "com.netease.cloudmusic",
        "com.maxmpz.audioplayer",
        "com.luna.music",
        "com.tencent.qqmusic",
        "com.salt.music",
        "com.spotify.music"
    );

    private SupportedApps() {
    }
}
