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
import com.hchen.superlyric.anchors.AnchorBus;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.utils.LyricDiag;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 椒盐音乐 Hook 锚点：从 {@code MusicController} 的歌词设置入口取回原始歌词文本
 *
 * <p>只读取 {@code LyricsDocument} 上的原始歌词文本字段 {@code sourceText}，宿主用它渲染，
 * 也用它导出。该文本尚未经过宿主内部 {@code VerbatimLyricsParserImpl.e()} 对相邻词元的
 * 重叠裁切，因此逐字时戳不会出现 0ms 坍缩。拿到 {@code sourceText} 后无需再反射
 * {@code LyricsLine} / {@code LyricsCell} 行/词模型，本实现只读取一个 String 字段。
 */
public final class SaltAnchors {
    private static final String TAG = "SaltAnchors";

    private static final String DOCUMENT_CACHE_KEY = "salt_lyrics_document_class_v1";

    private static final String DOCUMENT_ANCHOR = "LyricsDocument(sourceText=";

    private static final String SONG_CLASS = "com.salt.music.data.entry.Song";

    private static final String CONTROLLER_CLASS = "com.salt.music.service.MusicController";

    private static volatile boolean sInstalled;
    private static final AnchorBus<LyricEvent> LYRICS_BUS = new AnchorBus<>();
    private static final AnchorBus<SongEvent> SONG_BUS = new AnchorBus<>();

    private SaltAnchors() {
    }

    /**
     * 宿主设置歌词时的回调
     */
    public interface LyricsListener {
        /**
         * @param songId 宿主显式给出的曲目标识；可能为空
         */
        void onLyrics(@NonNull String sourceText, @NonNull String songId);

        /**
         * 宿主的真实曲目信息
         *
         * <p>椒盐在开启蓝牙歌词后会把 {@code METADATA_KEY_TITLE} 写成当前歌词行或制作人名单、
         * 把 {@code ARTIST} 拼成 {@code "歌手 - 曲名"}，元数据完全不可信。这个回调给出的是
         * 宿主自己的 {@code Song} 对象里的原始字段，且其 {@code id} 与 {@link #onLyrics} 的
         * {@code songId} 完全一致，可精确关联。
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
     * 定位并挂载 Hook。幂等；失败只记录日志
     */
    public record LyricEvent(@NonNull String sourceText, @NonNull String songId) {
    }

    public record SongEvent(@NonNull String songId, @NonNull String title,
                            @NonNull String artist, long durationMs) {
    }

    public static AutoCloseable subscribe(@NonNull LyricsListener subscriber) {
        AutoCloseable lyrics = LYRICS_BUS.subscribe(event ->
            subscriber.onLyrics(event.sourceText(), event.songId()));
        AutoCloseable songs = SONG_BUS.subscribe(event ->
            subscriber.onSong(event.songId(), event.title(), event.artist(), event.durationMs()));
        return () -> {
            lyrics.close();
            songs.close();
        };
    }

    public static void install(@NonNull LyricsListener subscriber) {
        subscribe(subscriber);
        if (sInstalled) return;
        sInstalled = true;
        LyricsListener listener = new LyricsListener() {
            @Override
            public void onLyrics(@NonNull String sourceText, @NonNull String songId) {
                LYRICS_BUS.emit(new LyricEvent(sourceText, songId));
            }

            @Override
            public void onSong(@NonNull String songId, @NonNull String title,
                               @NonNull String artist, long durationMs) {
                SONG_BUS.emit(new SongEvent(songId, title, artist, durationMs));
            }
        };

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
     * 挂载宿主那一个"曲目已切换"回调，取回 {@code com.salt.music.data.entry.Song}
     *
     * <p>取数点不能是 {@code LyriconSongSnapshot} 的构造器：它是歌词渲染器的快照，只在
     * 歌词页面订阅时生成，切歌时零触发。{@code MusicController} 内部以 {@code Song} 为
     * 参数的回调稳定触发，且 {@code Song} 带 {@code @Keep}、{@code Song.getId()} 与
     * {@link LyricsListener#onLyrics} 的 songId 完全一致，可精确关联。
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
                    // 探测异常不传到宿主
                }
            }
        });
    }

    /**
     * 已转储过的曲目标识，避免热路径刷屏
     */
    private static volatile String sLastDumpedSongId;

    private static void reportSong(@Nullable Object song, @NonNull LyricsListener listener) {
        if (song == null) return;

        String songId = invokeOrNull(song, "getId");
        if (songId == null || songId.isEmpty()) {
            // 取不到宿主标识就无法把歌词与曲目关联。不得把异常类型标记串当作真实值传下去：
            // songStore 以标记为键永远查不中，真曲名丢失后只能退回被歌词污染的 MediaMetadata。
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
     * 反射调用无参 getter；失败或返回 {@code null} 时返回 {@code null}
     *
     * <p>给语义路径用：取不到就得是"取不到"，不能用一个字符串冒充真实值。
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

    /**
     * 反射调用无参 getter；失败时返回标记串（含异常类型）而不是抛出。仅供诊断转储
     */
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
     * 按类型唯一性定位原始文本字段
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
