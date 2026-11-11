/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.spotify;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.shared.SessionHeadersFeed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spotify 会话头捕获锚点
 */
public final class SpotifyAnchors {
    private static final String TAG = "SpotifyAnchors";

    private static volatile boolean sInstalled;
    private static volatile SessionHeadersFeed sFeed;

    private SpotifyAnchors() {
    }

    /**
     * 安装会话头捕获。幂等
     */
    public static void installHeaderCapture(@NonNull SessionHeadersFeed feed) {
        sFeed = feed;
        if (sInstalled) return;
        sInstalled = true;

        AbsHook capture = new AbsHook() {
            @Override
            public void after() {
                Object[] args = getArgs();
                if (args.length > 0 && args[0] instanceof String[] values) {
                    sFeed.update(values);
                }
            }
        };

        // 明文快速通道
        try {
            Class<?> headersClass = CoreTool.findClass("okhttp3.Headers");
            CoreTool.hookAllConstructor(headersClass, capture);
            AndroidLog.logI(TAG, "Hooked plaintext okhttp3.Headers");
            return;
        } catch (Throwable t) {
            AndroidLog.logD(TAG, "Plaintext okhttp3.Headers unavailable, falling back to DexKit");
        }

        // 结构指纹兜底
        List<Class<?>> candidates = findHeaderCandidateClasses();
        if (candidates.isEmpty()) {
            AndroidLog.logW(TAG, "No headers candidate hooked; color-lyrics requests will stay unauthorized");
            return;
        }
        List<Executable> constructors = new ArrayList<>();
        for (Class<?> candidate : candidates) {
            Collections.addAll(constructors, candidate.getDeclaredConstructors());
        }
        try {
            CoreTool.hookAll(constructors.toArray(new Executable[0]), capture);
            AndroidLog.logI(TAG, "Hooked " + constructors.size() + " constructor(s) in "
                + candidates.size() + " DexKit candidate class(es) by <init>(String[]) fingerprint");
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to hook headers candidates", t);
        }
    }

    @NonNull
    private static List<Class<?>> findHeaderCandidateClasses() {
        // 精确指纹：含 <init>(String[]) 且实现 Iterable
        Class<?>[] precise = DexkitCache.findMember("spotify$headers_ctor_iterable",
            new IDexkit<ClassDataList>() {
                @NonNull
                @Override
                public ClassDataList dexkit(@NonNull DexKitBridge bridge) {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .addInterface(ClassMatcher.create(Iterable.class))
                            .addMethod(MethodMatcher.create()
                                .name("<init>")
                                .paramTypes("java.lang.String[]")
                            )
                        )
                    );
                }
            });
        if (precise.length > 0) {
            return new ArrayList<>(List.of(precise));
        }

        // 宽松指纹：仅含 <init>(String[])
        //
        // 这个判据会命中所有带 String[] 构造器的类，其中绝大多数与 okhttp 无关。
        // 给这些类逐个挂构造器钩子会抬高宿主开销，违背最少最精准的约束。
        // 因此在 DexKit 结果之上再收一道：只保留像 Headers 的类，即非接口、非注解、
        // 非抽象、非枚举且自带字段（Headers 持有 String[] namesAndValues）
        Class<?>[] loose = DexkitCache.findMember("spotify$headers_ctor",
            new IDexkit<ClassDataList>() {
                @NonNull
                @Override
                public ClassDataList dexkit(@NonNull DexKitBridge bridge) {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .addMethod(MethodMatcher.create()
                                .name("<init>")
                                .paramTypes("java.lang.String[]")
                            )
                        )
                    );
                }
            });
        List<Class<?>> filtered = new ArrayList<>(loose.length);
        for (Class<?> candidate : loose) {
            int modifiers = candidate.getModifiers();
            if (candidate.isInterface() || candidate.isAnnotation() || candidate.isEnum()) continue;
            if (Modifier.isAbstract(modifiers)) continue;
            if (candidate.getDeclaredFields().length == 0) continue;
            filtered.add(candidate);
        }
        AndroidLog.logI(TAG, "Loose fingerprint matched " + loose.length
            + " class(es), narrowed to " + filtered.size() + " header-like candidate(s)");
        return filtered;
    }
}
