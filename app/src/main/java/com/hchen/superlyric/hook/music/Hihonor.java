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

 * Copyright (C) 2023-2025 HChenX
 */
package com.hchen.superlyric.hook.music;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.hchen.collect.Collect;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.HCData;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.helper.MeizuHelper;
import com.hchen.superlyric.hook.LyricRelease;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 荣耀音乐
 */
@Collect(targetPackage = "com.hihonor.cloudmusic")
public class Hihonor extends LyricRelease {
    @Override
    protected void init() {
        hookTencentTinker();
        if (existsClass("android.app.Instrumentation")) {
            hookMethod("android.app.Instrumentation",
                "newApplication",
                ClassLoader.class, String.class, Context.class,
                new IHook() {
                    @Override
                    public void before() {
                        if (Objects.equals("com.netease.nis.wrapper.MyApplication", getArg(1))) {
                            setArg(1, "com.netease.cloudmusic.CloudMusicApplication");
                            logD(TAG, "Hooked netease wrapper class");
                        }
                    }
                }
            );
        }
    }

    @Override
    protected void initApplicationAfter(@NonNull Context context) {
        super.initApplicationAfter(context);
        HCData.setClassLoader(context.getClassLoader());

        MeizuHelper.shallowLayerDeviceMock();
        MeizuHelper.hookNotificationLyric();

        Method method = DexkitCache.findMember("hihonor$1", new IDexkit<MethodData>() {
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

        Class<?> clazz = DexkitCache.findMember("hihonor$2", new IDexkit<ClassData>() {
            @NonNull
            @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingStrings("com/netease/cloudmusic/module/lyric/flyme/StatusBarLyricSettingManager.class:setSwitchStatus:(Z)V")
                    )
                ).single();
            }
        });
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getReturnType().equals(boolean.class)) {
                hook(m, returnResult(true));
            } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(boolean.class)) {
                hook(m, setArg(0, true));
            } else if (m.getReturnType().equals(SharedPreferences.class)) {
                hook(m, new IHook() {
                    @Override
                    public void after() {
                        SharedPreferences sp = (SharedPreferences) getResult();
                        sp.edit().putBoolean("status_bar_lyric_setting_key", true).apply();
                    }
                });
            }
        }
    }
}
