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
package com.hchen.superlyric.provider.salt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 椒盐音乐 Hook 锚点：从 {@code MusicController} 的歌词设置入口取回**原始歌词文本**。
 *
 * <h3>为什么只取 {@code sourceText}</h3>
 * {@code LyricsDocument} 里保存着宿主收到的**原始歌词文本** {@code sourceText}
 * （宿主用它渲染，也用它导出）。它尚未经过宿主内部
 * {@code VerbatimLyricsParserImpl.e()} 对相邻词元的重叠裁切，
 * 因此逐字时戳不会出现 0ms 坍缩。
 *
 * <p>反过来说，只要拿到了 {@code sourceText}，就**不需要**再去反射
 * {@code LyricsLine} / {@code LyricsCell} 那套行/词模型——那正是本项目要根除的末端反射。
 * 本实现只读取 {@code LyricsDocument} 上的**一个 String 字段**。
 *
 * <h3>字段如何定位</h3>
 * 不硬编码字段名（R8 会重命名），而是在 {@code LyricsDocument} 内按**类型唯一性**取字符串字段。
 * 这个类只有一个非静态 String 字段。
 *
 * @author 焕晨HChen
 */
public final class SaltAnchors {
    private static final String TAG = "SaltAnchors";

    private static final String DOCUMENT_CACHE_KEY = "salt_lyrics_document_class_v1";

    /** 锚点：{@code LyricsDocument} 的 toString 格式串。 */
    private static final String DOCUMENT_ANCHOR = "LyricsDocument(sourceText=";

    private static final String CONTROLLER_CLASS = "com.salt.music.service.MusicController";

    private static volatile boolean sInstalled;

    private SaltAnchors() {
    }

    /** 宿主设置歌词时的回调。 */
    public interface LyricsListener {
        /**
         * @param sourceText 原始歌词文本
         * @param songId     宿主显式给出的曲目标识；可能为空
         */
        void onLyrics(@NonNull String sourceText, @NonNull String songId);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志。
     */
    public static void install(@NonNull LyricsListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        try {
            Class<?> documentClass = locateDocumentClass();
            if (documentClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate LyricsDocument");
                return;
            }
            Field sourceField = findSourceTextField(documentClass);
            if (sourceField == null) {
                AndroidLog.logW(TAG, "No String field found on LyricsDocument");
                return;
            }

            Class<?> controllerClass = CoreTool.findClassIfExists(CONTROLLER_CLASS);
            if (controllerClass == null) {
                AndroidLog.logW(TAG, "MusicController not found");
                return;
            }

            for (Method method : controllerClass.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                // 歌词设置入口：(LyricsDocument, String)V
                if (params.length != 2
                    || !params[0].equals(documentClass)
                    || !params[1].equals(String.class)
                    || method.getReturnType() != void.class) {
                    continue;
                }

                method.setAccessible(true);
                CoreTool.hook(method, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            String source = readField(getArg(0), sourceField);
                            if (source.isEmpty()) return;
                            listener.onLyrics(source, asString(getArg(1)));
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in MusicController hook: " + t.getMessage());
                        }
                    }
                });
                AndroidLog.logI(TAG, "Hooked MusicController lyric entry");
                return;
            }
            AndroidLog.logW(TAG, "No matching lyric entry method on MusicController");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install Salt anchors", t);
        }
    }

    @Nullable
    private static Class<?> locateDocumentClass() {
        return DexkitCache.findMember(DOCUMENT_CACHE_KEY, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings(DOCUMENT_ANCHOR)
                    )
                );
                return (classes == null || classes.isEmpty()) ? null : classes.get(0);
            }
        });
    }

    /**
     * 按类型唯一性定位原始文本字段。
     */
    @Nullable
    private static Field findSourceTextField(@NonNull Class<?> documentClass) {
        Field candidate = null;
        for (Field field : documentClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != String.class) continue;
            if (candidate != null) {
                // 出现第二个字符串字段说明类型唯一性假设不成立，宁可不挂
                AndroidLog.logW(TAG, "LyricsDocument has more than one String field; aborting");
                return null;
            }
            field.setAccessible(true);
            candidate = field;
        }
        return candidate;
    }

    @NonNull
    private static String readField(@Nullable Object target, @NonNull Field field) {
        if (target == null) return "";
        try {
            return asString(field.get(target));
        } catch (Throwable ignored) {
            return "";
        }
    }

    @NonNull
    private static String asString(@Nullable Object value) {
        return value instanceof String s ? s.trim() : "";
    }
}
