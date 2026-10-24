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

import android.content.Context;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * Poweramp 歌词来源
 *
 * <p>宿主把歌词解析链路全部放在 Java/Kotlin 层：内嵌标签（USLT/SYLT）、
 * 同名 LRC 文件、插件写入的缓存都汇总到 {@code TagAndMeta} 与
 * {@code cached_lyrics.content}。本 Provider 在解析入口截获<b>整首</b>歌词文本，
 * 再交给内核解析；"只取当前渲染行"的逐行通道会丢整首歌词，不能作为取词入口。
 *
 * @author HChenX
 */
@HookThis(targetPackage = "com.maxmpz.audioplayer")
public class PowerampProvider extends MusicProvider {
    private static final String TAG = "PowerampProvider";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        return new PowerampHookSource();
    }

    @Override
    public void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        // packageReady 时宿主可能还没有初始化 LrcParser engine；Application 起来后重试一次。
        PowerampAnchors.retryAnchors();
    }

    /**
     * 切歌时的取词要先查宿主数据库、再读 LRC 文件，默认 800ms 的 Hook 窗口
     * 在首次查询（JIT 未热）时可能不够。本 Provider <b>没有网络兜底</b>，
     * 超时后编排器直接进入 UNAVAILABLE，因此这里把窗口放宽到 3 秒。
     */
    @NonNull
    @Override
    protected LyricOrchestrator.Config orchestratorConfig() {
        return new LyricOrchestrator.Config(3000L, 3, 500L, 3000L);
    }
}
