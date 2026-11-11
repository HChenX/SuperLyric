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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.provider.mobilemusic;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.provider.kugou.KuGouApiSource;
import com.hchen.superlyric.shared.HostIdentitySource;

/**
 * 咪咕音乐歌词提供者
 *
 * <p>只读取宿主自己的完整歌词，原生 MRC 保留逐字时序及 TRC 翻译。
 * 宿主媒体元数据可能写入当前歌词行，曲目身份优先取播放器自己的 Song 模型。
 * Hook 拿不到时按曲名、歌手与时长检索酷狗曲库兜底。
 */
@HookThis(targetPackage = "cmccwm.mobilemusic")
public final class MobileMusicProvider extends MusicProvider {
    private static final String TAG = "MobileMusicProvider";

    private final MobileMusicIdentitySource miguIdentity = new MobileMusicIdentitySource();

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HostIdentitySource createIdentitySource() {
        return miguIdentity;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(new MobileMusicHookSource(), new KuGouApiSource());
    }

    @NonNull
    @Override
    protected LyricOrchestrator.Config orchestratorConfig() {
        // 加固 SDK 挂载与首次歌词下载都可能晚于 MediaSession，留出原生取词窗口。
        // 窗口到点后由酷狗检索兜底；maxApiAttempts/retryBase 取 3 与 500ms。
        return new LyricOrchestrator.Config(5_000L, 3, 500L, 3_000L);
    }
}
