/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.helper;

import static com.hchen.hooktool.core.CoreTool.hasClass;
import static com.hchen.hooktool.core.CoreTool.hookMethod;

import android.content.Context;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.XposedLog;

import java.util.Objects;

/**
 * 网易云音乐系列共享逻辑
 * <p>
 * 提取网易云加固壳绕过逻辑，供网易云音乐和荣耀音乐复用。
 *
 * @author 焕晨HChen
 */
public final class NeteaseHelper {
    private static final String TAG = "NeteaseHelper";

    /**
     * 绕过网易云加固壳（nis wrapper）
     * <p>
     * 将 MyApplication 重定向为 CloudMusicApplication。
     */
    public static void hookNeteaseWrapperBypass() {
        if (hasClass("android.app.Instrumentation")) {
            hookMethod("android.app.Instrumentation",
                "newApplication",
                ClassLoader.class, String.class, Context.class,
                new AbsHook() {
                    @Override
                    public void before() {
                        if (Objects.equals("com.netease.nis.wrapper.MyApplication", getArg(1))) {
                            setArg(1, "com.netease.cloudmusic.CloudMusicApplication");
                            XposedLog.logD(TAG, "Hooked netease wrapper class");
                        }
                    }
                }
            );
        }
    }
}
