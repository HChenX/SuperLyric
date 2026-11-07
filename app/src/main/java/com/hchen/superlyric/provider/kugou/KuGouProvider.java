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
package com.hchen.superlyric.provider.kugou;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * 酷狗音乐歌词提供者：仅走官方 API 路径。
 * <p>
 * 宿主不把原生歌词报文写入磁盘，没有可用的 Hook 路径。MEDIA_ID 可能先后上报多个哈希，
 * 直查落空时必须回退到「歌名 + 歌手」检索，否则会把有歌词的曲目误判为无歌词。
 */
@HookThis(targetPackage = "com.kugou.android")
public class KuGouProvider extends MusicProvider {
    private static final String TAG = "KuGouProvider";

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(null, new KuGouApiSource());
    }
}