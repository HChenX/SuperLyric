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
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.helper.KuGouHelper;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 酷狗音乐
 */
@HookThis(targetPackage = "com.kugou.android")
public final class KuGou extends AbsPublisher {
    @Override
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        fuckTencentTinker();
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);

        KuGouHelper.enableStatusBarLyric("kugou$1");
        if (mVersionCode <= 12009) {
            KuGouHelper.hookLocalBroadcast("android.support.v4.content.LocalBroadcastManager");
        } else {
            try {
                hookMeizuLyric();
            } catch (Throwable e) {
                logW(TAG, e);
                KuGouHelper.hookLocalBroadcast("androidx.localbroadcastmanager.content.LocalBroadcastManager");
            }
            KuGouHelper.fixProbabilityCollapse();
        }
    }

    private void hookMeizuLyric() {
        Class<?> statusBarLyricClass = DexkitCache.findMember("kugou$2", new IDexkit<ClassData>() {
            @NonNull
            @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings("RemoteLyricView status bar lyric: ")
                    )
                ).single();
            }
        });
        Field currentLineField = DexkitCache.findMember("kugou$3", new IDexkit<FieldData>() {
            @NonNull
            @Override
            public FieldData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findField(FindField.create()
                    .matcher(FieldMatcher.create()
                        .declaredClass(statusBarLyricClass)
                        .type(int.class)
                    )
                ).single();
            }
        });
        Class<?> clazz = DexkitCache.findMember("kugou$4", new IDexkit<ClassData>() {
            @NonNull
            @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings("GlobalVariableAccess getStChineseConverter stChineseConverter downloadConfig")
                    )
                ).single();
            }
        });
        Method staticMethod = Arrays.stream(clazz.getDeclaredMethods())
            .filter(new Predicate<Method>() {
                @Override
                public boolean test(Method method) {
                    return Objects.equals(method.getReturnType(), clazz);
                }
            }).findFirst().orElseThrow();

        Method getLyricDataMethod = Arrays.stream(clazz.getDeclaredMethods())
            .filter(new Predicate<Method>() {
                final Class<?> lyricDataClass = findClass("com.kugou.framework.lyric.LyricData");

                @Override
                public boolean test(Method method) {
                    return Objects.equals(method.getReturnType(), lyricDataClass);
                }
            }).findFirst().orElseThrow();

        Method getHashMethod = Arrays.stream(clazz.getDeclaredMethods())
            .filter(new Predicate<Method>() {
                @Override
                public boolean test(Method method) {
                    return Objects.equals(method.getReturnType(), String.class) &&
                        method.getParameterCount() == 1 &&
                        Objects.equals(method.getParameterTypes()[0], int.class);
                }
            }).findFirst().orElseThrow();

        hook(Arrays.stream(statusBarLyricClass.getDeclaredMethods())
                .filter(new Predicate<Method>() {
                    @Override
                    public boolean test(Method method) {
                        return method.getParameterCount() == 3 &&
                            Arrays.deepEquals(new Class<?>[]{Context.class, String.class, boolean.class}, method.getParameterTypes());
                    }
                }).findFirst().orElseThrow(),
            new AbsHook() {
                private Pair<String, Object> pair;

                @Override
                public void before() {
                    String lyric = (String) getArg(1);
                    boolean isClose = (boolean) getArg(2);

                    if (!isClose && lyric != null && !lyric.isEmpty()) {
                        Object c = callStaticMethod(staticMethod);
                        Object lyricData = callMethod(getLyricDataMethod, c, 41);
                        String hash = (String) callMethod(getHashMethod, c, 207);
                        if (lyricData != null && hash != null) {
                            pair = new Pair<>(hash, lyricData);
                        }
                        if (lyricData == null && pair != null && TextUtils.equals(pair.first, hash)) {
                            lyricData = pair.second;
                        }

                        if (lyricData != null) {
                            SuperLyricData data = new SuperLyricData();

                            SuperLyricWord[] lyricWords = null;
                            int currentLine = (int) getField(currentLineField, getThisObject());
                            String[][] wordss = (String[][]) callMethod(lyricData, "getWords");
                            long[][] wordBegins = (long[][]) callMethod(lyricData, "getWordBeginTime");
                            long[][] wordDelays = (long[][]) callMethod(lyricData, "getWordDelayTime");
                            if (wordss != null) {
                                String[] words = wordss[currentLine];
                                if (words == null) {
                                    return;
                                }

                                long lyricDelay = 0L;
                                long[] begins = wordBegins[currentLine];
                                long[] delays = wordDelays[currentLine];
                                lyricWords = new SuperLyricWord[words.length];
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < words.length; i++) {
                                    long begin = begins[i];
                                    long delay = delays[i];
                                    lyricDelay = lyricDelay + delay;

                                    sb.append(words[i]);
                                    lyricWords[i] = new SuperLyricWord(words[i], (int) begin, (int) (begin + delay));
                                }

                                data.setLyric(
                                    new SuperLyricLine(
                                        sb.toString(),
                                        lyricWords,
                                        lyricDelay
                                    )
                                );
                            }

                            String[][] translateWordss = (String[][]) callMethod(lyricData, "getTranslateWords");
                            if (translateWordss != null) {
                                String[] translateWords = translateWordss[currentLine];
                                if (translateWords != null) {
                                    StringBuilder sb = new StringBuilder();
                                    for (String translateWord : translateWords) {
                                        sb.append(translateWord);
                                    }
                                    data.setTranslation(
                                        new SuperLyricLine(
                                            sb.toString()
                                        )
                                    );
                                }
                            }

                            // 罗马音之类的
                            // String[][] transliterationWordss = (String[][]) callMethod(lyricData, "getTransliterationWords");
                            // if (transliterationWordss != null) {
                            //     String[] transliterationWords = transliterationWordss[currentLine];
                            //     AndroidLog.logI(TAG, "TransliterationWords: " + Arrays.toString(transliterationWords));
                            // }
                            sendLyric(data);
                        }
                    } else {
                        sendStop();
                    }
                }
            }
        );
    }
}
