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
package com.hchen.superlyric.provider.poweramp;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * Poweramp 歌词来源
 *
 * <p>宿主把歌词解析链路全部放在 Java/Kotlin 层：内嵌标签（USLT/SYLT）、同名 LRC 文件、
 * 插件写入的缓存都汇总到 {@code TagAndMeta} 与 {@code cached_lyrics.content}。该 Provider
 * 在解析入口截获整首歌词文本，再交给内核解析；逐行通道只取当前渲染行，会丢整首歌词，
 * 不能作为取词入口。
 *
 */
@HookThis(targetPackage = "com.maxmpz.audioplayer")
public class PowerampProvider extends MusicProvider {
    private static final String TAG = "PowerampProvider";

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(new PowerampHookSource(), null);
    }

    /**
     * 切歌时的取词要先查宿主数据库、再读 LRC 文件，默认 800ms 的 Hook 窗口
     * 在首次查询（JIT 未热）时可能不够。该 Provider 没有网络兜底，
     * 超时后编排器直接进入 UNAVAILABLE，因此窗口放宽到 3 秒。
     */
    @NonNull
    @Override
    protected LyricOrchestrator.Config orchestratorConfig() {
        return new LyricOrchestrator.Config(3000L, 3, 500L, 3000L);
    }
}