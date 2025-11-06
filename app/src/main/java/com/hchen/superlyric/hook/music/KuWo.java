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

import androidx.annotation.NonNull;

import com.hchen.collect.Collect;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.HCData;
import com.hchen.hooktool.helper.RangeHelper;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.helper.TimeoutHelper;
import com.hchen.superlyric.hook.LyricRelease;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.util.Objects;

/**
 * 酷我音乐
 */
@Collect(targetPackage = "cn.kuwo.player")
public class KuWo extends LyricRelease {
    @Override
    protected void init() {
    }

    @Override
    protected void initApplicationAfter(@NonNull Context context) {
        super.initApplicationAfter(context);
        HCData.setClassLoader(context.getClassLoader());

        if (existsClass("cn.kuwo.mod.playcontrol.RemoteControlLyricMgr")) {
            hookAllMethod("cn.kuwo.mod.playcontrol.RemoteControlLyricMgr",
                "updateLyricText",
                new IHook() {
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
            if (confMMKVMgrImplClass == null) return;

            findMethodPro(confMMKVMgrImplClass)
                .withReturnClass(boolean.class)
                .withParamCount(3, RangeHelper.EQ)
                .withParamClasses(String.class, String.class, boolean.class)
                .single()
                .hook(new IHook() {
                    @Override
                    public void before() {
                        String key = (String) getArg(1);
                        if (Objects.equals(key, "bluetooth_car_lyric"))
                            setResult(true);
                    }
                });

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
            findMethodPro(clazz)
                .withParamCount(1, RangeHelper.EQ)
                .withParamClasses(String.class)
                .single()
                .hook(new IHook() {
                    @Override
                    public void before() {
                        String lyric = (String) getArg(0);
                        if (lyric == null || lyric.isEmpty()) return;

                        TimeoutHelper.start();
                        sendLyric(lyric);
                    }
                });
        }
    }
}
