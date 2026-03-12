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
import android.content.Intent;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.hchen.collect.Collect;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;
import com.hchen.superlyricapi.AcquisitionMode;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 酷狗音乐
 */
@Collect(targetPackage = "com.kugou.android")
public final class KuGou extends LyricRelease {
    @Override
    protected void init() {
        hookTencentTinker();
    }

    @Override
    protected void initApplicationAfter(@NonNull Context context) {
        super.initApplicationAfter(context);

        try {
            if (!enableStatusBarLyric()) return;

            if (versionCode <= 12009) {
                hookLocalBroadcast("android.support.v4.content.LocalBroadcastManager");
            } else {
                hookMeizuLyric();
                fixProbabilityCollapse();
            }
        } catch (Throwable e) {
            logE(TAG, e);
        }
    }

    private boolean enableStatusBarLyric() {
        try {
            Method[] methodList = DexkitCache.findMember("kugou$1", new IDexkit<MethodDataList>() {
                @NonNull
                @Override
                public MethodDataList dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create()
                                .usingStrings("key_status_bar_lyric_open")
                            )
                            .usingStrings("key_status_bar_lyric_open")
                        )
                    );
                }
            });

            Method[] methods = new Method[2];
            for (Method m : methodList) {
                if (Objects.equals(m.getReturnType(), boolean.class)) methods[0] = m;
                else methods[1] = m;
            }

            hook(methods[0], new IHook() {
                @Override
                public void before() {
                    callThisMethod(methods[1], true);
                    setResult(true);
                }
            });
            hook(methods[1], setArg(0, true));
        } catch (Throwable e) {
            logE(TAG, e);
            return false;
        }
        return true;
    }

    private Pair<String, Object> pair;

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
        Method staticMethod = findMethodPro(clazz)
            .withReturnClass(clazz)
            .single().obtain();
        Method getLyricDataMethod = findMethodPro(clazz)
            .withReturnClass(findClass("com.kugou.framework.lyric.LyricData"))
            .single().obtain();
        Method getHashMethod = findMethodPro(clazz)
            .withParamClasses(int.class)
            .withReturnClass(String.class)
            .single().obtain();

        findMethodPro(statusBarLyricClass)
            .withParamClasses(Context.class, String.class, boolean.class)
            .single()
            .hook(
                new IHook() {
                    @Override public void before() {
                        String lyric = (String) getArg(1);
                        boolean isClose = (boolean) getArg(2);

                        if (!isClose && lyric != null && !lyric.isEmpty()) {
                            Object c = callStaticMethod(staticMethod);
                            Object lyricData = callMethod(c, getLyricDataMethod, 41);
                            String hash = (String) callMethod(c, getHashMethod, 207);
                            if (lyricData != null && hash != null) {
                                pair = new Pair<>(hash, lyricData);
                            }
                            if (lyricData == null && pair != null && TextUtils.equals(pair.first, hash)) {
                                lyricData = pair.second;
                            }
                            if (lyricData != null) {
                                SuperLyricData data = new SuperLyricData();

                                SuperLyricWord[] lyricWords = null;
                                int currentLine = (int) getThisField(currentLineField);
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

                                    data.setLyric(sb.toString());
                                    data.setDelay(Math.toIntExact(lyricDelay));
                                    data.setLyricWordData(lyricWords);
                                }

                                String[][] translateWordss = (String[][]) callMethod(lyricData, "getTranslateWords");
                                if (translateWordss != null) {
                                    String[] translateWords = translateWordss[currentLine];
                                    if (translateWords != null) {
                                        StringBuilder sb = new StringBuilder();
                                        for (String translateWord : translateWords) {
                                            sb.append(translateWord);
                                        }
                                        data.setTranslation(sb.toString());
                                    }
                                }

                                // 罗马音之类的
                                // String[][] transliterationWordss = (String[][]) callMethod(lyricData, "getTransliterationWords");
                                // if (transliterationWordss != null) {
                                //     String[] transliterationWords = transliterationWordss[currentLine];
                                //     AndroidLog.logI(TAG, "TransliterationWords: " + Arrays.toString(transliterationWords));
                                // }
                                data.setAcquisitionMode(AcquisitionMode.HOOK_LYRIC);
                                sendSuperLyricData(data);
                            }
                        } else {
                            sendStop();
                        }
                    }
                }
            );
    }

    private void hookLocalBroadcast(String clazz) {
        hookMethod(clazz,
            "sendBroadcast",
            Intent.class,
            new IHook() {
                @Override
                public void before() {
                    Intent intent = (Intent) getArg(0);
                    if (intent == null) return;

                    String action = intent.getAction();
                    String message = intent.getStringExtra("lyric");
                    if (message == null) return;

                    if (Objects.equals(action, "com.kugou.android.update_meizu_lyric")) {
                        sendLyric(message, 0, AcquisitionMode.HOOK_LYRIC);
                    }
                }
            }
        );
    }

    private void fixProbabilityCollapse() {
        hookMethod("com.kugou.framework.hack.ServiceFetcherHacker$FetcherImpl",
            "createServiceObject",
            Context.class, Context.class,
            new IHook() {
                @Override
                public void after() {
                    String mServiceName = (String) getThisField("serviceName");
                    if (mServiceName == null) return;

                    if (mServiceName.equals(Context.WIFI_SERVICE)) {
                        if (getThrowable() != null) {
                            setThrowable(null);
                            setResult(null);
                        }
                    }
                }
            }
        );
    }
}
