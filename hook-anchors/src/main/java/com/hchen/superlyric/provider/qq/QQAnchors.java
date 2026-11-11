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
package com.hchen.superlyric.provider.qq;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.utils.LyricDiag;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * QQ 音乐 Hook 锚点：从歌词加载请求的载体上取回当前曲目的官方标识
 *
 * <p>QQ 音乐 20.9+ 的歌词行/词模型位于 {@code com.lyricengine.base.*}，深度混淆且结构
 * 随版本变动。但无论模型怎么改，宿主都必须先把 {@code LyricLoadBean}（含
 * {@code SongInfo}）交给歌词引擎，这个构造入口是稳定的。
 */
public final class QQAnchors {
    private static final String TAG = "QQAnchors";

    private static final String CACHE_KEY = "qqmusic_lyric_load_bean_class";

    private static final String ANCHOR = "LyricLoadBean(songInfo=";

    private static final String TARGET_PROCESS_SUFFIX = ":QQPlayerService";

    private static volatile boolean sInstalled;

    private static volatile String sLastRawToString;
    private static volatile String sLastRawShortMessage;

    private QQAnchors() {
    }

    /**
     * 取回当前曲目标识时的回调
     */
    public record SongInfoEvent(@NonNull String rawToString, @NonNull String shortMessage) {
    }

    public interface SongInfoListener {
        void onSongInfo(@NonNull SongInfoEvent event);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志
     *
     * @param listener 宿主加载某曲目歌词时回调
     */
    public static void install(@NonNull SongInfoListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        String process = currentProcessName();
        if (process != null && process.indexOf(':') >= 0 && !process.endsWith(TARGET_PROCESS_SUFFIX)) {
            // 用 I 级而非 D 级：判据是写死的子进程名，宿主改名后本层不会挂载且没有
            // 其他信号，默认日志级别下必须能看见未挂载
            AndroidLog.logI(TAG, "Skipping lyric anchor in non-player sub-process: " + process
                + " (expected suffix: " + TARGET_PROCESS_SUFFIX + ")");
            return;
        }

        try {
            Class<?> beanClass = locateBeanClass();
            if (beanClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate LyricLoadBean");
                return;
            }

            String ctors = Arrays.stream(beanClass.getDeclaredConstructors())
                .map(Constructor::toGenericString)
                .collect(Collectors.joining("\n  ", "\n  ", ""));
            LyricDiag.dumpPayload(TAG, "CTORS", beanClass.getName() + " ctors:" + ctors);

            List<Constructor<?>> targets = new ArrayList<>();
            for (Constructor<?> constructor : beanClass.getDeclaredConstructors()) {
                // Kotlin 会为一个 8 参主构造器额外合成一个 (…, int, DefaultConstructorMarker) 桥接。
                // 按参数个数筛选是不可靠的：一旦选中桥接，而宿主实际走 8 参路径，
                // Hook 就永远不会触发。此处按首参类型精确筛选。
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length == 0 || !isSongInfo(parameterTypes[0])) continue;
                constructor.setAccessible(true);
                targets.add(constructor);
            }
            if (targets.isEmpty()) {
                AndroidLog.logW(TAG, "No SongInfo-typed LyricLoadBean constructor found");
                return;
            }

            CoreTool.hookAll(targets.toArray(new Constructor<?>[0]), new AbsHook() {
                @Override
                public void after() {
                    dispatch(getArg(0) != null ? getArg(0) : getThisObject(), listener);
                }
            });
            for (Constructor<?> constructor : targets) {
                AndroidLog.logD(TAG, "Hooked LyricLoadBean ctor: " + constructor.toGenericString());
            }
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install QQ anchors", t);
        }
    }

    /**
     * 首参是否为宿主曲目信息类型
     * <p>
     * 这里出现的 {@code SongInfo} 是宿主的业务类名（非混淆产物），跨版本稳定；
     * 判据用后缀匹配以容忍包路径调整。
     */
    private static boolean isSongInfo(@Nullable Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        return "SongInfo".equals(name) || name.endsWith(".SongInfo");
    }

    private static Class<?> locateBeanClass() {
        return DexkitCache.findMember(CACHE_KEY, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings(ANCHOR)
                    )
                );
                return classes.firstOrNull();
            }
        });
    }

    /**
     * 把宿主 {@code SongInfo} 的原始字符串交给 hooks-id。
     */
    private static void dispatch(@NonNull Object songInfo, @NonNull SongInfoListener listener) {
        // 未解析的原始数据：这两个串正是 hooks-id 正则的输入，必须原样可见。
        // 宿主会反复重建同一份 SongInfo，因此只在内容变化时输出。
        String raw = String.valueOf(songInfo);
        if (!raw.equals(sLastRawToString)) {
            sLastRawToString = raw;
            LyricDiag.dumpPayload(TAG, "RAW-toString", raw);
        }

        String shortMessage = "";
        try {
            Object value = CoreTool.callMethodIfExists(songInfo, "shortMessage");
            if (value instanceof String text) {
                shortMessage = text;
                if (!text.equals(sLastRawShortMessage)) {
                    sLastRawShortMessage = text;
                    LyricDiag.dumpPayload(TAG, "RAW-shortMessage", text);
                }
            }
        } catch (Throwable ignored) {
            // shortMessage 不可用时仍交 toString
        }

        listener.onSongInfo(new SongInfoEvent(raw, shortMessage));
    }

    @Nullable
    private static String currentProcessName() {
        try {
            return Application.getProcessName();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
