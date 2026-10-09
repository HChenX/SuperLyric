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
            // add("remix.myplayer"); // APlayer
            add("com.apple.android.music"); // Apple Music
            // add("cn.aqzscn.stream_music"); // 音流音乐
            add("cn.wenyu.bodian"); // 波点音乐
            // add("org.akanework.gramophone"); // Gramophone
            // add("com.heytap.music"); // OPPO 音乐
            // add("com.hiby.music"); // 海贝音乐
            // add("com.hihonor.cloudmusic"); // 荣耀音乐
            // add("com.huawei.music"); // 华为音乐
            // add("org.kde.kdeconnect_tp"); // Kde
            add("com.kugou.android"); // 酷狗音乐
            // add("com.kugou.android.lite"); // 酷狗概念版
            // add("cn.kuwo.player"); // 酷我音乐
            // add("com.lalilu.lmusic"); // LMusic
            // add("cn.toside.music.mobile"); // LX Music
            // add("com.meizu.media.music"); // 魅族音乐
            // add("com.mimicry.mymusic"); // 拟声音乐
            // add("com.miui.player"); // 小米音乐
            add("cmccwm.mobilemusic"); // 咪咕音乐
            // add("fun.upup.musicfree"); // MusicFree
            add("com.netease.cloudmusic"); // 网易云音乐
            // add("com.oppo.music"); // OPPO 音乐
            // add("com.maxmpz.audioplayer"); // Poweramp
            // add("com.xuncorp.qinalt.music"); // 青盐音乐
            // add("com.luna.music"); // 汽水音乐
            add("com.tencent.qqmusic"); // QQ 音乐
            // add("com.r.rplayer"); // RPlayer
            add("com.salt.music"); // 椒盐音乐
            // add("com.xuncorp.suvine.music"); // 糖醋音乐
            // add("app.symfonik.music.player"); // Symfonium
            add("com.spotify.music"); // Spotify
            // add("com.aspiro.tidal"); // Tidal
        }
    };

    public static final HashMap<String, Integer> sPackageLabelRes = new HashMap<>() {
        {
            // put("remix.myplayer", R.string.aplayer_music);
            put("com.apple.android.music", R.string.apple_music);
            // put("cn.aqzscn.stream_music", R.string.yinliu_music);
            put("cn.wenyu.bodian", R.string.bodian_music);
            // put("org.akanework.gramophone", R.string.gramophone_music);
            // put("com.heytap.music", R.string.oppo_music);
            // put("com.hiby.music", R.string.haibei_music);
            // put("com.hihonor.cloudmusic", R.string.wangyiyun_rongyao_music);
            // put("com.huawei.music", R.string.huawei_music);
            // put("org.kde.kdeconnect_tp", R.string.kde_music);
            put("com.kugou.android", R.string.kugou_music);
            // put("com.kugou.android.lite", R.string.kugou_lite_music);
            // put("cn.kuwo.player", R.string.kuwo_music);
            // put("com.lalilu.lmusic", R.string.lmusic_music);
            // put("cn.toside.music.mobile", R.string.luoxue_music);
            // put("com.meizu.media.music", R.string.meizu_music);
            // put("com.mimicry.mymusic", R.string.nisheng_music);
            // put("com.miui.player", R.string.qq_music_xiaomi);
            put("cmccwm.mobilemusic", R.string.migu_music);
            // put("fun.upup.musicfree", R.string.musicfree_music);
            put("com.netease.cloudmusic", R.string.wangyiyun_music);
            // put("com.oppo.music", R.string.oppo_music);
            // put("com.maxmpz.audioplayer", R.string.unknown);
            // put("com.xuncorp.qinalt.music", R.string.qingyan_music);
            // put("com.luna.music", R.string.qishui_music);
            put("com.tencent.qqmusic", R.string.qq_music);
            // put("com.r.rplayer", R.string.rplayer_music);
            put("com.salt.music", R.string.jiaoyan_music);
            // put("com.xuncorp.suvine.music", R.string.tangcu_music);
            // put("app.symfonik.music.player", R.string.symfonium_music);
            put("com.spotify.music", R.string.spotify_music);
            // put("com.aspiro.tidal", R.string.tidal_music);
        }
    };
}
