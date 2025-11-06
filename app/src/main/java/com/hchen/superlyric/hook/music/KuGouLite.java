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
import android.content.Intent;

import androidx.annotation.NonNull;

import com.hchen.collect.Collect;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.HCData;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 酷狗音乐概念版
 */
@Collect(targetPackage = "com.kugou.android.lite")
public class KuGouLite extends LyricRelease {

    @Override
    protected void init() {
        hookTencentTinker();
    }

    @Override
    protected void initApplicationAfter(@NonNull Context context) {
        super.initApplicationAfter(context);
        HCData.setClassLoader(context.getClassLoader());

        try {
            if (Objects.equals(loadPackageParam.processName, "com.kugou.android.lite.support"))
                return;
            if (!enableStatusBarLyric())
                return;

            if (versionCode <= 10935)
                hookLocalBroadcast("android.support.v4.content.LocalBroadcastManager");
            else
                hookLocalBroadcast("androidx.localbroadcastmanager.content.LocalBroadcastManager");

            fixProbabilityCollapse();
        } catch (Throwable e) {
            logE(TAG, e);
        }
    }

    private boolean enableStatusBarLyric() {
        try {
            Method[] methodList = DexkitCache.findMember("kugoulite$1", new IDexkit<MethodDataList>() {
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
                        sendLyric(message);
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
