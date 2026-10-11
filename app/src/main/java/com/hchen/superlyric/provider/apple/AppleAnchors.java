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
import android.os.Handler;
import android.os.Looper;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Apple Music Hook 锚点与歌词抽取。
 *
 * <h3>为什么 Apple 是唯一的例外</h3>
 * 其余六个平台都能拿到**协议级**的原始报文（KRC / QRC / LRCX / JSON / TTML / YRC），
 * 因此模块可以自行解析。Apple Music 的歌词由 **JNI 侧**解析完成后通过裸指针
 * （{@code SongInfo$SongInfoPtr}）交给 Java 层，不存在可直接读取的明文报文。
 *
 * 因此这一家必须走宿主模型：在 {@code buildTimeRangeToLyricsMap} 的入参上遍历
 * {@code LyricsSectionVector → LyricsWordVector} 取出文本、译文与逐字时戳。
 * 这是经过权衡后**有意保留**的例外，不是遗漏。
 *
 * <h3>只读取，不改写</h3>
 * 本层只从宿主对象上读值，不调用任何会改变宿主状态的 API（除了主动触发一次歌词加载），
 * 因此宿主内部结构的变动最多让本 Provider 失效，不会影响宿主稳定性。
 *
 * <h3>关于主动触发</h3>
 * {@code buildTimeRangeToLyricsMap} 只在宿主真正构建歌词时被调用。
 * 若用户没有打开歌词界面，歌词永远不会构建，因此本层需要主动调用
 * {@code PlayerLyricsViewModel.loadLyrics} 把歌词"叫醒"。
 *
 * @author 焕晨HChen
 */
public final class AppleAnchors {
    private static final String TAG = "AppleAnchors";

    private static final String VIEW_MODEL_CLASS = "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel";
    private static final String PLAYBACK_ITEM_CLASS = "com.apple.android.music.model.PlaybackItem";

    private static final String PM_CACHE_KEY = "apple_pm_class_v1";

    /** 锚点：MediaPlaybackManager 中的固定日志串。 */
    private static final String PM_ANCHOR = "Unrecognized PlayerTimedTextItem.AnchorType: ";

    private static final long REQUEST_DEBOUNCE_MS = 350L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile Object sLyricViewModel;
    private static volatile Object sCurrentPlaybackItem;
    private static volatile String sLastRequestedItemId;
    private static volatile Runnable sPendingRequest;
    private static volatile LyricsListener sListener;
    private static volatile boolean sInstalled;

    private AppleAnchors() {
    }

    /** 逐字切片。 */
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

    /** 单行歌词；文本保留宿主给出的**原始 HTML**，由上层统一净化。 */
    public static final class AppleLine {
        public final String text;
        public final String translation;
        public final long startMs;
        public final long endMs;
        @NonNull
        public final List<AppleWord> words;

        AppleLine(String text, String translation, long startMs, long endMs, @NonNull List<AppleWord> words) {
            this.text = text;
            this.translation = translation;
            this.startMs = startMs;
            this.endMs = endMs;
            this.words = words;
        }
    }

    /** 抽取结果回调。 */
    public interface LyricsListener {
        void onLyrics(@NonNull List<AppleLine> lines);

        /** 宿主明确表示这首曲目没有歌词。 */
        void onNoLyrics();
    }

    /**
     * 安装全部 Hook。幂等；失败只记录日志。
     */
    public static void install(@NonNull LyricsListener listener) {
        if (sInstalled) return;
        sInstalled = true;
        sListener = listener;

        hookApplicationCreate();
        hookBuildTimeRangeToLyricsMap();
        hookMediaPlaybackManager();
        hookPlaybackItemSetId();
    }

    // ------------------------------------------------------------------ 安装

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
        if (sLyricViewModel != null || application == null) return;
        try {
            Class<?> viewModelClass = CoreTool.findClass(VIEW_MODEL_CLASS);
            sLyricViewModel = CoreTool.newInstance(viewModelClass, application);
            AndroidLog.logI(TAG, "Initialized PlayerLyricsViewModel");
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to instantiate PlayerLyricsViewModel: " + t.getMessage());
        }
    }

    private static void ensureLyricViewModel() {
        if (sLyricViewModel != null) return;
        android.content.Context context = com.hchen.superlyric.publisher.AbsPublisher.getAppContext();
        if (context instanceof Application application) {
            initLyricViewModel(application);
        }
    }

    private static void hookBuildTimeRangeToLyricsMap() {
        try {
            CoreTool.hookMethod(VIEW_MODEL_CLASS,
                "buildTimeRangeToLyricsMap",
                "com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoPtr",
                new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            Object songInfoPtr = getArg(0);
                            if (songInfoPtr != null) handleSongInfo(songInfoPtr);
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in buildTimeRangeToLyricsMap: " + t.getMessage());
                        }
                    }
                });
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to hook buildTimeRangeToLyricsMap: " + t.getMessage());
        }
    }

    private static void hookMediaPlaybackManager() {
        try {
            Class<?> managerClass = DexkitCache.findMember(PM_CACHE_KEY, new IDexkit<ClassData>() {
                @Override
                public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings(PM_ANCHOR)
                        )
                    ).singleOrNull();
                }
            });
            if (managerClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate MediaPlaybackManager");
                return;
            }

            CoreTool.hookMethod(managerClass,
                "onCurrentItemChanged",
                "com.apple.android.music.playback.controller.MediaPlayerController",
                "com.apple.android.music.playback.model.PlayerQueueItem",
                "com.apple.android.music.playback.model.PlayerQueueItem",
                new AbsHook() {
                    @Override
                    public void before() {
                        handlePlayerQueueItem(getArg(2));
                    }
                });

            CoreTool.hookMethod(managerClass,
                "onMetadataUpdated",
                "com.apple.android.music.playback.controller.MediaPlayerController",
                "com.apple.android.music.playback.model.PlayerQueueItem",
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

    /**
     * 兜底：从队列构建路径上捕获 {@code PlaybackItem} 实体。
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

                            // 只接受来自"队列逐项构建"这一条路径的赋值，避免误捕其它 setId
                            boolean fromQueueBuild = false;
                            boolean fromCallback = false;
                            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                                String frame = element.toString();
                                if (!fromQueueBuild && frame.contains("getItemAtIndex")) fromQueueBuild = true;
                                if (!fromCallback && frame.contains(".accept")) fromCallback = true;
                                if (fromQueueBuild && fromCallback) break;
                            }
                            if (fromQueueBuild && fromCallback) {
                                Object item = getThisObject();
                                sCurrentPlaybackItem = item;
                                scheduleRequestLyrics(item);
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

    // ------------------------------------------------------------------ 触发

    private static void handlePlayerQueueItem(@Nullable Object queueItem) {
        if (queueItem == null) return;
        try {
            Object mediaItem = CoreTool.callMethodIfExists(queueItem, "getItem");
            if (mediaItem == null) return;

            Object candidate = CoreTool.getFieldIfExists(mediaItem, "playbackItem");
            if (candidate == null) candidate = mediaItem;

            Class<?> playbackItemClass = CoreTool.findClass(PLAYBACK_ITEM_CLASS);
            if (playbackItemClass.isInstance(candidate)) {
                sCurrentPlaybackItem = candidate;
                scheduleRequestLyrics(candidate);
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to handle PlayerQueueItem: " + t.getMessage());
        }
    }

    private static synchronized void scheduleRequestLyrics(@NonNull Object playbackItem) {
        String itemId = readItemId(playbackItem);
        if (itemId != null && itemId.equals(sLastRequestedItemId)) return;

        Runnable pending = sPendingRequest;
        if (pending != null) MAIN_HANDLER.removeCallbacks(pending);

        Runnable task = () -> {
            sLastRequestedItemId = itemId;
            requestLyrics(playbackItem);
        };
        sPendingRequest = task;
        MAIN_HANDLER.postDelayed(task, REQUEST_DEBOUNCE_MS);
    }

    private static void requestLyrics(@NonNull Object playbackItem) {
        try {
            if (!hasLyrics(playbackItem)) {
                AndroidLog.logI(TAG, "PlaybackItem reports no lyrics available");
                notifyNoLyrics();
                return;
            }

            ensureLyricViewModel();
            Object viewModel = sLyricViewModel;
            if (viewModel == null) {
                AndroidLog.logD(TAG, "PlayerLyricsViewModel unavailable; cannot request lyrics");
                return;
            }
            AndroidLog.logD(TAG, "Requesting lyrics for " + readItemId(playbackItem));
            CoreTool.callMethodIfExists(viewModel, "loadLyrics", playbackItem);
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to call loadLyrics: " + t.getMessage());
        }
    }

    /**
     * @return false 表示宿主明确声明该曲目没有歌词
     */
    private static boolean hasLyrics(@NonNull Object playbackItem) {
        try {
            Object hasLyrics = CoreTool.callMethodIfExists(playbackItem, "hasLyrics");
            Object hasSynced = CoreTool.callMethodIfExists(playbackItem, "hasTimeSyncedLyrics");
            if (Boolean.FALSE.equals(hasLyrics) && Boolean.FALSE.equals(hasSynced)) {
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

    // ------------------------------------------------------------------ 抽取

    /**
     * 遍历 JNI 侧的 {@code LyricsSectionVector} 取出全部歌词行。
     */
    private static void handleSongInfo(@NonNull Object songInfoPtr) {
        Object songInfo = CoreTool.callMethodIfExists(songInfoPtr, "get");
        if (songInfo == null) return;

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
            AndroidLog.logD(TAG, "No lyric sections for the current track");
            notifyNoLyrics();
            return;
        }

        List<AppleLine> lines = new ArrayList<>();
        long sectionCount = asLong(CoreTool.callMethodIfExists(sections, "size"));

        for (long s = 0; s < sectionCount; s++) {
            Object section = unwrap(CoreTool.callMethodIfExists(sections, "get", s));
            if (section == null) continue;

            Object sectionLines = CoreTool.callMethodIfExists(section, "getLines");
            if (sectionLines == null) continue;

            long lineCount = asLong(CoreTool.callMethodIfExists(sectionLines, "size"));
            for (long l = 0; l < lineCount; l++) {
                Object line = unwrap(CoreTool.callMethodIfExists(sectionLines, "get", l));
                if (line == null) continue;

                AppleLine extracted = extractLine(line);
                if (extracted != null) lines.add(extracted);
            }
        }

        if (lines.isEmpty()) {
            notifyNoLyrics();
        } else {
            LyricsListener listener = sListener;
            if (listener != null) listener.onLyrics(Collections.unmodifiableList(lines));
        }
    }

    @Nullable
    private static AppleLine extractLine(@NonNull Object line) {
        Object rawText = CoreTool.callMethodIfExists(line, "getHtmlLineText");
        Object rawTranslation = CoreTool.callMethodIfExists(line, "getHtmlTranslationLineText");
        Object begin = CoreTool.callMethodIfExists(line, "getBegin");
        Object end = CoreTool.callMethodIfExists(line, "getEnd");

        if (!(rawText instanceof String text) || !(begin instanceof Integer start) || !(end instanceof Integer stop)) {
            return null;
        }

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
                if (wordText instanceof String wt && wordBegin instanceof Integer wb && wordEnd instanceof Integer we) {
                    words.add(new AppleWord(wt, wb, we));
                }
            }
        }

        String translation = rawTranslation instanceof String t ? t : null;
        return new AppleLine(text, translation, start, stop, words);
    }

    /**
     * JNI 向量元素是"指针包装对象"，需要再取一次 {@code get()} 才能拿到实体。
     */
    @Nullable
    private static Object unwrap(@Nullable Object pointer) {
        if (pointer == null) return null;
        Object value = CoreTool.callMethodIfExists(pointer, "get");
        return value != null ? value : pointer;
    }

    private static long asLong(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static void notifyNoLyrics() {
        LyricsListener listener = sListener;
        if (listener != null) listener.onNoLyrics();
    }
}
