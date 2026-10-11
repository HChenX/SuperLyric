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
package com.hchen.superlyric.provider.kugou;

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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 酷狗音乐 Hook 锚点：定位并挂载"歌词已就绪"与"读取音频 Hash"这两个点位。
 *
 * <h3>为什么锚在这里</h3>
 * 酷狗的歌词与曲目标识都经过其跨进程 IPC 中枢 {@code @twin:GlobalVariate} 转发。
 * 该中枢是一个**静态键值总线**，键空间由业务常量固定：
 * <ul>
 *   <li>{@code key = 41}——宿主写入 {@code LyricData}，即"这首曲目的歌词已就绪"的权威信号；</li>
 *   <li>{@code key = 207}——读取当前音频 Hash，即这首曲目的跨进程不可变唯一标识。</li>
 * </ul>
 * 只锚定这一个类、只挂一个方法，是"最少最精准 Hook"的直接体现。
 * 特征串 {@code @twin:GlobalVariate} 是酷狗自研 IPC 框架的固定标记，跨版本稳定。
 *
 * <h3>为什么这一层是 Java</h3>
 * 这里的 DexKit 检索与反射调用沿用了已在线上验证多年的写法
 * （尤其是 {@link IDexkit} 允许返回 null 以表达"没搜到"这一约定），
 * 用 Java 可以做到逐字不改。上层的取词逻辑则在 Kotlin 中实现。
 *
 * @author 焕晨HChen
 */
public final class KuGouAnchors {
    private static final String TAG = "KuGouAnchors";

    /** DexKit 缓存键；改名即视为缓存失效。 */
    private static final String CACHE_KEY = "kugou_global_value_class_v1";

    /** 锚点：酷狗自研跨进程 IPC 中枢的固定标记。 */
    private static final String ANCHOR = "@twin:GlobalVariate";

    /** 键空间：歌词数据写入。 */
    private static final int KEY_LYRIC_DATA = 41;

    /** 键空间：音频 Hash 读取。 */
    private static final int KEY_SONG_HASH = 207;

    private static volatile Method sGetValueMethod;
    private static volatile Method sSetValueMethod;

    private KuGouAnchors() {
    }

    /** 宿主写入歌词数据时的回调。 */
    public interface LyricReadyListener {
        void onLyricReady();
    }

    /**
     * 定位并挂载 Hook。可以重复调用（幂等）；搜索失败只记录日志，不抛出。
     *
     * @param listener 宿主写入 {@code LyricData} 时回调
     */
    public static void install(LyricReadyListener listener) {
        try {
            if (sSetValueMethod == null) {
                locateAccessors();
            }
            if (sSetValueMethod == null) {
                AndroidLog.logW(TAG, "GlobalVariate setter not found; hook not installed");
                return;
            }

            CoreTool.hook(sSetValueMethod, new AbsHook() {
                @Override
                public void after() {
                    try {
                        int key = (int) getArg(0);
                        if (key == KEY_LYRIC_DATA) {
                            listener.onLyricReady();
                        }
                    } catch (Throwable t) {
                        AndroidLog.logW(TAG, "Error in GlobalVariate hook: " + t.getMessage());
                    }
                }
            });
            AndroidLog.logI(TAG, "Hooked KuGou GlobalVariate setter");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install KuGou anchors", t);
        }
    }

    /**
     * 读取当前曲目的 32 位音频 Hash。
     *
     * @return Hash 字符串；不可用时返回 null
     */
    @Nullable
    public static String readSongHash() {
        Method getter = sGetValueMethod;
        if (getter == null) {
            return null;
        }
        try {
            Object value = getter.invoke(null, KEY_SONG_HASH, "");
            if (value instanceof String s && !s.trim().isEmpty()) {
                return s.trim();
            }
        } catch (Throwable ignored) {
            // 宿主尚未初始化总线时属正常情况
        }
        return null;
    }

    /**
     * 单次 DexKit 检索定位中枢类，再按签名分离出读/写两个静态方法。
     *
     * 检索**从大到小**：先用锚点串锁定类，再在类内按参数形态区分方法，
     * 而不是直接去搜方法。
     */
    private static void locateAccessors() {
        // 注意 DexkitCache.findMember 的签名是 <T, D> T findMember(String, IDexkit<D>)：
        // 返回类型 T 与 IDexkit 的泛型 D 相互独立。此处 D = ClassData 表示"检索得到一个类"，
        // 而 T = Class<?> 表示框架会把检索结果解析成可用的 Class。
        Class<?> hostClass = DexkitCache.findMember(CACHE_KEY, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings(ANCHOR)
                    )
                );
                return (classes == null || classes.isEmpty()) ? null : classes.get(0);
            }
        });

        if (hostClass == null) {
            AndroidLog.logW(TAG, "DexKit could not locate the GlobalVariate class");
            return;
        }

        for (Method method : hostClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[0] != int.class) continue;

            if (params[1] == String.class && method.getReturnType() == String.class) {
                if (sGetValueMethod == null) {
                    method.setAccessible(true);
                    sGetValueMethod = method;
                }
            } else if (android.os.Parcelable.class.isAssignableFrom(params[1])
                && method.getReturnType() == void.class) {
                if (sSetValueMethod == null) {
                    method.setAccessible(true);
                    sSetValueMethod = method;
                }
            }
        }

        AndroidLog.logI(TAG, "Located KuGou accessors: get=" + sGetValueMethod
            + ", set=" + sSetValueMethod);
    }
}
