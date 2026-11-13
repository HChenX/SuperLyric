/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.provider.mobilemusic;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.anchors.AnchorBus;
import com.hchen.superlyric.anchors.BuildConfig;
import com.hchen.superlyric.kernel.merge.TimeText;
import com.hchen.superlyric.kernel.parse.MobileMusicLyricParser.NativeLine;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/**
 * 咪咕歌词解析入口的锚点
 *
 * <p>8.9.6 起音乐 SDK 放进动态 dex，但类名没有混淆：直接按稳定类名定位
 * 管理器、数据对象、解析入口与歌词视图，不依赖 DexKit 扫描。
 * 只观察整首歌词、TRC 与偏移变更；播放状态和进度由公共框架处理。
 * 宿主会原地复用列表，因此在解析入口返回后复制数据，后续映射不持有宿主模型。
 */
public final class MobileMusicAnchors {
    private static final String TAG = "MobileMusicAnchors";

    private static final String MANAGER_CLASS = "com.migu.music.lyrics.LrcManager";
    private static final String PLAYER_CLASS = "com.migu.music.player.PlayerController";
    private static final String DATA_CLASS = "com.migu.music.lyrics.LrcDataInfo";
    private static final String PARSER_CLASS = "com.migu.music.lyrics.LrcDataManager";
    private static final String VIEW_CLASS = "com.migu.music.lyrics.view.NormalLyricView";

    /**
     * 无参 getter 的反射缓存，读取路径需要能在 JVM 单测里直接驱动。
     */
    private static final ConcurrentHashMap<Class<?>, Map<String, Method>> GETTERS =
        new ConcurrentHashMap<>();

    public record Capture(
        String songId,
        String title,
        String artist,
        long durationMs,
        String album,
        List<NativeLine> lines,
        List<TimeText> translations,
        long offsetMs,
        @Nullable String unavailableReason
    ) {
    }

    public interface Listener {
        void onCaptured(@NonNull Capture capture);
    }

    private static final AnchorBus<Capture> BUS = new AnchorBus<>();
    private volatile boolean installed;
    private final Set<Method> lyricHooks = new HashSet<>();
    private final Set<String> offsetHooks = new HashSet<>();
    private Method getManager;
    private Method getSong;
    private Field parsedSong;
    private Field lyricLines;
    private Field translationLines;
    private Field lyricParser;
    private Field isMrc;
    private Field currentData;
    private Field dataLines;
    private Field dataTranslations;
    private Field dataOffset;
    private Field dataIsStatic;
    private boolean legacyModel;
    private volatile OffsetSnapshot liveOffset;
    private Listener listener;
    private boolean subscribed;

    private record OffsetSnapshot(Object song, Object token, WeakReference<Object> view) {
    }

    public boolean isInstalled() {
        return installed;
    }

    /**
     * 安装失败允许在 Application 初始化后以最终类加载器重试
     *
     * @return 歌词主钩子及两个偏移钩子是否均已安装；主钩子成功不阻止偏移钩子重试
     */
    public static AutoCloseable subscribe(@NonNull Listener subscriber) {
        return BUS.subscribe(subscriber::onCaptured);
    }

    public synchronized boolean install(@NonNull ClassLoader loader, @NonNull Listener subscriber) {
        if (!subscribed) {
            subscribed = true;
            subscribe(subscriber);
        }
        Listener listener = BUS::emit;
        if (installed) {
            installOffsets(loader);
            return offsetHooks.size() == 2;
        }
        try {
            Class<?> manager = CoreTool.findClassIfExists(MANAGER_CLASS, loader);
            Class<?> player = CoreTool.findClassIfExists(PLAYER_CLASS, loader);
            if (manager == null || player == null) return false;

            getManager = CoreTool.findMethod(manager, "getIntance");
            getSong = CoreTool.findMethod(player, "getUseSong");
            parsedSong = CoreTool.findField(manager, "mCurrentParseSong");
            Class<?> songClass = parsedSong.getType();

            AbsHook capture = new AbsHook() {
                @Override
                public void after() {
                    refreshCurrent();
                }
            };

            if (CoreTool.hasField(manager, "mLrcLineList")) {
                legacyModel = true;
                lyricLines = CoreTool.findField(manager, "mLrcLineList");
                translationLines = CoreTool.findField(manager, "mLrcTrcsLineList");
                lyricParser = CoreTool.findField(manager, "lyricsParser");
                isMrc = CoreTool.findField(manager, "isMrc");
                hookMethods(List.of(
                    CoreTool.findMethod(manager, "parseLyricsFile", String.class, songClass),
                    CoreTool.findMethod(manager, "parseTrc", File.class)
                ), capture);
            } else {
                legacyModel = false;
                Class<?> dataClass = CoreTool.findClassIfExists(DATA_CLASS, loader);
                if (dataClass == null) return false;
                currentData = CoreTool.findField(manager, "currentSongLrcInfo");
                dataLines = CoreTool.findField(dataClass, "mLrcLineList");
                dataTranslations = CoreTool.findField(dataClass, "mLrcTrcsLineList");
                dataOffset = CoreTool.findField(dataClass, "lrcOffset");
                dataIsStatic = CoreTool.findField(dataClass, "isStaticLrc");

                Class<?> parserClass = CoreTool.findClassIfExists(PARSER_CLASS, loader);
                if (parserClass != null) {
                    List<Method> parseEntries = new ArrayList<>();
                    for (Method method : parserClass.getDeclaredMethods()) {
                        if (isParseEntry(method)) parseEntries.add(method);
                    }
                    hookMethods(parseEntries, capture);
                }
                // 原生解析完成后由管理器回调通知整首歌词；回调参数即当前数据对象
                List<Method> finishCallbacks = new ArrayList<>();
                for (Method method : manager.getDeclaredMethods()) {
                    String name = method.getName();
                    if (name.startsWith("callBackLrcFinish") || name.startsWith("callBackTrcFinish")) {
                        finishCallbacks.add(method);
                    }
                }
                hookMethods(finishCallbacks, capture);
            }

            this.listener = listener;
            installed = true;
            installOffsets(loader);
            diagnose("Installed native full-song lyric hooks");
            refreshCurrent();
            return offsetHooks.size() == 2;
        } catch (Throwable error) {
            AndroidLog.logW(TAG, "Native lyric anchors not ready: " + error.getMessage());
            return false;
        }
    }

    /**
     * 取词会话可能晚于歌词回调建立，故还要允许主动读取当前完整快照
     */
    @Nullable
    public Capture readCurrent() {
        if (!installed) return null;
        Object song;
        Object manager;
        try {
            song = getSong.invoke(null);
            manager = getManager.invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
        if (song == null || manager == null) return null;
        synchronized (manager) {
            if (!sameSong(song, CoreTool.getField(parsedSong, manager))) {
                AndroidLog.logD(TAG, "Waiting for a lyric parser belonging to the current song");
                return null;
            }
            String songId = rawSongIdOf(song);
            String title = invoke(song, "getSongName") instanceof String text ? text : "";
            String artist = invoke(song, "getSingerName") instanceof String text ? text : "";
            long durationMs = durationMs(((Number) invoke(song, "getMicroSecondsDuration")).longValue());
            String album = invoke(song, "getAlbum") instanceof String text ? text : "";
            boolean legacy = legacyModel;
            Object data = legacy ? manager : CoreTool.getField(currentData, manager);
            if (data == null) return null;
            Object isStatic = legacy
                ? invoke(manager, "isStaticLrc")
                : CoreTool.getField(dataIsStatic, data);
            if (Boolean.TRUE.equals(invoke(song, "isVideoResource"))
                || Boolean.TRUE.equals(isStatic)) {
                return new Capture(songId, title, artist, durationMs, album, List.of(), List.of(), 0L,
                    "static lyrics or video");
            }
            long offset = readOffset(song, manager, data, legacy);
            List<NativeLine> lines = new ArrayList<>();
            Object nativeLines = CoreTool.getField(legacy ? lyricLines : dataLines, data);
            if (nativeLines instanceof List<?> list) {
                for (Object line : list) {
                    String[] words = (String[]) invoke(line, "getLyricsWords");
                    int[] intervals = (int[]) invoke(line, "getWordsDisInterval");
                    List<Long> durations = durationsOf(intervals);
                    lines.add(new NativeLine(
                        ((Number) invoke(line, "getStartTime")).longValue(),
                        ((Number) invoke(line, "getEndTime")).longValue(),
                        invoke(line, "getLineLyrics") instanceof String text ? text : "",
                        invoke(line, "getTrcLrc") instanceof String trc ? trc : "",
                        words == null ? null : new ArrayList<>(Arrays.asList(words)), durations
                    ));
                }
            }
            List<TimeText> translations = new ArrayList<>();
            Object nativeTranslations = CoreTool.getField(
                legacy ? translationLines : dataTranslations, data);
            if (nativeTranslations instanceof List<?> list) {
                for (Object line : list) {
                    translations.add(new TimeText(((Number) invoke(line, "getStartTime")).longValue(),
                        invoke(line, "getLineLyrics") instanceof String text ? text : ""));
                }
            }
            boolean sameAtEnd;
            try {
                sameAtEnd = sameSong(song, getSong.invoke(null));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
            if (!sameAtEnd) return null;
            String unavailable = lines.isEmpty() || (lines.size() == 1 && lines.get(0).getStartMs() == 0L
                && "暂无歌词".equals(lines.get(0).getText())) ? "host lyric unavailable" : null;
            return new Capture(songId, title, artist, durationMs, album, lines, translations, offset, unavailable);
        }
    }

    private long readOffset(@NonNull Object song, @NonNull Object manager, @NonNull Object data,
                            boolean legacy) {
        OffsetSnapshot live = liveOffset;
        Object offsetView = live == null ? null : live.view.get();
        Object token = legacy ? CoreTool.getField(lyricParser, manager) : data;
        if (offsetView != null && live.token == token && sameSong(song, live.song)) {
            return ((Number) invoke(offsetView, "getOffset")).longValue();
        }
        if (!legacy) {
            Object value = CoreTool.getField(dataOffset, data);
            return value instanceof Number number ? number.longValue() : 0L;
        }
        boolean mrc = Boolean.TRUE.equals(CoreTool.getField(isMrc, manager));
        Object parser = CoreTool.getField(lyricParser, manager);
        if (mrc && parser != null) {
            return ((Number) invoke(parser, "getPlayOffset")).longValue();
        }
        return 0L;
    }

    /**
     * 读取当前快照并发布给所有监听者；调用方可在返回后立即读取身份 feed。
     */
    @Nullable
    public Capture refreshCurrent() {
        Capture capture = readCurrent();
        if (capture != null) listener.onCaptured(capture);
        return capture;
    }

    private void installOffsets(ClassLoader loader) {
        if (offsetHooks.size() == 2) return;
        try {
            Class<?> view = CoreTool.findClassIfExists(VIEW_CLASS, loader);
            if (view == null) {
                diagnose("Lyric view anchor not ready; offset hooks will be retried");
                return;
            }
            Method relative = CoreTool.findMethod(view, "setProgressOffset", int.class);
            Method absolute = CoreTool.findMethod(view, "setCurrentOffset", int.class);
            AbsHook capture = new AbsHook() {
                @Override
                public void after() {
                    try {
                        Object lyricsView = getThisObject();
                        if (!Boolean.TRUE.equals(invoke(lyricsView, "isPlayerLyricsMode"))
                            && !Boolean.TRUE.equals(invoke(lyricsView, "isTreasureLyricsMode"))) {
                            diagnose("Discarding offset from a non-player lyric view");
                            return;
                        }
                        Object song = CoreTool.callStaticMethod(getSong);
                        Object manager = CoreTool.callStaticMethod(getManager);
                        if (song == null || manager == null
                            || !sameSong(song, CoreTool.getField(parsedSong, manager))) {
                            diagnose("Discarding offset without a matching current lyric parser");
                            return;
                        }
                        Object token = legacyModel
                            ? CoreTool.getField(lyricParser, manager)
                            : CoreTool.getField(currentData, manager);
                        liveOffset = new OffsetSnapshot(song, token, new WeakReference<>(lyricsView));
                        diagnose("Captured view offset: offset="
                            + ((Number) invoke(lyricsView, "getOffset")).longValue() + "ms");
                        refreshCurrent();
                    } catch (Throwable error) {
                        diagnose("Failed to capture view offset: " + error.getMessage());
                    }
                }
            };
            hookOffset(relative, capture);
            hookOffset(absolute, capture);
            diagnose("Installed lyric view offset hooks");
        } catch (Throwable error) {
            AndroidLog.logW(TAG, "Live lyric offset hooks unavailable: " + error.getMessage());
            diagnose("Live lyric offset hooks unavailable: " + error.getMessage());
        }
    }

    /**
     * 循环只负责选择目标方法，安装由这里一次 hookAll 完成
     */
    private void hookMethods(@NonNull List<Method> methods, @NonNull AbsHook hook) {
        List<Method> targets = new ArrayList<>(methods.size());
        for (Method method : methods) {
            method.setAccessible(true);
            if (lyricHooks.add(method)) targets.add(method);
        }
        if (targets.isEmpty()) return;
        CoreTool.hookAll(targets.toArray(new Method[0]), hook);
    }

    private void hookOffset(@NonNull Method method, @NonNull AbsHook hook) {
        method.setAccessible(true);
        if (offsetHooks.add(method.getName())) CoreTool.hook(method, hook);
    }

    private static boolean isParseEntry(@NonNull Method method) {
        if (method.getParameterCount() < 2) return false;
        if (method.getParameterTypes()[0] != String.class) return false;
        return switch (method.getName()) {
            case "parseLyricsFile", "parseTrcLyricsFile", "parseLrcFile", "parseTrcFile" -> true;
            default -> false;
        };
    }

    private static void diagnose(String message) {
        AndroidLog.logD(TAG, message);
        // 调试包同时使用 Android 日志，减少对 Xposed 日志转发的依赖。
        if (BuildConfig.DEBUG) Log.i(TAG, message);
    }

    /**
     * 把宿主给的词间隔数组转成列表
     *
     * <p>手写循环而不用 {@code Arrays.stream(...).toList()}：{@code Stream.toList()} 是
     * Java 16 / Android 34 才有的 API，而本模块 {@code minSdk 30}，在 Android 11–13 上会抛
     * {@link NoSuchMethodError}。
     */
    @Nullable
    private static List<Long> durationsOf(@Nullable int[] intervals) {
        if (intervals == null) return null;
        List<Long> durations = new ArrayList<>(intervals.length);
        for (int interval : intervals) {
            durations.add((long) interval);
        }
        return durations;
    }

    private static boolean sameSong(Object song, Object other) {
        if (song == null || other == null) return false;
        if (song == other) return true;
        for (String getter : List.of("getSongId", "getContentId", "getLocalPathMd5")) {
            Object value = invoke(song, getter);
            String text = value instanceof String string ? string : "";
            if (!text.trim().isEmpty() && Objects.equals(value, invoke(other, getter)))
                return true;
        }
        return false;
    }

    private static Object invoke(Object target, String getter) {
        Map<String, Method> methods = GETTERS.computeIfAbsent(target.getClass(), type -> {
            Map<String, Method> found = new HashMap<>();
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() == 0) found.put(method.getName(), method);
            }
            return found;
        });
        Method method = methods.get(getter);
        if (method == null)
            throw new IllegalStateException(new NoSuchMethodException(target.getClass().getName() + "." + getter));
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * 按 Song 实例缓存首次选中的原始 ID，避免宿主分步填充字段时 ID 来源抖动。
     */
    private static final Map<Object, String> RAW_IDS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private static String rawSongIdOf(Object song) {
        String cached = RAW_IDS.get(song);
        if (cached != null) return cached;
        for (String getter : List.of("getSongId", "getContentId", "getLocalPathMd5")) {
            String raw = invoke(song, getter) instanceof String text ? text.trim() : "";
            if (!raw.isEmpty()) {
                RAW_IDS.put(song, raw);
                return raw;
            }
        }
        return "";
    }

    /**
     * 宿主方法名是 {@code getMicroSecondsDuration}，但不同版本可能实际给毫秒
     *
     * <p>用“超过 24 小时”作为不可能出现在毫秒里的上界：一般歌曲远小于它，
     * 而微秒表示 3 分钟就是 1.8e8，必然超过。这样既兼容两种单位，又不会把
     * 正常时长误除。
     */
    private static long durationMs(long raw) {
        if (raw <= 0L) return 0L;
        return raw > 86_400_000L ? raw / 1_000L : raw;
    }
}
