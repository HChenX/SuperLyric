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
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * Apple Music 歌词提供者。
 *
 * <h3>为什么只有 Hook 路径</h3>
 * Apple Music 的歌词由 JNI 侧解析后交给 Java 层，模块拿不到可直接解析的明文报文；
 * 同时其歌词接口需要 Apple 的 Music-User-Token，模块不持有、也不应持有用户凭证。
 * 因此本 Provider 只实现 Hook 路径。
 *
 * <p>另一个后果是**没有网络兜底**：一旦宿主结构变动导致抽取失败，本 Provider 会静默失效，
 * 而不是退化成"从别的平台拉一首同名歌"——后者在跨语言曲库上错配率很高。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.apple.android.music")
public class AppleProvider extends MusicProvider {
    private static final String TAG = "AppleProvider";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        return new AppleHookSource();
    }
}
