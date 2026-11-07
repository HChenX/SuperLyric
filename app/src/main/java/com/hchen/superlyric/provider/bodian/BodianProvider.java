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
package com.hchen.superlyric.provider.bodian;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.base.LyricPlan;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.provider.kugou.KuGouApiSource;
import com.hchen.superlyric.shared.HostIdentitySource;

/**
 * 波点音乐歌词提供者。
 * <p>
 * Hook 路径截获 LyricsRunner4Flutter 构造参数中的本地歌词文件路径；API 路径复用酷狗接口
 * 做跨源检索。身份以 MEDIA_ID 为准，rid 只在其为空时兜底。
 */
@HookThis(targetPackage = "cn.wenyu.bodian")
public class BodianProvider extends MusicProvider {
    private static final String TAG = "BodianProvider";

    private final BodianIdentitySource bodianIdentity = new BodianIdentitySource(TAG);

    @NonNull
    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HostIdentitySource createIdentitySource() {
        return bodianIdentity;
    }

    @NonNull
    @Override
    protected LyricPlan createLyricPlan() {
        return new LyricPlan(new BodianHookSource(), new KuGouApiSource());
    }
}
