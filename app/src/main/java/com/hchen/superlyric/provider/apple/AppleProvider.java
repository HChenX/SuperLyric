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
package com.hchen.superlyric.provider.apple;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.shared.HostIdentitySource;

/**
 * Apple Music 歌词提供者
 * <p>
 * 仅实现 Hook 路径：Apple Music 的歌词由 JNI 侧解析后交给 Java 层，模块拿不到可直接解析的
 * 明文报文；其歌词接口需要 Apple 的 Music-User-Token，模块不持有、也不应持有用户凭证。
 *
 * <p>没有网络兜底：宿主结构变化导致抽取失败时，该 Provider 不发布结果，而不是从其它平台
 * 拉取同名歌曲；跨语言曲库的匹配错误率高。
 *
 */
@HookThis(targetPackage = "com.apple.android.music")
public class AppleProvider extends MusicProvider {
    private static final String TAG = "AppleProvider";

    private final AppleIdentitySource appleIdentity = new AppleIdentitySource(TAG);

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HostIdentitySource createIdentitySource() {
        return appleIdentity;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(new AppleHookSource(), null);
    }

    @NonNull
    @Override
    protected LyricOrchestrator.Config orchestratorConfig() {
        // JNI 歌词回调可能晚于 Hook 等待窗口，超时不应封死同曲目的成功结果。
        return new LyricOrchestrator.Config(800L, 3, 500L, 3_000L, true);
    }

}
