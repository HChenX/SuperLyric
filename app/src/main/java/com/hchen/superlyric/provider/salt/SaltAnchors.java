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
import com.hchen.superlyric.utils.LyricDiag;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Constructor;
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

    /** 宿主的曲目模型类；带 {@code @Keep}，类名与 getter 名都是稳定的。 */
    private static final String SONG_CLASS = "com.salt.music.data.entry.Song";

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

        /**
         * 宿主的**真实曲目信息**。
         *
         * <p>椒盐在开启蓝牙歌词后会把 {@code METADATA_KEY_TITLE} 写成当前歌词行或制作人名单、
         * 把 {@code ARTIST} 拼成 {@code "歌手 - 曲名"}——元数据完全不可信。
         * 这个回调给出的是宿主自己的 {@code Song} 对象里的原始字段，且其
         * {@code id} 与 {@link #onLyrics} 的 {@code songId} 完全一致，可精确关联。
         *
         * @param songId     曲目标识，与 {@link #onLyrics} 的 songId 同源
         * @param title      真实曲名
         * @param artist     真实歌手
         * @param durationMs 曲目时长（毫秒）
         */
        default void onSong(
            @NonNull String songId,
            @NonNull String title,
            @NonNull String artist,
            long durationMs
        ) {
        }
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

            // 探针：独立挂载，失败不影响主取词路径
            installSongProbe(controllerClass, listener);

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
     * 挂载宿主那一个"曲目已切换"回调，取回 {@code com.salt.music.data.entry.Song}。
     *
     * <h3>为什么是这条路</h3>
     * 先试过 {@code LyriconSongSnapshot} 的构造器，真机实测**切歌时零触发**——
     * 那是 Lyricon 歌词渲染器的快照，只有歌词页面订阅时才会生成，不能作为取数依据。
     * 而 {@code MusicController} 内部以 {@code Song} 为参数的回调稳定触发，
     * 且 {@code Song} 带 {@code @Keep}、{@code Song.getId()} 与
     * {@link LyricsListener#onLyrics} 的 songId **完全一致**，可精确关联。
     *
     * <h3>为什么按签名而不是按名字</h3>
     * 触发的方法名是混淆产物（实测为单个生僻字符），无法跨版本硬编码；
     * 而参数类型序列 {@code (Song, long, long, Long)} 由 {@code @Keep} 的
     * {@code Song} 与基本类型构成，是稳定可辨的。
     *
     * <h3>签名失配时不退回"广撒网"</h3>
     * 宿主若改动了这个签名，本层就取不到真实曲目信息（退化为使用元数据），
     * 只记一条警告。刻意不去挂载"所有以 Song 为参数的方法"：
     * 那会牵进一批与曲目切换无关的方法，违背"最少最精准"。
     * 真遇到签名变更，再针对具体情况处理。
     */
    private static void installSongProbe(
        @NonNull Class<?> controllerClass,
        @NonNull LyricsListener listener
    ) {
        try {
            Class<?> songClass = CoreTool.findClassIfExists(SONG_CLASS);
            if (songClass == null) {
                AndroidLog.logW(TAG, SONG_CLASS + " not found");
                return;
            }

            int hooked = hookSongCallback(
                controllerClass, listener,
                new Class<?>[]{songClass, long.class, long.class, Long.class}
            );
            if (hooked == 0) {
                AndroidLog.logW(TAG, "Song callback signature not found; real track info unavailable");
                return;
            }

            AndroidLog.logD(TAG, "Hooked " + hooked + " Song callback(s)");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install song probe", t);
        }
    }

    /** 按精确参数序列挂载静态 void 方法。 */
    private static int hookSongCallback(
        @NonNull Class<?> controllerClass,
        @NonNull LyricsListener listener,
        @NonNull Class<?>[] expected
    ) {
        int hooked = 0;
        for (Method method : controllerClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (method.getReturnType() != void.class) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length != expected.length || !params[0].equals(expected[0])) continue;

            boolean same = true;
            for (int i = 1; i < params.length; i++) {
                if (!params[i].equals(expected[i])) {
                    same = false;
                    break;
                }
            }
            if (!same) continue;

            hookOnSong(method, 0, listener);
            hooked++;
        }
        return hooked;
    }

    private static void hookOnSong(
        @NonNull Method method,
        int songArgIndex,
        @NonNull LyricsListener listener
    ) {
        method.setAccessible(true);
        CoreTool.hook(method, new AbsHook() {
            @Override
            public void after() {
                try {
                    reportSong(getArg(songArgIndex), listener);
                } catch (Throwable ignored) {
                    // 绝不干扰宿主
                }
            }
        });
    }

    /** 已转储过的曲目标识，避免热路径刷屏。 */
    private static volatile String sLastDumpedSongId;

    private static void reportSong(@Nullable Object song, @NonNull LyricsListener listener) {
        if (song == null) return;

        String songId = invokeOrNull(song, "getId");
        if (songId == null || songId.isEmpty()) {
            // 取不到宿主标识就没法把歌词与曲目关联起来。曾经失败时返回 "<XXXException>" 这类
            // 标记串并当成真实值传下去，songStore 以标记为键、永远查不中，真曲名丢失后退回
            // 被歌词污染的 MediaMetadata——宁可这一轮不报，也不能报一个假标识
            AndroidLog.logW(TAG, "Song.getId() unavailable; skipping track info for this callback");
            return;
        }

        String title = orEmpty(invokeOrNull(song, "getTitle"));
        String artist = orEmpty(invokeOrNull(song, "getArtist"));

        listener.onSong(songId, title, artist, parseLong(invokeOrNull(song, "getDuration")));

        if (songId.equals(sLastDumpedSongId)) return;
        sLastDumpedSongId = songId;

        StringBuilder builder = new StringBuilder(256);
        builder.append("id=").append(songId);
        builder.append(" title=\"").append(title).append('"');
        builder.append(" artist=\"").append(artist).append('"');
        builder.append(" album=\"").append(invoke(song, "getAlbum")).append('"');
        builder.append(" duration=").append(invoke(song, "getDuration"));
        builder.append(" path=").append(invoke(song, "getPath"));

        LyricDiag.dumpPayload(TAG, "SONG", builder.toString());
    }

    private static long parseLong(@Nullable String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    @NonNull
    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    /**
     * 反射调用无参 getter；失败或返回 {@code null} 时返回 {@code null}。
     *
     * <p>给**语义路径**用：取不到就得是"取不到"，不能用一个字符串冒充真实值。
     * 诊断转储请用 {@link #invoke}。
     */
    @Nullable
    private static String invokeOrNull(@NonNull Object target, @NonNull String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射调用无参 getter；失败时返回标记串（含异常类型）而不是抛出。仅供诊断转储。 */
    @NonNull
    private static String invoke(@NonNull Object target, @NonNull String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value == null ? "null" : String.valueOf(value);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
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
