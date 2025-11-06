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
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Symfonium
 *
 * @author 焕晨HChen
 */
@Collect(targetPackage = "app.symfonik.music.player")
public class Symfonium extends LyricRelease {
    private String lastLyric;

    @Override
    protected void init() {
        Method method = DexkitCache.findMember("Symfonium$1", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create()
                            .usingStrings("replaygain_track_gain")
                        )
                        .usingStrings("replaygain_track_gain")
                    )
                ).single();
            }
        });

        hookConstructor(method.getReturnType(),
            Object.class, Object.class,
            new IHook() {
                @Override
                public void after() {
                    Object arg1 = getArg(0);
                    Object arg2 = getArg(1);
                    if (arg1 == null || arg2 == null) return;

                    if (arg1 instanceof Integer && arg2 instanceof String lyric) {
                        if (Objects.equals(lastLyric, lyric)) return;

                        if (lyric.contains("\n")) {
                            // 裁剪掉翻译
                            lyric = lyric.substring(0, lyric.lastIndexOf("\n"));
                        }
                        sendLyric(lyric);
                        lastLyric = lyric;
                    }
                }
            }
        );
    }
}
