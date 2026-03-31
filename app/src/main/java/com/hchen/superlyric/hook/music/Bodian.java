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
import android.view.View;

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.hook.LyricRelease;
import com.hchen.superlyricapi.AcquisitionMode;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 波点音乐
 *
 * @author 焕晨HChen
 */
@AutoHook(targetPackage = "cn.wenyu.bodian")
public final class Bodian extends LyricRelease {
    @Override 
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
    }

    @Override 
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);

        Class<?> deskLyricViewClass = findClass("cn.kuwo.player.util.DeskLyricView");
        Method methodData = DexkitCache.findMember("bodian$1", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(deskLyricViewClass)
                        .paramCount(1)
                        .paramTypes(String.class)
                        .returnType(float.class)
                        .addInvoke("Landroid/graphics/Paint;->measureText(Ljava/lang/String;)F")
                    )
                ).singleOrThrow(() -> new Throwable("Failed to find lyric method!!"));
            }
        });
        hook(methodData,
            new AbsHook() {
                @Override
                public void before() {
                    String lyric = (String) getArg(0);
                    sendLyric(lyric, 0, AcquisitionMode.HOOK_LYRIC);
                }
            }
        );

        hookMethod("io.flutter.plugin.common.MethodCall",
            "argument",
            String.class,
            new AbsHook() {
                @Override
                public void before() {
                    String key = (String) getArg(0);
                    if (Objects.equals(key, "isShow"))
                        setResult(true);
                }
            }
        );

        hookMethod("cn.kuwo.audio_player.StatusBarLyricLayout",
            "getLayoutBinding",
            new AbsHook() {
                @Override
                public void after() {
                    View view = (View) getThisObject();
                    view.setAlpha(0f);
                }
            }
        );
    }
}
