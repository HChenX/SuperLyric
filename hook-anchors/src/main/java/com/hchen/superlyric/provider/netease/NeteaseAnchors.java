/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.provider.netease;

import android.app.Instrumentation;
import android.content.Context;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;

/**
 * 网易云壳类绕过：在 Instrumentation 创建 Application 时替换为真实类。
 */
public final class NeteaseAnchors {
    private static final String TAG = "NeteaseAnchors";
    private static final String WRAPPER_APPLICATION = "com.netease.nis.wrapper.MyApplication";
    private static final String REAL_APPLICATION = "com.netease.cloudmusic.CloudMusicApplication";

    private NeteaseAnchors() {
    }

    public static void installWrapperBypass() {
        try {
            CoreTool.hookMethod("android.app.Instrumentation",
                "newApplication",
                ClassLoader.class, String.class, Context.class,
                new AbsHook() {
                    @Override
                    public void before() {
                        Object className = getArg(1);
                        if (WRAPPER_APPLICATION.equals(className)) {
                            setArg(1, REAL_APPLICATION);
                            AndroidLog.logI(TAG, "Bypassed NetEase wrapper to CloudMusicApplication");
                            return;
                        }
                        AndroidLog.logI(TAG, "Application class is " + className + "; no wrapper to bypass");
                    }
                }
            );
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to bypass NetEase wrapper: " + t.getMessage());
        }
    }
}
