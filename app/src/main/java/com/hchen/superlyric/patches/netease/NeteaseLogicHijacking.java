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
package com.hchen.superlyric.patches.netease;

import static com.hchen.hooktool.core.CoreTool.hasClass;
import static com.hchen.hooktool.core.CoreTool.hook;
import static com.hchen.hooktool.core.CoreTool.hookMethod;
import static com.hchen.hooktool.core.CoreTool.returnResult;
import static com.hchen.hooktool.log.XposedLog.logW;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.XposedLog;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 网易云音乐加固壳与系统级 Hook 处理。
 * <p>
 * 绕过壳包装类以恢复正常的 ClassLoader 加载，并拦截锁屏权限判定，
 * 使状态栏歌词在锁屏界面仍可显示。
 *
 * @author 焕晨HChen
 */
public final class NeteaseLogicHijacking {
    private static final String TAG = "NeteaseLogicHijacking";

    /**
     * 绕过加固壳：将壳包装的 MyApplication 重定向为真实的 CloudMusicApplication。
     */
    public static void bypassPackProtection() {
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

    /**
     * 拦截锁屏权限判定，允许歌词在锁屏界面显示。
     */
    public static void hookLockScreenPermission() {
        try {
            Method method = DexkitCache.findMember("lock_screen", new IDexkit<MethodData>() {
                @NonNull
                @Override
                public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create()
                                .usingStrings("KEY_SHOW_LOCK_SCREEN_PERMISSION")
                            )
                            .usingStrings("KEY_SHOW_LOCK_SCREEN_PERMISSION")
                        )
                    ).single();
                }
            });
            hook(method, returnResult(null));
        } catch (Throwable t) {
            logW(TAG, t);
        }
    }
}
