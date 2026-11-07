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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.kugou;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * 酷狗概念版歌词提供者
 * <p>
 * 概念版与酷狗音乐同源（同为 {@code com.kugou.framework} 播放服务），取词路线一致：
 * 宿主把原生报文放在内存中、不落盘，没有可用的 Hook 取词点，只走官方 KRC 接口。
 * 差异仅在目标包名；标识构建、直查落空后回退到「歌名 + 歌手」检索、缓存命名空间
 * 全部共用，边界详见 {@link KuGouProvider} 的类注释。
 */
@HookThis(targetPackage = "com.kugou.android.lite")
public class KuGouLiteProvider extends MusicProvider {
    private static final String TAG = "KuGouLiteProvider";

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