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
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.helper.MeizuHelper;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * 网易云音乐
 */
@AutoHook(targetPackage = "com.netease.cloudmusic")
public final class Netease extends AbsPublisher {
    @Override
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        hookTencentTinker();
        if (hasClass("android.app.Instrumentation")) {
            hookMethod("android.app.Instrumentation",
                "newApplication",
                ClassLoader.class, String.class, Context.class,
                new AbsHook() {
                    @Override
                    public void before() {
                        if (Objects.equals("com.netease.nis.wrapper.MyApplication", getArg(1))) {
                            setArg(1, "com.netease.cloudmusic.CloudMusicApplication");
                            logD(TAG, "Hooked netease wrapper class");
                        }
                    }
                }
            );
        }
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);

        if (mVersionCode >= 8000041) {
            MeizuHelper.shallowLayerDeviceMock();
            // MeizuHelper.hookNotificationLyric();

            Class<?> statusBarLyricController = DexkitCache.findMember("netease$3", new IDexkit<ClassData>() {
                @NonNull
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("StatusBarLyricController")
                        )
                    ).single();
                }
            });
            Method lyricMethod = null;
            for (Field declaredField : statusBarLyricController.getDeclaredFields()) {
                try {
                    lyricMethod = declaredField.getType().getDeclaredMethod("onLyricText", String.class, String.class);
                    break;
                } catch (NoSuchMethodException ignore) {
                }
            }

            Objects.requireNonNull(lyricMethod);
            hook(lyricMethod,
                new AbsHook() {
                    @Override
                    public void before() {
                        List<?> mSentences = (List<?>) getField(getThisObject(), "mSentences");
                        int mCurLyricIndex = (int) getField(getThisObject(), "mCurLyricIndex");

                        Object mSentence = mSentences.get(mCurLyricIndex);
                        String lyric = (String) callMethod(mSentence, "getContent");
                        String translate = (String) callMethod(mSentence, "getTranslateContent");
                        int endTime = (int) callMethod(mSentence, "getEndTime");
                        int startTime = (int) callMethod(mSentence, "getStartTime");

                        sendLyric(
                            lyric,
                            endTime - startTime,
                            new SuperLyricData()
                                .setTranslation(
                                    new SuperLyricLine(translate)
                                )
                        );
                    }
                }
            );

            Method method = DexkitCache.findMember("netease$1", new IDexkit<MethodData>() {
                @NonNull
                @Override
                public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create()
                                .usingStrings("KEY_SHOW_LOCK_SCREEN_PERMISSION")
                            )
                            .usingStrings("KEY_SHOW_LOCK_SCREEN_PERMISSION")
                        )
                    ).single();
                }
            });
            hook(method, returnResult(null));

            Class<?> clazz = DexkitCache.findMember("netease$2", new IDexkit<ClassData>() {
                @NonNull
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingStrings("com/netease/cloudmusic/module/lyric/flyme/StatusBarLyricSettingManager.class:setSwitchStatus:(Z)V")
                        )
                    ).single();
                }
            });
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getReturnType().equals(boolean.class)) {
                    hook(m, returnResult(true));
                } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(boolean.class)) {
                    hook(m, setArg(0, true));
                } else if (m.getReturnType().equals(SharedPreferences.class)) {
                    hook(m, new AbsHook() {
                        @Override
                        public void after() {
                            SharedPreferences sp = (SharedPreferences) getResult();
                            sp.edit().putBoolean("status_bar_lyric_setting_key", true).apply();
                        }
                    });
                }
            }
        } else {
            hookMediaMetadataLyric();
        }
    }
}
