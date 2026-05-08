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

import static com.hchen.hooktool.core.CoreTool.getField;
import static com.hchen.hooktool.core.CoreTool.hook;
import static com.hchen.hooktool.core.CoreTool.hookMethod;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.hook.AbsPublisher;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 酷狗音乐系列共享逻辑
 * <p>
 * 提取状态栏歌词开关、崩溃修复和本地广播 Hook。
 *
 * @author 焕晨HChen
 */
public final class KuGouHelper {

    /**
     * 强制开启状态栏歌词
     *
     * @param dexkitKey DexKit 缓存键（区分酷狗/酷狗概念版）
     */
    public static void enableStatusBarLyric(@NonNull String dexkitKey) {
        Method[] ms = DexkitCache.findMember(dexkitKey, new IDexkit<MethodDataList>() {
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
        for (Method m : ms) {
            if (Objects.equals(m.getReturnType(), boolean.class)) {
                methods[0] = m;
            } else {
                methods[1] = m;
            }
        }

        final Method setterMethod = methods[1];
        hook(methods[0], new AbsHook() {
            @Override
            public void before() {
                try {
                    setterMethod.invoke(getThisObject(), true);
                } catch (Exception ignored) {
                }
                setResult(true);
            }
        });
        hook(methods[1], AbsPublisher.setArg(0, true));
    }

    /**
     * Hook 本地广播拦截酷狗魅族歌词
     *
     * @param clazz 广播管理器类名
     */
    public static void hookLocalBroadcast(@NonNull String clazz) {
        hookMethod(clazz,
            "sendBroadcast",
            Intent.class,
            new AbsHook() {
                @Override
                public void before() {
                    Intent intent = (Intent) getArg(0);
                    if (intent == null) return;

                    String action = intent.getAction();
                    String message = intent.getStringExtra("lyric");
                    if (message == null) return;

                    if (Objects.equals(action, "com.kugou.android.update_meizu_lyric")) {
                        AbsPublisher.sendLyric(message);
                    }
                }
            }
        );
    }

    /**
     * 修复概率性崩溃（WiFi ServiceFetcher 空指针）
     */
    public static void fixProbabilityCollapse() {
        hookMethod("com.kugou.framework.hack.ServiceFetcherHacker$FetcherImpl",
            "createServiceObject",
            Context.class, Context.class,
            new AbsHook() {
                @Override
                public void after() {
                    String serviceName = (String) getField(getThisObject(), "serviceName");
                    if (serviceName == null) return;

                    if (serviceName.equals(Context.WIFI_SERVICE)) {
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
