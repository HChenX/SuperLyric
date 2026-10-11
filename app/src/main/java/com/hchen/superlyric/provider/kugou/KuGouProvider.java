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
 * 酷狗音乐歌词提供者：**仅走官方 API 路径**。
 *
 * <h3>为什么没有 Hook 路径</h3>
 * 原设计锚定酷狗的跨进程 IPC 中枢 {@code @twin:GlobalVariate}，
 * 在宿主写入 {@code LyricData}（key=41）后读取宿主自己下载的本地 {@code .krc} 文件。
 * 实机验证（酷狗 20.8.2）证明该路径**完全不可用**：
 * <ul>
 *   <li>宿主根本不把 {@code .krc} 落盘——{@code filesDir}（194 项）与
 *       {@code externalFilesDir}（26 项）下均无任何 lyric 目录，8 个候选路径全部不存在；</li>
 *   <li>key=41 在整个播放期间每秒重发多次，每次都触发一轮无效的文件系统探测。</li>
 * </ul>
 * 因此该路径已整体删除，改由官方 KRC 接口直接取词。
 *
 * <h3>标识与检索</h3>
 * 酷狗在 {@code MediaMetadata.MEDIA_ID} 中对**同一首曲目**会上报**多个不同的哈希**
 * （实测 160~600ms 内出现 null → 甲 → 乙）。其中只有一个能直查到歌词，
 * 因此 {@link KuGouApiSource} 在直查落空时必须回退到「歌名 + 歌手」检索，
 * 否则会把有歌词的歌误判为无歌词并下发纯音乐占位。
 *
 * <p>本类必须用 Java 编写：{@code @HookThis} 由 Java 注解处理器消费，
 * 而 Kotlin 类不会经过该处理器，写 Kotlin 会导致整个 Provider 不被注册。
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
