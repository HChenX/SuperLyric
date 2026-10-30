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
import androidx.annotation.Nullable;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * 酷狗音乐歌词提供者：仅走官方 API 路径
 * <p>
 * 没有 Hook 路径：酷狗不把宿主原生报文写入磁盘。酷狗 20.8.2 的 {@code filesDir}
 * （194 项）与 {@code externalFilesDir}（26 项）下均无 lyric 目录，8 个候选路径均不存在；
 * key=41 的 {@code LyricData} 在播放期间每秒重发多次，逐次探测文件系统无结果。
 * 因此该 Provider 仅走官方 KRC 接口。
 * <p>
 * 标识与检索：酷狗在 {@code MediaMetadata.MEDIA_ID} 中会对同一曲目上报多个不同哈希
 * （160~600ms 内可出现 null → 甲 → 乙），其中只有一个能直查到歌词。因此
 * {@link KuGouApiSource} 在直查落空时必须回退到「歌名 + 歌手」检索，否则有歌词的曲目
 * 会被误判为无歌词并下发纯音乐占位。
 *
 * <p>本类必须用 Java：{@code @HookThis} 由 Java 注解处理器消费，Kotlin 类不经过该处理器，
 * 写 Kotlin 会导致 Provider 不被注册。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.kugou.android")
public class KuGouProvider extends MusicProvider {
    private static final String TAG = "KuGouProvider";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @Nullable
    @Override
    protected HookLyricSource createHookSource() {
        // 宿主的原生报文不落盘，没有可用的 Hook 取词点
        return null;
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new KuGouApiSource();
    }
}
