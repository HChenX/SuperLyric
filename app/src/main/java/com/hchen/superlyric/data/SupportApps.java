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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 支持的音乐应用清单与应用名资源映射。
 *
 * @author 焕晨HChen
 */
public final class SupportApps {
    public static final Set<String> sMediaAppPackages = new HashSet<>() {
        {
            add("com.apple.android.music"); // Apple Music
            add("cn.wenyu.bodian"); // 波点音乐
            add("com.kugou.android"); // 酷狗音乐
            add("com.kugou.android.lite"); // 酷狗概念版
            add("cn.kuwo.player"); // 酷我音乐
            add("cn.kuwo.kwmusiccar"); // 酷我音乐车机版
            add("cmccwm.mobilemusic"); // 咪咕音乐
            add("com.netease.cloudmusic"); // 网易云音乐
            add("com.maxmpz.audioplayer"); // Poweramp
            add("com.luna.music"); // 汽水音乐
            add("com.tencent.qqmusic"); // QQ 音乐
            add("com.salt.music"); // 椒盐音乐
            add("com.spotify.music"); // Spotify
        }
    };

    public static final HashMap<String, Integer> sPackageLabelRes = new HashMap<>() {
        {
            put("com.apple.android.music", R.string.apple_music);
            put("cn.wenyu.bodian", R.string.bodian_music);
            put("com.kugou.android", R.string.kugou_music);
            put("com.kugou.android.lite", R.string.kugou_lite_music);
            put("cn.kuwo.player", R.string.kuwo_music);
            put("cn.kuwo.kwmusiccar", R.string.kuwo_car_music);
            put("cmccwm.mobilemusic", R.string.migu_music);
            put("com.netease.cloudmusic", R.string.wangyiyun_music);
            put("com.maxmpz.audioplayer", R.string.poweramp_music);
            put("com.luna.music", R.string.qishui_music);
            put("com.tencent.qqmusic", R.string.qq_music);
            put("com.salt.music", R.string.jiaoyan_music);
            put("com.spotify.music", R.string.spotify_music);
        }
    };
}
