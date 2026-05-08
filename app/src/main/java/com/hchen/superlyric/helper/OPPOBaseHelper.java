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

import static com.hchen.hooktool.core.CoreTool.hook;

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
 * OPPO/Heytap 音乐共享逻辑
 * <p>
 * 提取设备模拟、MediaMetadata 歌词拦截和蓝牙连接状态 Hook。
 *
 * @author 焕晨HChen
 */
public final class OPPOBaseHelper {

    /**
     * 执行 OPPO 系音乐应用的通用 Hook 初始化
     *
     * @param dexkitKey DexKit 缓存键（区分 OPPO/Heytap）
     */
    public static void initHook(@NonNull String dexkitKey) {
        OPPOHelper.mockDevice();
        AbsPublisher.hookMediaMetadataLyric();
        hookCarBluetoothConnected(dexkitKey);
    }

    private static void hookCarBluetoothConnected(@NonNull String dexkitKey) {
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
