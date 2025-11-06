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

import androidx.annotation.NonNull;

import com.hchen.collect.Collect;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.helper.RangeHelper;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 汽水音乐
 *
 * @author 焕晨HChen
 */
@Collect(targetPackage = "com.luna.music")
public class Qishui extends LyricRelease {
    private static Object BLUETOOTH;

    @Override
    protected void init() {
        fakeBluetoothA2dpEnabled();
        getMediaMetadataCompatLyric();

        Class<?> blueToothLyricStatus = findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricStatus");
        BLUETOOTH = getStaticField("com.luna.common.arch.device.OutputDeviceType", "BLUETOOTH");
        Class<?> blueToothLyricStatusClass = findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricStatus");

        Method method = DexkitCache.findMember("qishui$1", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .paramTypes(null, blueToothLyricStatus, boolean.class, int.class, Object.class)
                    )
                ).single();
            }
        });
        Class<?> clazz = method.getParameterTypes()[0];
        Method m = DexkitCache.findMember("qishui$2", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(clazz)
                        .addUsingField(
                            FieldMatcher.create()
                                .declaredClass(blueToothLyricStatusClass)
                                .name("PAUSE")
                        )
                    )
                ).single();
            }
        });
        hook(m, doNothing());

        Method b = findMethodPro(clazz)
            .withParamCount(1, RangeHelper.EQ)
            .withReturnClass(boolean.class)
            .single()
            .obtain();

        hook(b,
            new IHook() {
                @Override
                public void before() {
                    Object N = getArg(0);
                    if (N == null) {
                        setResult(true);
                    } else {
                        Object type = callMethod(N, "getType");
                        if (Objects.equals(type, BLUETOOTH)) {
                            setResult(true);
                        }
                    }
                }
            }
        );

        findMethodPro(b.getParameterTypes()[0])
            .withParamCount(0, RangeHelper.EQ)
            .withReturnClass(boolean.class)
            .single()
            .hook(returnResult(true));

        // 保持 蓝牙歌词功能 为开启状态
        // 无需强制保持开启状态，应该由用户自主选择
        // findMethodPro(clazz)
        //     .withReturnType(boolean.class)
        //     .withParamCount(0)
        //     .single()
        //     .hook(returnResult(true));
    }
}
