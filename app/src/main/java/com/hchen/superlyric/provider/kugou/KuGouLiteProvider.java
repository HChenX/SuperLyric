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
import androidx.annotation.Nullable;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * 酷狗概念版歌词提供者。
 *
 * <h3>与酷狗音乐的关系</h3>
 * 概念版与酷狗音乐同源（同为 {@code com.kugou.framework} 播放服务），因此两者的取词
 * 路线完全一致：宿主把原生报文放在自己的内存里、**不落盘**，没有可用的 Hook 取词点，
 * 于是都只走官方 KRC 接口。差异仅在于目标包名，其余（标识构建、直查落空后回退到
 * 「歌名 + 歌手」检索、缓存命名空间）全部共用，理由与边界详见
 * {@link KuGouProvider} 的类注释。
 *
 * <p>本类必须用 Java 编写：{@code @HookThis} 由 Java 注解处理器消费，
 * 而 Kotlin 类不会经过该处理器，写 Kotlin 会导致整个 Provider 不被注册。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.kugou.android.lite")
public class KuGouLiteProvider extends MusicProvider {
    private static final String TAG = "KuGouLiteProvider";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @Nullable
    @Override
    protected HookLyricSource createHookSource() {
        // 与酷狗音乐同理：宿主不把原生报文落盘，没有可用的 Hook 取词点
        return null;
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new KuGouApiSource();
    }
}
