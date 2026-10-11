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
package com.hchen.superlyric.provider.bodian;

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

import java.lang.reflect.Constructor;

/**
 * 波点音乐 Hook 锚点：在歌词引擎的构造入口截获**本地原生歌词文件路径**
 *
 * <h3>为什么锚在这里</h3>
 * {@code LyricsRunner4Flutter} 是波点把歌词交给 Flutter 渲染层的唯一咽喉，
 * 其构造参数 {@code (Music, int, String lyricPath)} 中的第三个参数就是
 * 宿主自己下载好的歌词文件路径。这是**未经宿主任何加工**的原始载荷。
 *
 * <p>相对地，从宿主的行/词模型里反射抠数据要走
 * {@code VerbatimLyricsParserImpl.e()}，而该实现会对相邻词元做重叠裁切，
 * 造成逐字时戳 0ms 坍缩、动效卡死；绕开它即可。
 *
 * <h3>代价</h3>
 * 只挂一个构造器，仅此一处。
 *
 * @author 焕晨HChen
 */
public final class BodianAnchors {
    private static final String TAG = "BodianAnchors";

    private static final String CACHE_KEY = "bodian_lyrics_runner_class_v1";

    /** 锚点：Flutter 桥接层的类名，跨版本稳定。 */
    private static final String ANCHOR = "LyricsRunner4Flutter";

    private static volatile boolean sInstalled;

    private BodianAnchors() {
    }

    /** 截获到歌词文件路径（或确认没有）时的回调。 */
    public interface LyricFileListener {
        /**
         * @param filePath 本地歌词文件路径；为空表示宿主本次没有歌词
         */
        void onLyricFile(@NonNull String filePath,
                         @NonNull String title,
                         @NonNull String artist,
                         @NonNull String album,
                         @NonNull String rid);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志。
     */
    public static void install(@NonNull LyricFileListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        try {
            Class<?> runnerClass = locateRunnerClass();
            if (runnerClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate LyricsRunner4Flutter");
                return;
            }

            for (Constructor<?> constructor : runnerClass.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 3) continue;

                constructor.setAccessible(true);
                CoreTool.hook(constructor, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            Object music = getArg(0);
                            String filePath = asString(getArg(2));
                            listener.onLyricFile(
                                filePath,
                                readString(music, "getName"),
                                readString(music, "getArtist"),
                                readString(music, "getAlbum"),
                                readField(music, "rid")
                            );
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in LyricsRunner4Flutter hook: " + t.getMessage());
                        }
                    }
                });
                AndroidLog.logI(TAG, "Hooked LyricsRunner4Flutter constructor");
                return;
            }
            AndroidLog.logW(TAG, "No 3-parameter constructor found on LyricsRunner4Flutter");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install Bodian anchors", t);
        }
    }

    /**
     * 检索锚点类；优先取非内部类（内部类通常是伴生类，构造签名不同）。
     */
    @Nullable
    private static Class<?> locateRunnerClass() {
        return DexkitCache.findMember(CACHE_KEY, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings(ANCHOR)
                    )
                );
                if (classes == null || classes.isEmpty()) return null;
                for (ClassData data : classes) {
                    if (!data.getName().contains("$")) return data;
                }
                return classes.get(0);
            }
        });
    }

    @NonNull
    private static String readString(@Nullable Object target, @NonNull String getter) {
        if (target == null) return "";
        try {
            return asString(CoreTool.callMethodIfExists(target, getter));
        } catch (Throwable ignored) {
            return "";
        }
    }

    @NonNull
    private static String readField(@Nullable Object target, @NonNull String field) {
        if (target == null) return "";
        try {
            return asString(CoreTool.getFieldIfExists(target, field));
        } catch (Throwable ignored) {
            return "";
        }
    }

    @NonNull
    private static String asString(@Nullable Object value) {
        return value instanceof String s ? s.trim() : "";
    }
}
