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
package com.hchen.superlyric.hook.music;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.helper.TimeoutHelper;
import com.hchen.superlyric.hook.AbsPublisher;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 酷我音乐
 */
@AutoHook(targetPackage = "cn.kuwo.player")
public final class KuWo extends AbsPublisher {
    @Override
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);

        if (hasClass("cn.kuwo.mod.playcontrol.RemoteControlLyricMgr")) {
            hookAllMethod("cn.kuwo.mod.playcontrol.RemoteControlLyricMgr",
                "updateLyricText",
                new AbsHook() {
                    @Override
                    public void after() {
                        String lyric = (String) getArg(0);
                        if (lyric == null || lyric.isEmpty()) return;

                        TimeoutHelper.start();
                        sendLyric(lyric);
                    }
                }
            );
        } else {
            Class<?> confMMKVMgrImplClass = findClass("cn.kuwo.base.config.ConfMMKVMgrImpl");

            hook(Arrays.stream(confMMKVMgrImplClass.getDeclaredMethods())
                    .filter(new Predicate<Method>() {
                        @Override public boolean test(Method method) {
                            return Objects.equals(method.getReturnType(), boolean.class) &&
                                method.getParameterCount() == 3 &&
                                Arrays.deepEquals(new Class<?>[]{String.class, String.class, boolean.class}, method.getParameterTypes());
                        }
                    }).findFirst().orElseThrow(),
                new AbsHook() {
                    @Override
                    public void before() {
                        String key = (String) getArg(1);
                        if (Objects.equals(key, "bluetooth_car_lyric"))
                            setResult(true);
                    }
                }
            );

            fakeBluetoothA2dpEnabled();

            Class<?> clazz = DexkitCache.findMember("kuwo$1", new IDexkit<ClassData>() {
                @NonNull
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingStrings("正在搜索歌词...", "bluetooth_car_lyric")
                        )
                    ).singleOrThrow(() -> new Throwable("Failed to find bluetooth_car_lyric!"));
                }
            });

            hook(Arrays.stream(clazz.getDeclaredMethods())
                    .filter(new Predicate<Method>() {
                        @Override public boolean test(Method method) {
                            return method.getParameterCount() == 1 &&
                                Objects.equals(method.getParameterTypes()[0], String.class);
                        }
                    }).findFirst().orElseThrow(),
                new AbsHook() {
                    @Override
                    public void before() {
                        String lyric = (String) getArg(0);
                        if (lyric == null || lyric.isEmpty()) return;

                        TimeoutHelper.start();
                        sendLyric(lyric);
                    }
                }
            );
        }
    }
}
