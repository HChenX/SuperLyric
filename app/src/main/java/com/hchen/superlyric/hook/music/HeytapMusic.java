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
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.helper.OPPOHelper;
import com.hchen.superlyric.hook.BaseLyric;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.base.BaseData;

import java.lang.reflect.Method;

/**
 * OPPO 音乐
 */
@Collect(targetPackage = "com.heytap.music")
public class HeytapMusic extends BaseLyric {
    @Override
    protected void init() {
    }

    @Override
    protected void onApplicationAfter(@NonNull Context context) {
        super.onApplicationAfter(context);
        HCData.setClassLoader(context.getClassLoader());

        OPPOHelper.mockDevice();
        getMediaMetadataCompatLyric();

        Method method = DexkitCache.findMember("heytap$1", new IDexkit() {
            @NonNull
            @Override
            public BaseData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass("com.allsaints.music.player.thirdpart.MediaSessionHelper")
                        .usingStrings("isCarBluetoothConnected 没有蓝牙连接权限")
                    )
                ).singleOrThrow(() -> new Throwable("Failed to find bluetooth method!!"));
            }
        });

        hook(method,
            new IHook() {
                @Override
                public void after() {
                    setResult(true);
                }
            }
        );
    }
}
