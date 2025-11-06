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
package com.hchen.superlyric.helper;

import static com.hchen.hooktool.core.CoreTool.hook;
import static com.hchen.hooktool.log.XposedLog.logE;
import static com.hchen.hooktool.log.XposedLog.logI;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.IHook;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 阻止应用获取息屏广播
 *
 * @author 焕晨HChen
 */
public class ScreenHelper {
    private static final String TAG = "ScreenHelper";

    public static void screenOffNotStopLyric(@NonNull String... excludes) {
        try {
            Method[] methods = DexkitCache.findMember("screen_helper", new IDexkit<MethodDataList>() {
                @NonNull
                @Override
                public MethodDataList dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .usingStrings("android.intent.action.SCREEN_OFF")
                            .returnType(void.class)
                            .name("onReceive")
                            .paramTypes(Context.class, Intent.class)
                        )
                    );
                }
            });

            Arrays.stream(methods).forEach(method -> {
                String className = method.getDeclaringClass().getSimpleName();
                if (!className.contains("Fragment") && !className.contains("Activity")) {
                    if (Arrays.stream(excludes).noneMatch(className::contains)) {
                        logI(TAG, "[screenOffNotStopLyric]: hook method: " + method);

                        hook(method,
                            new IHook() {
                                @Override
                                public void before() {
                                    Intent intent = (Intent) getArg(1);
                                    if (TextUtils.equals(intent.getAction(), Intent.ACTION_SCREEN_OFF)) {
                                        returnNull();
                                    }
                                }
                            }
                        );
                    }
                }
            });
        } catch (Throwable e) {
            logE(TAG, e);
        }
    }
}
