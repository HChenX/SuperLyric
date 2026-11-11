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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.provider.mobilemusic;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.anchors.AnchorBus;
import com.hchen.superlyric.anchors.BuildConfig;
import com.hchen.superlyric.kernel.merge.TimeText;
import com.hchen.superlyric.kernel.parse.MobileMusicLyricParser.NativeLine;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * 咪咕歌词解析入口的 DexKit 锚点
 *
 * <p>加固宿主把音乐 SDK 放进动态 dex，APK 扫描失败时从宿主类加载器扫描并缓存。
 * 只观察整首歌词、TRC 与偏移变更；播放状态和进度由公共框架处理。
 * 宿主会原地复用列表，因此在解析入口返回后复制数据，后续映射不持有宿主模型。
 */
public final class MobileMusicAnchors {
    private static final String TAG = "MobileMusicAnchors";

    private static final String MANAGER_CACHE_KEY = "migu_lyric_manager_v1";
    private static final String MANAGER_ANCHOR = "musicplay lrc parseLyricsFile";
    private static final String PLAYER_CACHE_KEY = "migu_player_controller_v1";
    private static final String PLAYER_ANCHOR = "PlayerController initialize";
    private static final String VIEW_CACHE_KEY = "migu_lyric_view_v2";

    /**
     * 末尾空格是宿主字符串常量的一部分，精确匹配时必须保留
     */
    private static final String VIEW_ANCHOR = "treasure song Normal lyrics view apply skin mApplySkinChange = ";

    /**
     * 按类缓存无参 getter
     *
     * <p>使用自建映射而不是 {@link ClassValue}：后者在 Android 上是 API 34 才有的类，
     * 而本模块 {@code minSdk 30}，类初始化时就会在 Android 11–13 上失败。
     * 读取路径由 JVM 单元测试直接驱动，CoreTool 的调用需要 Xposed 环境，故保留纯 JDK 反射。
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
    private volatile OffsetSnapshot liveOffset;
    private Listener listener;
    private boolean subscribed;

    private record OffsetSnapshot(Object song, Object parser, WeakReference<Object> view) {
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
            Class<?> manager = locateClass(loader, MANAGER_CACHE_KEY, MANAGER_ANCHOR);
            Class<?> player = locateClass(loader, PLAYER_CACHE_KEY, PLAYER_ANCHOR);
            if (manager == null || player == null) return false;

            parsedSong = manager.getField("mCurrentParseSong");
            lyricLines = manager.getField("mLrcLineList");
            translationLines = manager.getField("mLrcTrcsLineList");
            lyricParser = manager.getField("lyricsParser");
            isMrc = manager.getField("isMrc");
            getManager = manager.getMethod("getIntance");
            getSong = player.getMethod("getUseSong");
            Class<?> song = parsedSong.getType();
            Method parsed = manager.getDeclaredMethod("parseLyricsFile", String.class, song);
            Method translated = manager.getDeclaredMethod("parseTrc", File.class);
            this.listener = listener;

            AbsHook capture = new AbsHook() {
                @Override
                public void after() {
                    refreshCurrent();
                }
            };
            // 部分挂载成功后继续重试时，不能重复安装已经生效的回调。
            if (!lyricHooks.contains(parsed)) {
                CoreTool.hook(parsed, capture);
                lyricHooks.add(parsed);
            }
            if (!lyricHooks.contains(translated)) {
                CoreTool.hook(translated, capture);
                lyricHooks.add(translated);
            }
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
            if (Boolean.TRUE.equals(invoke(song, "isVideoResource")) ||
                Boolean.TRUE.equals(invoke(manager, "isStaticLrc"))) {
                return new Capture(songId, title, artist, durationMs, album, List.of(), List.of(), 0L, "static lyrics or video");
            }
            Object parser = CoreTool.getField(lyricParser, manager);
            long offset = 0L;
            OffsetSnapshot live = liveOffset;
            Object offsetView = live == null ? null : live.view.get();
            // 普通 LRC 没有 MRC 解析器，歌词视图里调整的偏移仍须保留。
            // 视图重置会直接清零字段，因此快照读取当前值，且不延长已销毁界面的生命周期。
            if (offsetView != null && live.parser == parser && sameSong(song, live.song)) {
                offset = ((Number) invoke(offsetView, "getOffset")).longValue();
            } else if (Boolean.TRUE.equals(CoreTool.getField(isMrc, manager)) && parser != null) {
                offset = ((Number) invoke(parser, "getPlayOffset")).longValue();
            }
            List<NativeLine> lines = new ArrayList<>();
            Object nativeLines = CoreTool.getField(lyricLines, manager);
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
            Object nativeTranslations = CoreTool.getField(translationLines, manager);
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
            // 锚点必须由视图本身输出，匿名监听器不包含偏移 setter。
            Class<?> view = locateClass(loader, VIEW_CACHE_KEY, VIEW_ANCHOR);
            if (view == null) {
                diagnose("Lyric view anchor not ready; offset hooks will be retried");
                return;
            }
            Method relative = view.getDeclaredMethod("setProgressOffset", int.class);
            Method absolute = view.getDeclaredMethod("setCurrentOffset", int.class);
            AbsHook capture = new AbsHook() {
                @Override
                public void after() {
                    Object lyricsView = getThisObject();
                    if (!Boolean.TRUE.equals(invoke(lyricsView, "isPlayerLyricsMode")) &&
                        !Boolean.TRUE.equals(invoke(lyricsView, "isTreasureLyricsMode"))) {
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
                    liveOffset = new OffsetSnapshot(song, CoreTool.getField(lyricParser, manager),
                        new WeakReference<>(lyricsView));
                    diagnose("Captured view offset: offset="
                        + ((Number) invoke(lyricsView, "getOffset")).longValue() + "ms");
                    refreshCurrent();
                }
            };
            if (!offsetHooks.contains(relative.getName())) {
                CoreTool.hook(relative, capture);
                offsetHooks.add(relative.getName());
            }
            if (!offsetHooks.contains(absolute.getName())) {
                CoreTool.hook(absolute, capture);
                offsetHooks.add(absolute.getName());
            }
            diagnose("Installed lyric view offset hooks");
        } catch (Throwable error) {
            AndroidLog.logW(TAG, "Live lyric offset hooks unavailable: " + error.getMessage());
            diagnose("Live lyric offset hooks unavailable: " + error.getMessage());
        }
    }

    private static void diagnose(String message) {
        AndroidLog.logD(TAG, message);
        // 调试包同时使用 Android 日志，减少对 Xposed 日志转发的依赖。
        if (BuildConfig.DEBUG) Log.i(TAG, message);
    }

    @Nullable
    private static Class<?> locateClass(ClassLoader loader, String key, String anchor) {
        DexKitBridge[] runtime = new DexKitBridge[1];
        try {
            return DexkitCache.findMember(key, loader, new IDexkit<ClassData>() {
                @Override
                public ClassData dexkit(DexKitBridge bridge) {
                    FindClass query = FindClass.create().matcher(ClassMatcher.create().usingEqStrings(anchor));
                    ClassDataList classes = bridge.findClass(query);
                    if (classes.isEmpty()) {
                        runtime[0] = DexKitBridge.create(loader, true);
                        classes = runtime[0].findClass(query);
                    }
                    ClassData nonInner = classes.firstOrNull(data -> !data.getName().contains("$"));
                    return nonInner != null ? nonInner : classes.firstOrNull();
                }
            });
        } finally {
            if (runtime[0] != null) runtime[0].close();
        }
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
