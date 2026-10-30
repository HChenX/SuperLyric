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

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spotify 会话头捕获锚点
 *
 * <p>{@code color-lyrics/v2} 接口需要 Bearer 鉴权，而令牌只存在于宿主自己的网络请求里。
 * 本模块不发起登录、也不持有任何凭证，而是从宿主已经发出的 {@code okhttp3.Headers} 中
 * 旁路复制最新的一份会话头。
 *
 * <p>锚点是 {@code okhttp3.Headers} 的构造器：它是所有请求头的唯一汇聚点，挂一个构造器
 * 即可覆盖全部请求，无需逐个匹配歌词接口的调用点。
 *
 * <p>两级降级：
 * <ol>
 *   <li>能直接引用到 {@code okhttp3.Headers} 时走明文快速通道；</li>
 *   <li>否则用 DexKit 按"含 {@code <init>(String[])} 且实现 {@code Iterable}"的结构指纹
 *       定位，这正是 {@code okhttp3.Headers} 的结构特征，类名被混淆也能命中。</li>
 * </ol>
 *
 * @author 焕晨HChen
 */
public final class SpotifyAnchors {
    private static final String TAG = "SpotifyAnchors";

    private static volatile boolean sInstalled;

    private SpotifyAnchors() {
    }

    /**
     * 安装会话头捕获。幂等
     */
    public static void installHeaderCapture() {
        if (sInstalled) return;
        sInstalled = true;

        AbsHook capture = new AbsHook() {
            @Override
            public void after() {
                Object[] args = getArgs();
                if (args != null && args.length > 0 && args[0] instanceof String[] values) {
                    SpotifyLyricAnalysis.updateHeaders(values);
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
        int hooked = 0;
        for (Class<?> candidate : candidates) {
            try {
                CoreTool.hookAllConstructor(candidate, capture);
                hooked++;
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Failed to hook candidate " + candidate.getName(), t);
            }
        }

        if (hooked == 0) {
            AndroidLog.logW(TAG, "No headers candidate hooked; color-lyrics requests will stay unauthorized");
        } else {
            AndroidLog.logI(TAG, "Hooked " + hooked + " DexKit candidate class(es) by <init>(String[]) fingerprint");
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
        if (precise != null && precise.length > 0) {
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
        if (loose == null) return Collections.emptyList();

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
