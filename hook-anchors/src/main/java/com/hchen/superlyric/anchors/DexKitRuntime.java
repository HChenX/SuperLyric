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
package com.hchen.superlyric.anchors;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.hooktool.log.AndroidLog;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 宿主进程的 DexKit 缓存生命周期。
 */
public final class DexKitRuntime {
    private static final String TAG = "DexKitRuntime";

    private DexKitRuntime() {
    }

    public static void initialize(@NonNull XposedModuleInterface.PackageReadyParam param, int version) {
        try {
            DexkitCache.init(
                "superlyric",
                param.getClassLoader(),
                param.getApplicationInfo().sourceDir,
                param.getApplicationInfo().dataDir,
                version
            );
        } catch (Throwable t) {
            // 缓存初始化失败只能降级 DexKit 查找，不能中断 packageReady 里其余模块。
            AndroidLog.logE(TAG, "Failed to initialize DexKit cache", t);
        }
    }

    public static void close() {
        try {
            DexkitCache.close();
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to close DexKit cache", t);
        }
    }
}
