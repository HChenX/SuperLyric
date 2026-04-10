/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.data;

import com.hchen.superlyric.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public final class SupportApps {
    public static final Set<String> mSupportMediaApp = new HashSet<>() {
        {
            add("remix.myplayer"); // APlayer
            add("com.apple.android.music"); // 苹果音乐
            add("cn.aqzscn.stream_music"); // 音流音乐
            add("cn.wenyu.bodian"); // 波点音乐
            add("org.akanework.gramophone"); // Gramophone
            add("com.heytap.music"); // OPPO 音乐
            add("com.hiby.music"); // 海贝音乐
            add("com.hihonor.cloudmusic"); // 荣耀音乐
            add("com.huawei.music"); // 华为音乐
            add("org.kde.kdeconnect_tp"); // Kde
            add("com.kugou.android"); // 酷狗音乐
            add("com.kugou.android.lite"); // 酷狗概念版
            add("cn.kuwo.player"); // 酷我音乐
            add("com.lalilu.lmusic"); // LMusic
            add("cn.toside.music.mobile"); // LX Music
            add("com.meizu.media.music"); // 魅族音乐
            add("com.mimicry.mymusic"); // 拟声音乐
            add("com.miui.player"); // 小米音乐
            add("cmccwm.mobilemusic"); // 咪咕音乐
            add("fun.upup.musicfree"); // MusicFree
            add("com.netease.cloudmusic"); // 网易云音乐
            add("com.oppo.music"); // OPPO 音乐
            add("com.maxmpz.audioplayer"); // Poweramp
            add("com.xuncorp.qinalt.music"); // 青盐音乐
            add("com.luna.music"); // 汽水音乐
            add("com.tencent.qqmusic"); // QQ 音乐
            add("com.r.rplayer"); // RPlayer
            add("com.salt.music"); // 椒盐音乐
            add("com.xuncorp.suvine.music"); // 糖醋音乐
            add("app.symfonik.music.player"); // Symfonium
        }
    };

    public static HashMap<String, Integer> mPackageToDetails = new HashMap<>() {
        {
            put("remix.myplayer", R.string.aplayer_music); // APlayer
            put("com.apple.android.music", R.string.apple_music); // 苹果音乐
            put("cn.aqzscn.stream_music", R.string.yinliu_music); // 音流音乐
            put("cn.wenyu.bodian", R.string.bodian_music); // 波点音乐
            put("org.akanework.gramophone", R.string.gramophone_music); // Gramophone
            put("com.heytap.music", R.string.oppo_music); // OPPO 音乐
            put("com.hiby.music", R.string.haibei_music); // 海贝音乐
            put("com.hihonor.cloudmusic", R.string.wangyiyun_rongyao_music); // 荣耀音乐
            put("com.huawei.music", R.string.huawei_music); // 华为音乐
            put("org.kde.kdeconnect_tp", R.string.kde_music); // Kde
            put("com.kugou.android", R.string.kugou_music); // 酷狗音乐
            put("com.kugou.android.lite", R.string.kugou_lite_music); // 酷狗概念版
            put("cn.kuwo.player", R.string.kuwo_music); // 酷我音乐
            put("com.lalilu.lmusic", R.string.lmusic_music); // LMusic
            put("cn.toside.music.mobile", R.string.luoxue_music); // LX Music
            put("com.meizu.media.music", R.string.meizu_music); // 魅族音乐
            put("com.mimicry.mymusic", R.string.nisheng_music); // 拟声音乐
            put("com.miui.player", R.string.qq_music_xiaomi); // 小米音乐
            put("cmccwm.mobilemusic", R.string.migu_music); // 咪咕音乐
            put("fun.upup.musicfree", R.string.musicfree_music); // MusicFree
            put("com.netease.cloudmusic", R.string.wangyiyun_music); // 网易云音乐
            put("com.oppo.music", R.string.oppo_music); // OPPO 音乐
            put("com.maxmpz.audioplayer", R.string.unknown); // Poweramp
            put("com.xuncorp.qinalt.music", R.string.qingyan_music); // 青盐音乐
            put("com.luna.music", R.string.qishui_music); // 汽水音乐
            put("com.tencent.qqmusic", R.string.qq_music); // QQ 音乐
            put("com.r.rplayer", R.string.rplayer_music); // RPlayer
            put("com.salt.music", R.string.jiaoyan_music); // 椒盐音乐
            put("com.xuncorp.suvine.music", R.string.tangcu_music); // 糖醋音乐
            put("app.symfonik.music.player", R.string.symfonium_music); // Symfonium
        }
    };
}
