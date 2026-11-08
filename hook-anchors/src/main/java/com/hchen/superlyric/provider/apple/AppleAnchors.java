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
package com.hchen.superlyric.provider.apple;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.anchors.AnchorBus;
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.publisher.AbsPublisher;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Apple Music Hook 锚点：遍历 JNI 歌词向量取出文本、译文与逐字时戳。
 * <p>
 * buildTimeRangeToLyricsMap 只在宿主构建歌词时调用，因此还要主动触发 loadLyrics。
 * 所有调用捕获异常，失败只影响本 Provider。
 */
public final class AppleAnchors {
    private static final String TAG = "AppleAnchors";

    private static final String PLAYBACK_ITEM_CLASS = "com.apple.android.music.model.PlaybackItem";

    private static final String VIEW_MODEL_CACHE_KEY = "apple_lyrics_view_model_class_v1";
    private static final String VIEW_MODEL_ANCHOR = "currentPlaybackItem";
    private static final String PM_CACHE_KEY = "apple_pm_class_v1";

    /**
     * 锚点：MediaPlaybackManager 中的固定日志串
     * <p>
     * DexKit 按 {@code ClassMatcher.usingEqStrings(PM_ANCHOR)} 匹配。命中多个类时取第一个，
     * 并记录匹配数量；类选错会使该 Provider 全程失效。
     */
    private static final String PM_ANCHOR = "Unrecognized PlayerTimedTextItem.AnchorType: ";

    /**
     * 请求歌词的去抖窗口
     * <p>
     * 宿主切歌时会连续发送几次队列变化，逐个请求会重复加载歌词；350ms 足以覆盖这一串事件，
     * 又不会产生可感知延迟。
     * <p>
     * 去抖任务在宿主主线程执行：宿主的 ViewModel 只能在主线程访问。
     */
    private static final long REQUEST_DEBOUNCE_MS = 350L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile Object sLyricViewModel;
    private static volatile Method sLoadLyricsMethod;
    /**
     * 最近一次成功发起请求的 itemId；去重只使用它，失败后允许同一曲目重试
     */
    private static volatile String sLastCompletedItemId;
    private static volatile Runnable sPendingRequest;
    /**
     * 队列触发路径首次失败时记录一条 W；后续不再记录，避免每次回调刷屏
     */
    private static final AtomicBoolean sQueueTriggerDeadReported = new AtomicBoolean();
    private static final AnchorBus<AppleEvent> BUS = new AnchorBus<>();
    private static volatile boolean sInstalled;

    /**
     * {@code PlaybackItem.setId} 兜底锚点的插桩计数
     * <p>
     * 该回退依赖调用栈中的 {@code getItemAtIndex} / {@code .accept}，宿主混淆改名后无法命中；
     * 计数用于区分“从未命中”与“未插桩”。
     */
    private static final AtomicInteger sSetIdProbes = new AtomicInteger();
    private static final AtomicInteger sSetIdCaptures = new AtomicInteger();
    private static final int SET_ID_REPORT_AFTER = 200;

    /**
     * 兜底锚点失效告警是否已输出
     * <p>
     * 判据必须是“跨过阈值”而非“恰好等于阈值”：计数由 Hook 回调并发自增，
     * {@code get() == 200} 可能被跳过，告警将不再出现。
     */
    private static final AtomicBoolean sSetIdDeadReported = new AtomicBoolean();

    private AppleAnchors() {
    }

    /**
     * 逐字切片
     */
    public static final class AppleWord {
        public final String text;
        public final long startMs;
        public final long endMs;

        AppleWord(String text, long startMs, long endMs) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    /**
     * 单行歌词；文本保留宿主给出的原始 HTML，由上层统一净化
     */
    public static final class AppleLine {
        public final String text;
        @Nullable
        public final String translation;
        public final long startMs;
        public final long endMs;
        @NonNull
        public final List<AppleWord> words;

        AppleLine(String text, @Nullable String translation, long startMs, long endMs, @NonNull List<AppleWord> words) {
            this.text = text;
            this.translation = translation;
            this.startMs = startMs;
            this.endMs = endMs;
            this.words = words;
        }
    }

    /**
     * 一次 Apple 抽取结果。
     *
     * @param itemId  结果所属曲目；取不到时为 null
     * @param lines   歌词行；noLyrics 时为空
     * @param noLyrics 宿主明确表示无歌词
     */
    public record AppleEvent(@Nullable String itemId, @NonNull List<AppleLine> lines, boolean noLyrics) {
    }

    public static AutoCloseable subscribe(@NonNull Consumer<AppleEvent> listener) {
        return BUS.subscribe(listener);
    }

    /**
     * 安装全部 Hook
     *
     * <p>幂等；失败只记录日志。
     */
    public static void install() {
        if (sInstalled) return;
        sInstalled = true;

        hookApplicationCreate();
        hookBuildTimeRangeToLyricsMap();
        hookMediaPlaybackManager();
        hookPlaybackItemSetId();
    }

    private static void hookApplicationCreate() {
        try {
            CoreTool.hookMethod("com.apple.android.music.AppleMusicApplication", "onCreate",
                new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            initLyricViewModel((Application) getThisObject());
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Failed to init lyric view model: " + t.getMessage());
                        }
                    }
                });
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to hook AppleMusicApplication.onCreate: " + t.getMessage());
        }
    }

    private static synchronized void initLyricViewModel(@Nullable Application application) {
        if (sLyricViewModel != null || application == null || sLoadLyricsMethod == null) return;
        try {
            sLyricViewModel = CoreTool.newInstance(sLoadLyricsMethod.getDeclaringClass(), application);
            AndroidLog.logI(TAG, "Initialized lyric view model");
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to instantiate lyric view model: " + t.getMessage());
        }
    }

    private static void ensureLyricViewModel() {
        if (sLyricViewModel != null) return;
        Context context = AbsPublisher.getAppContext();
        if (context instanceof Application application) {
            initLyricViewModel(application);
        }
    }

    private static void hookBuildTimeRangeToLyricsMap() {
        try {
            Class<?> viewModelClass = locateClass(VIEW_MODEL_CACHE_KEY, VIEW_MODEL_ANCHOR);
            if (viewModelClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate lyric view model");
                return;
            }
            Method loadLyrics = findVoidMethod(viewModelClass, CoreTool.findClass(PLAYBACK_ITEM_CLASS));
            Method buildMap = findVoidMethod(viewModelClass,
                CoreTool.findClass("com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoPtr"));
            CoreTool.hook(buildMap, new AbsHook() {
                @Override
                public void after() {
                    try {
                        Object songInfoPtr = getArg(0);
                        if (songInfoPtr != null) handleSongInfo(songInfoPtr);
                    } catch (Throwable t) {
                        AndroidLog.logW(TAG, "Failed to extract lyric model: " + t.getMessage());
                    }
                }
            });
            sLoadLyricsMethod = loadLyrics;
            AndroidLog.logI(TAG, "Hooked lyric view model: " + viewModelClass.getName());
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to hook lyric view model: " + t.getMessage());
        }
    }

    private static void hookMediaPlaybackManager() {
        try {
            Class<?> managerClass = locateClass(PM_CACHE_KEY, PM_ANCHOR);
            if (managerClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate MediaPlaybackManager");
                return;
            }
            Class<?> controllerClass = CoreTool.findClass("com.apple.android.music.playback.controller.MediaPlayerController");
            Class<?> queueItemClass = CoreTool.findClass("com.apple.android.music.playback.model.PlayerQueueItem");

            CoreTool.hook(findVoidMethod(managerClass, controllerClass, queueItemClass, queueItemClass),
                new AbsHook() {
                    @Override
                    public void before() {
                        handlePlayerQueueItem(getArg(2));
                    }
                });

            CoreTool.hook(findVoidMethod(managerClass, controllerClass, queueItemClass),
                new AbsHook() {
                    @Override
                    public void before() {
                        handlePlayerQueueItem(getArg(1));
                    }
                });

            AndroidLog.logI(TAG, "Hooked MediaPlaybackManager: " + managerClass.getName());
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to hook MediaPlaybackManager: " + t.getMessage());
        }
    }

    @Nullable
    private static Class<?> locateClass(@NonNull String cacheKey, @NonNull String anchor) {
        return DexkitCache.findMember(cacheKey, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingEqStrings(anchor))
                );
                if (classes == null || classes.isEmpty()) return null;
                if (classes.size() > 1) {
                    AndroidLog.logW(TAG, "Anchor " + anchor + " matched " + classes.size()
                        + " classes; using the first: " + classes.get(0).getName());
                }
                return classes.get(0);
            }
        });
    }

    @NonNull
    private static Method findVoidMethod(@NonNull Class<?> owner, Class<?>... parameterTypes)
        throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
            if (method.getReturnType() == void.class && Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + Arrays.toString(parameterTypes));
    }

    /**
     * 兜底：从队列构建路径上捕获 {@code PlaybackItem} 实体
     */
    private static void hookPlaybackItemSetId() {
        try {
            Class<?> playbackItemClass = CoreTool.findClass(PLAYBACK_ITEM_CLASS);
            CoreTool.hookMethodIfExists("com.apple.android.music.model.BaseContentItem",
                "setId",
                String.class,
                new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            if (!playbackItemClass.isInstance(getThisObject())) return;
                            Object id = getArg(0);
                            if (!(id instanceof String trackId) || trackId.isEmpty()) return;

                            // 只接受来自队列逐项构建路径的赋值，避免误捕其它 setId
                            sSetIdProbes.incrementAndGet();
                            boolean fromQueueBuild = false;
                            boolean fromCallback = false;
                            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                                String frame = element.toString();
                                if (!fromQueueBuild && frame.contains("getItemAtIndex"))
                                    fromQueueBuild = true;
                                if (!fromCallback && frame.contains(".accept")) fromCallback = true;
                                if (fromQueueBuild && fromCallback) break;
                            }
                            if (fromQueueBuild && fromCallback) {
                                sSetIdCaptures.incrementAndGet();
                                Object item = getThisObject();
                                scheduleRequestLyrics(item);
                                return;
                            }

                            // 跨过阈值说明该回退已失效；取词只剩 MediaPlaybackManager，
                            // 若它同时失效，该 Provider 不会执行任何操作。使用 >= 而非 ==：
                            // 并发自增可能跳过阈值；compareAndSet 保证只输出一次
                            if (sSetIdProbes.get() >= SET_ID_REPORT_AFTER && sSetIdCaptures.get() == 0
                                && sSetIdDeadReported.compareAndSet(false, true)) {
                                AndroidLog.logW(TAG, "PlaybackItem.setId fallback anchor matched nothing in "
                                    + sSetIdProbes.get() + " invocations; the getItemAtIndex/.accept frames "
                                    + "were probably renamed. Lyric requests rely on MediaPlaybackManager alone.");
                            }
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in PlaybackItem.setId hook: " + t.getMessage());
                        }
                    }
                });
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to hook PlaybackItem.setId: " + t.getMessage());
        }
    }

    private static void handlePlayerQueueItem(@Nullable Object queueItem) {
        if (queueItem == null) {
            reportDeadQueueTrigger("queueItem=null");
            return;
        }
        try {
            Object mediaItem = CoreTool.callMethodIfExists(queueItem, "getItem");
            if (mediaItem == null) {
                reportDeadQueueTrigger("queueItem.getItem() returned null; method renamed?");
                return;
            }

            Object candidate = CoreTool.getFieldIfExists(mediaItem, "playbackItem");
            if (candidate == null) candidate = mediaItem;

            Class<?> playbackItemClass = CoreTool.findClass(PLAYBACK_ITEM_CLASS);
            if (playbackItemClass.isInstance(candidate)) {
                scheduleRequestLyrics(candidate);
            } else {
                reportDeadQueueTrigger("candidate is " + candidate.getClass().getName()
                    + ", expected " + PLAYBACK_ITEM_CLASS);
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to handle PlayerQueueItem: " + t.getMessage());
        }
    }

    private static void reportDeadQueueTrigger(@NonNull String reason) {
        if (sQueueTriggerDeadReported.compareAndSet(false, true)) {
            AndroidLog.logW(TAG, "Apple queue trigger is not usable: " + reason);
        }
    }

    private static synchronized void scheduleRequestLyrics(@NonNull Object playbackItem) {
        String itemId = readItemId(playbackItem);
        if (itemId != null && itemId.equals(sLastCompletedItemId)) return;

        Runnable pending = sPendingRequest;
        if (pending != null) MAIN_HANDLER.removeCallbacks(pending);

        Runnable task = () -> {
            if (requestLyrics(playbackItem)) {
                sLastCompletedItemId = itemId;
            }
        };
        sPendingRequest = task;
        MAIN_HANDLER.postDelayed(task, REQUEST_DEBOUNCE_MS);
    }

    /**
     * @return 是否已发出请求，或已得到“无歌词”的确定结论；失败返回 false，
     * 调用方不写去重键，同一曲目的下一次宿主回调仍可重试
     */
    private static boolean requestLyrics(@NonNull Object playbackItem) {
        try {
            if (!hasLyrics(playbackItem)) {
                AndroidLog.logI(TAG, "PlaybackItem reports no lyrics available");
                notifyNoLyrics(readItemId(playbackItem));
                return true;
            }

            ensureLyricViewModel();
            Object viewModel = sLyricViewModel;
            if (viewModel == null) {
                AndroidLog.logD(TAG, "PlayerLyricsViewModel unavailable; cannot request lyrics");
                return false;
            }
            AndroidLog.logD(TAG, "Requesting lyrics for " + readItemId(playbackItem));
            sLoadLyricsMethod.invoke(viewModel, playbackItem);
            return true;
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to call loadLyrics: " + t.getMessage());
            return false;
        }
    }

    /**
     * @return false 表示宿主明确声明该曲目没有歌词
     */
    private static boolean hasLyrics(@NonNull Object playbackItem) {
        try {
            Object hasLyrics = CoreTool.callMethodIfExists(playbackItem, "hasLyrics");
            Object hasSynced = CoreTool.callMethodIfExists(playbackItem, "hasTimeSyncedLyrics");
            Object hasCustom = CoreTool.callMethodIfExists(playbackItem, "hasCustomLyrics");
            if (Boolean.FALSE.equals(hasLyrics) && Boolean.FALSE.equals(hasSynced) && Boolean.FALSE.equals(hasCustom)) {
                return false;
            }
        } catch (Throwable ignored) {
            // 取不到就当作"可能有"，让后续流程去判定
        }
        return true;
    }

    @Nullable
    private static String readItemId(@NonNull Object playbackItem) {
        try {
            Object id = CoreTool.callMethodIfExists(playbackItem, "getId");
            return id != null ? id.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void handleSongInfo(@NonNull Object songInfoPtr) {
        Object songInfo = CoreTool.callMethodIfExists(songInfoPtr, "get");
        if (songInfo == null) return;

        Object adamId = CoreTool.callMethodIfExists(songInfo, "getAdamId");
        String itemId = TrackIdentity.usableId(adamId != null ? adamId.toString() : null);

        try {
            Object language = CoreTool.callStaticMethodIfExists(
                "com.apple.android.music.playback.util.LocaleUtil", "getSystemLyricsLanguage");
            if (language instanceof String text && !text.isEmpty()) {
                CoreTool.callMethodIfExists(songInfo, "setTranslation", text);
            }
        } catch (Throwable ignored) {
            // 译文语言设置失败不影响主歌词
        }

        Object sections = CoreTool.callMethodIfExists(songInfo, "getSections");
        if (sections == null) {
            AndroidLog.logW(TAG, "Cannot read lyric sections; leaving the verdict to the orchestrator");
            return;
        }

        List<AppleLine> lines = new ArrayList<>();
        long sectionCount = asLong(CoreTool.callMethodIfExists(sections, "size"));
        for (long s = 0; s < sectionCount; s++) {
            Object section = unwrap(CoreTool.callMethodIfExists(sections, "get", s));
            if (section == null) continue;

            Object sectionLines = CoreTool.callMethodIfExists(section, "getLines");
            if (sectionLines == null) {
                AndroidLog.logW(TAG, "Cannot read lines of lyric section; skipping it");
                continue;
            }

            long lineCount = asLong(CoreTool.callMethodIfExists(sectionLines, "size"));
            for (long l = 0; l < lineCount; l++) {
                Object line = unwrap(CoreTool.callMethodIfExists(sectionLines, "get", l));
                if (line == null) continue;

                AppleLine extracted = extractLine(line);
                if (extracted != null) lines.add(extracted);
            }
        }

        if (lines.isEmpty()) {
            AndroidLog.logW(TAG, "No usable lyric lines for " + itemId + "; not a no-lyrics verdict");
            return;
        }
        BUS.emit(new AppleEvent(itemId, Collections.unmodifiableList(lines), false));
    }

    @Nullable
    private static AppleLine extractLine(@NonNull Object line) {
        Object rawText = CoreTool.callMethodIfExists(line, "getHtmlLineText");
        Object rawTranslation = CoreTool.callMethodIfExists(line, "getHtmlTranslationLineText");
        Object begin = CoreTool.callMethodIfExists(line, "getBegin");
        Object end = CoreTool.callMethodIfExists(line, "getEnd");

        if (!(rawText instanceof String text)) {
            return null;
        }
        // asLong 对任意 Number 容错（宿主可能给 Integer 或 Long）
        long start = asLong(begin);
        long stop = asLong(end);

        List<AppleWord> words = new ArrayList<>();
        Object wordVector = CoreTool.callMethodIfExists(line, "getWords");
        if (wordVector != null) {
            long wordCount = asLong(CoreTool.callMethodIfExists(wordVector, "size"));
            for (long w = 0; w < wordCount; w++) {
                Object word = unwrap(CoreTool.callMethodIfExists(wordVector, "get", w));
                if (word == null) continue;

                Object wordText = CoreTool.callMethodIfExists(word, "getHtmlLineText");
                Object wordBegin = CoreTool.callMethodIfExists(word, "getBegin");
                Object wordEnd = CoreTool.callMethodIfExists(word, "getEnd");
                if (wordText instanceof String wt && wordBegin instanceof Number && wordEnd instanceof Number) {
                    words.add(new AppleWord(wt, asLong(wordBegin), asLong(wordEnd)));
                }
            }
        }

        String translation = rawTranslation instanceof String t ? t : null;
        return new AppleLine(text, translation, start, stop, words);
    }

    @Nullable
    private static Object unwrap(@Nullable Object pointer) {
        if (pointer == null) return null;
        Object value = CoreTool.callMethodIfExists(pointer, "get");
        return value != null ? value : pointer;
    }

    private static long asLong(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static void notifyNoLyrics(@Nullable String itemId) {
        BUS.emit(new AppleEvent(itemId, List.of(), true));
    }
}
