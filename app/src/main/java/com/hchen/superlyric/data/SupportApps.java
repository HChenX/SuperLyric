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
package com.hchen.superlyric.data;

import com.hchen.superlyric.R;

import java.util.Map;
import java.util.Set;

/**
 * 支持的音乐应用清单与应用名资源映射。
 *
 */
public final class SupportApps {
    public static final Set<String> sMediaAppPackages = SupportedApps.PACKAGES;

    public static final Map<String, Integer> sPackageLabelRes = Map.ofEntries(
        Map.entry("com.apple.android.music", R.string.apple_music),
        Map.entry("cn.wenyu.bodian", R.string.bodian_music),
        Map.entry("com.kugou.android", R.string.kugou_music),
        Map.entry("com.kugou.android.lite", R.string.kugou_lite_music),
        Map.entry("cn.kuwo.player", R.string.kuwo_music),
        Map.entry("cn.kuwo.kwmusiccar", R.string.kuwo_car_music),
        Map.entry("com.miui.player", R.string.miui_music),
        Map.entry("cmccwm.mobilemusic", R.string.migu_music),
        Map.entry("com.netease.cloudmusic", R.string.wangyiyun_music),
        Map.entry("com.maxmpz.audioplayer", R.string.poweramp_music),
        Map.entry("com.luna.music", R.string.qishui_music),
        Map.entry("com.tencent.qqmusic", R.string.qq_music),
        Map.entry("com.salt.music", R.string.jiaoyan_music),
        Map.entry("com.spotify.music", R.string.spotify_music)
    );
}
