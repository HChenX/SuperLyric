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
import android.view.View;

import androidx.annotation.NonNull;

import com.hchen.collect.Collect;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.HCData;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;

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
@Collect(targetPackage = "cn.wenyu.bodian")
public class Bodian extends LyricRelease {
    @Override
    protected void init() {
    }

    @Override
    protected void initApplicationAfter(@NonNull Context context) {
        super.initApplicationAfter(context);
        HCData.setClassLoader(context.getClassLoader());

        Class<?> deskLyricViewClass = findClass("cn.kuwo.player.util.DeskLyricView");
        if (deskLyricViewClass == null) return;

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
            new IHook() {
                @Override
                public void before() {
                    String lyric = (String) getArg(0);
                    sendLyric(lyric);
                }
            }
        );

        hookMethod("io.flutter.plugin.common.MethodCall",
            "argument",
            String.class,
            new IHook() {
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
            new IHook() {
                @Override
                public void after() {
                    View view = (View) thisObject();
                    view.setAlpha(0f);
                }
            }
        );
    }
}
