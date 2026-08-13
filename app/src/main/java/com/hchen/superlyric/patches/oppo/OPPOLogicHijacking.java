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
package com.hchen.superlyric.patches.oppo;

import static com.hchen.hooktool.core.CoreTool.hook;
import static com.hchen.hooktool.core.CoreTool.setStaticField;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.superlyric.hook.AbsPublisher;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;

/**
 * OPPO / Heytap 系音乐应用的通用逻辑处理。
 * <p>
 * 模拟 OPPO 设备开启歌词能力、hook 媒体元数据歌词，并绕过蓝牙连接权限检查。
 *
 * @author 焕晨HChen
 */
public final class OPPOLogicHijacking {
    /**
     * 执行 OPPO 系音乐应用的通用 Hook 初始化。
     *
     * @param dexkitKey DexKit 缓存键（区分 OPPO/Heytap）
     */
    public static void initOPPO(@NonNull String dexkitKey) {
        mockDevice();
        AbsPublisher.hookMediaMetadataLyric();
        hookBluetoothPermissionCheck(dexkitKey);
    }

    private static void mockDevice() {
        setStaticField("android.os.Build", "BRAND", "oppo");
        setStaticField("android.os.Build", "MANUFACTURER", "Oppo");
        setStaticField("android.os.Build", "DISPLAY", "Color");
    }

    /**
     * 绕过「没有蓝牙连接权限」检查，使蓝牙场景可发布歌词。
     */
    private static void hookBluetoothPermissionCheck(@NonNull String dexkitKey) {
        Method method = DexkitCache.findMember(dexkitKey, new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass("com.allsaints.music.player.thirdpart.MediaSessionHelper")
                        .usingStrings("isCarBluetoothConnected 没有蓝牙连接权限")
                    )
                ).single();
            }
        });
        hook(method, CoreTool.returnResult(true));
    }
}
