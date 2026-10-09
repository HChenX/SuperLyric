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
package com.hchen.superlyric.provider;

import android.app.Application;
import android.content.Context;
import android.media.MediaMetadata;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.publisher.AbsPublisher;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Apple Music 统一歌词提供者。
 * <p>
 * <b>逆向适配与架构设计说明 (Apple Music 6.5.3+)：</b>
 * <ul>
 *   <li><b>主动式全量歌词加载：</b>
 *       通过挂载宿主内部核心 {@code PlayerLyricsViewModel}，在感知切歌后主动触发其内部 {@code loadLyrics(PlaybackItem)}，
 *       使宿主在后台或未打开歌词界面的情况下依然能够完成整首 TTML 逐字歌词拉取与解析。</li>
 *   <li><b>原生 C++/JNI 指针流水线拦截：</b>
 *       直接在 {@code PlayerLyricsViewModel.buildTimeRangeToLyricsMap(SongInfo$SongInfoPtr)} 入口处拦截，
 *       注入系统语言翻译（{@code LocaleUtil.getSystemLyricsLanguage()}），并逐行遍历 C++ 包装容器
 *       {@code LyricsSectionVector} 与 {@code LyricsWordVector}，零损耗提取全量原文、逐字高精时间戳与官方翻译。</li>
 *   <li><b>高精度双轨状态同步：</b>
 *       结合系统 {@code MediaSession} 播放状态与宿主 {@code MediaPlayerController} 进度回调，
 *       由 {@code PlaybackTracker} 进行毫秒级推演与跨行轻量推送，彻底消除旧版高频轮询的能耗与丢帧缺陷。</li>
 * </ul>
 *
 * @author YuKongA
 * @author AnserJim
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.apple.android.music")
public class AppleMusicProvider extends UnifiedLyricProvider {
    private static final String TAG = "AppleMusicProvider";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();
    private final AtomicReference<SuperLyricData> mPendingLyricData = new AtomicReference<>();

    private volatile Object sLyricViewModel;
    private volatile Object sCurrentPlaybackItem;
    private volatile String mLastRequestedItemId;
    private Runnable mPendingRequestRunnable;

    public AppleMusicProvider() {
        super();
    }

    @NonNull
    @Override
    public ProviderCapability capability() {
        return ProviderCapability.FULL_HOOK_ONLY;
    }

    @android.annotation.SuppressLint("WrongConstant")
    @Nullable
    @Override
    protected String extractTrackId(@NonNull MediaMetadata metadata) {
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (mediaId != null && !mediaId.trim().isEmpty()) {
            return mediaId.trim();
        }
        String appleMediaId = metadata.getString("com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID");
        if (appleMediaId != null && !appleMediaId.trim().isEmpty()) {
            return appleMediaId.trim();
        }
        return super.extractTrackId(metadata);
    }

    @Nullable
    @Override
    protected IHookLyricEngine createHookEngine() {
        return new IHookLyricEngine() {
            @Override
            public void initHooks() {
                hookApplicationCreate();
                hookMediaPlaybackManager();
                hookPlaybackItemSetId();
                hookBuildTimeRangeToLyricsMap();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
                SuperLyricData pending = mPendingLyricData.get();
                if (pending != null && !Objects.equals(pending.getLyricId(), context.getTrackId())) {
                    mPendingLyricData.set(null);
                }
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                mActiveTrack.set(context);
                SuperLyricData pending = mPendingLyricData.get();
                if (pending != null && (pending.getLyricId() == null || pending.getLyricId().equals(context.getTrackId()))) {
                    return pending;
                }
                return null;
            }
        };
    }

    /**
     * Hook 宿主 Application.onCreate 以初始化 PlayerLyricsViewModel 实例。
     */
    private void hookApplicationCreate() {
        hookMethod("com.apple.android.music.AppleMusicApplication",
            "onCreate",
            new AbsHook() {
                @Override
                public void after() {
                    try {
                        Application app = (Application) getThisObject();
                        initLyricViewModel(app);
                    } catch (Throwable t) {
                        logE(tag, "Failed to initialize LyricViewModel in onCreate", t);
                    }
                }
            }
        );
    }

    private synchronized void initLyricViewModel(@NonNull Application app) {
        if (sLyricViewModel != null) return;
        try {
            Class<?> vmClass = findClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
            sLyricViewModel = newInstance(vmClass, app);
            logI(tag, "Initialized PlayerLyricsViewModel successfully");
        } catch (Throwable t) {
            logE(tag, "Failed to instantiate PlayerLyricsViewModel", t);
        }
    }

    private void ensureLyricViewModel() {
        if (sLyricViewModel != null) return;
        Context ctx = AbsPublisher.getAppContext();
        if (ctx instanceof Application app) {
            initLyricViewModel(app);
        }
    }

    /**
     * Hook MediaPlaybackManager（通过特征常量从 DexKit 动态定位混淆类）。
     */
    private void hookMediaPlaybackManager() {
        try {
            Class<?> mediaPlaybackManagerClass = DexkitCache.findMember("apple_pm_class_v1", new IDexkit<ClassData>() {
                @NonNull
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingStrings("Unrecognized PlayerTimedTextItem.AnchorType: ")
                        )
                    ).single();
                }
            });

            if (mediaPlaybackManagerClass == null) {
                logE(tag, "Failed to locate MediaPlaybackManager class via Dexkit");
                return;
            }

            hookMethod(mediaPlaybackManagerClass,
                "onCurrentItemChanged",
                "com.apple.android.music.playback.controller.MediaPlayerController",
                "com.apple.android.music.playback.model.PlayerQueueItem",
                "com.apple.android.music.playback.model.PlayerQueueItem",
                new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            Object newItem = getArg(2);
                            handlePlayerQueueItem(newItem);
                        } catch (Throwable t) {
                            logE(tag, "Error in onCurrentItemChanged", t);
                        }
                    }
                }
            );

            hookMethod(mediaPlaybackManagerClass,
                "onMetadataUpdated",
                "com.apple.android.music.playback.controller.MediaPlayerController",
                "com.apple.android.music.playback.model.PlayerQueueItem",
                new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            Object currentItem = getArg(1);
                            handlePlayerQueueItem(currentItem);
                        } catch (Throwable t) {
                            logE(tag, "Error in onMetadataUpdated", t);
                        }
                    }
                }
            );

            hookMethod(mediaPlaybackManagerClass,
                "onPlaybackStateChanged",
                "com.apple.android.music.playback.controller.MediaPlayerController",
                int.class,
                int.class,
                new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            Object controller = getArg(0);
                            int newState = (int) getArg(2);
                            if (controller != null && newState == 1) {
                                Object posObj = callMethod(controller, "getCurrentPosition");
                                if (posObj instanceof Number num) {
                                    mTracker.onPositionSynced(num.longValue());
                                }
                            }
                        } catch (Throwable t) {
                            logE(tag, "Error in onPlaybackStateChanged", t);
                        }
                    }
                }
            );

            logI(tag, "Hooked MediaPlaybackManager: " + mediaPlaybackManagerClass.getName());
        } catch (Throwable t) {
            logE(tag, "Failed to hook MediaPlaybackManager", t);
        }
    }

    /**
     * 从 PlayerQueueItem 中抽取 PlaybackItem 实例并触发请求。
     */
    private void handlePlayerQueueItem(@Nullable Object queueItem) {
        if (queueItem == null) return;
        try {
            Object mediaItem = callMethod(queueItem, "getItem");
            if (mediaItem == null) return;

            Object pi = getField(mediaItem, "playbackItem");
            Object candidate = pi != null ? pi : mediaItem;
            Class<?> playbackItemClass = findClass("com.apple.android.music.model.PlaybackItem");
            if (playbackItemClass.isInstance(candidate)) {
                sCurrentPlaybackItem = candidate;
                scheduleRequestLyrics(candidate);
            }
        } catch (Throwable t) {
            logW(tag, "Failed to handle PlayerQueueItem: " + t.getMessage());
        }
    }

    /**
     * 兜底 Hook BaseContentItem.setId 以捕获队列构建中的 PlaybackItem 实体。
     */
    private void hookPlaybackItemSetId() {
        try {
            Class<?> playbackItemClass = findClass("com.apple.android.music.model.PlaybackItem");
            hookMethodIfExists("com.apple.android.music.model.BaseContentItem",
                "setId",
                String.class,
                new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            if (playbackItemClass.isInstance(getThisObject())) {
                                String trackId = (String) getArg(0);
                                if (trackId == null || trackId.isEmpty()) return;

                                boolean hasGetItemAtIndex = false;
                                boolean hasAccept = false;
                                for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                                    String s = element.toString();
                                    if (!hasGetItemAtIndex && s.contains("getItemAtIndex"))
                                        hasGetItemAtIndex = true;
                                    if (!hasAccept && s.contains(".accept")) hasAccept = true;
                                    if (hasGetItemAtIndex && hasAccept) break;
                                }
                                if (hasGetItemAtIndex && hasAccept) {
                                    sCurrentPlaybackItem = getThisObject();
                                    scheduleRequestLyrics(getThisObject());
                                }
                            }
                        } catch (Throwable t) {
                            logW(tag, "Error in PlaybackItem.setId hook: " + t.getMessage());
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logE(tag, "Failed to hook PlaybackItem.setId", t);
        }
    }

    /**
     * 防抖调度触发歌词加载。
     */
    private synchronized void scheduleRequestLyrics(@NonNull Object playbackItem) {
        String itemId = getPlaybackItemId(playbackItem);
        if (itemId != null && itemId.equals(mLastRequestedItemId)) {
            return;
        }

        if (mPendingRequestRunnable != null) {
            mMainHandler.removeCallbacks(mPendingRequestRunnable);
        }

        mPendingRequestRunnable = () -> {
            mLastRequestedItemId = itemId;
            requestLyrics(playbackItem);
        };
        mMainHandler.postDelayed(mPendingRequestRunnable, 350);
    }

    @Nullable
    private String getPlaybackItemId(@NonNull Object playbackItem) {
        try {
            Object id = callMethod(playbackItem, "getId");
            if (id != null) return id.toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 调用 PlayerLyricsViewModel.loadLyrics(PlaybackItem) 触发歌词拉取。
     */
    private void requestLyrics(@NonNull Object playbackItem) {
        try {
            // 校验是否有歌词能力
            boolean hasAnyLyrics = true;
            try {
                Boolean hl = (Boolean) callMethod(playbackItem, "hasLyrics");
                Boolean htsl = (Boolean) callMethod(playbackItem, "hasTimeSyncedLyrics");
                if (Boolean.FALSE.equals(hl) && Boolean.FALSE.equals(htsl)) {
                    hasAnyLyrics = false;
                }
            } catch (Throwable ignored) {
            }

            if (!hasAnyLyrics) {
                logI(tag, "Current PlaybackItem has no lyrics available");
                notifyLyricsInvalid();
                return;
            }

            ensureLyricViewModel();
            if (sLyricViewModel != null) {
                String itemId = getPlaybackItemId(playbackItem);
                logD(tag, "Requesting lyrics via PlayerLyricsViewModel.loadLyrics for: " + itemId);
                callMethod(sLyricViewModel, "loadLyrics", playbackItem);
            } else {
                logD(tag, "Unable to request lyrics - PlayerLyricsViewModel not available");
            }
        } catch (Throwable t) {
            logE(tag, "Error calling PlayerLyricsViewModel.loadLyrics", t);
        }
    }

    /**
     * Hook PlayerLyricsViewModel.buildTimeRangeToLyricsMap 拦截底层解析完成的 SongInfo 指针。
     */
    private void hookBuildTimeRangeToLyricsMap() {
        hookMethod("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            "buildTimeRangeToLyricsMap",
            "com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoPtr",
            new AbsHook() {
                @Override
                public void after() {
                    try {
                        Object songInfoPtr = getArg(0);
                        if (songInfoPtr == null) return;
                        handleSongInfo(songInfoPtr);
                    } catch (Throwable t) {
                        logE(tag, "Error in buildTimeRangeToLyricsMap", t);
                    }
                }
            }
        );
    }

    /**
     * 从 JNI 原生对象深度解析整首歌词数据，组装为标准 SuperLyricData。
     */
    private void handleSongInfo(@NonNull Object songInfoPtr) {
        Object songInfo = callMethod(songInfoPtr, "get");
        if (songInfo == null) return;

        // 1. 设置系统语言翻译
        try {
            String sysLang = (String) callStaticMethod("com.apple.android.music.playback.util.LocaleUtil", "getSystemLyricsLanguage");
            if (sysLang != null && !sysLang.isEmpty()) {
                callMethod(songInfo, "setTranslation", sysLang);
            }
        } catch (Throwable t) {
            logW(tag, "Failed to set lyrics translation language: " + t.getMessage());
        }

        // 2. 提取 sections
        Object sections = callMethod(songInfo, "getSections");
        if (sections == null) {
            logD(tag, "LyricsSectionVector is null, no lyrics for current track");
            notifyLyricsInvalid();
            return;
        }

        long sectionCount = (long) callMethod(sections, "size");
        List<SuperLyricLine> lineList = new ArrayList<>();

        for (long s = 0; s < sectionCount; s++) {
            Object sectionPtr = callMethod(sections, "get", s);
            if (sectionPtr == null) continue;
            Object section = callMethod(sectionPtr, "get");
            if (section == null) continue;

            Object lines = callMethod(section, "getLines");
            if (lines == null) continue;
            long lineCount = (long) callMethod(lines, "size");

            for (long l = 0; l < lineCount; l++) {
                Object linePtr = callMethod(lines, "get", l);
                if (linePtr == null) continue;
                Object line = callMethod(linePtr, "get");
                if (line == null) continue;

                String rawLyric = (String) callMethod(line, "getHtmlLineText");
                Integer start = (Integer) callMethod(line, "getBegin");
                Integer end = (Integer) callMethod(line, "getEnd");
                String rawTrans = (String) callMethod(line, "getHtmlTranslationLineText");

                if (rawLyric == null || start == null || end == null) continue;

                String cleanLyric = cleanHtmlText(rawLyric);
                String cleanTrans = cleanHtmlText(rawTrans);

                SuperLyricWord[] superLyricWords = null;
                Object words = callMethod(line, "getWords");
                if (words != null) {
                    long wordSize = (long) callMethod(words, "size");
                    if (wordSize > 0) {
                        List<SuperLyricWord> wordList = new ArrayList<>((int) wordSize);
                        for (long w = 0; w < wordSize; w++) {
                            Object wordPtr = callMethod(words, "get", w);
                            if (wordPtr == null) continue;
                            Object word = callMethod(wordPtr, "get");
                            if (word == null) continue;

                            String rawWord = (String) callMethod(word, "getHtmlLineText");
                            Integer subStart = (Integer) callMethod(word, "getBegin");
                            Integer subEnd = (Integer) callMethod(word, "getEnd");

                            if (rawWord != null && subStart != null && subEnd != null) {
                                wordList.add(new SuperLyricWord(cleanHtmlText(rawWord), subStart, subEnd));
                            }
                        }
                        if (!wordList.isEmpty()) {
                            LyricSanitizer.healWordTimings(wordList, start, end);
                            superLyricWords = wordList.toArray(new SuperLyricWord[0]);
                        }
                    }
                }

                SuperLyricLine lyricLine = new SuperLyricLine(cleanLyric, superLyricWords, cleanTrans, start, end);
                SuperLyricLine sanitized = LyricSanitizer.sanitizeLine(lyricLine);
                if (sanitized != null) {
                    lineList.add(sanitized);
                }
            }
        }

        if (lineList.isEmpty()) {
            logD(tag, "No lyrics lines parsed from songInfo");
            notifyLyricsInvalid();
            return;
        }

        // 3. 构建全量 SuperLyricData
        SuperLyricData fullData = new SuperLyricData();
        fullData.setAllLyrics(lineList.toArray(new SuperLyricLine[0]));

        String songLyricsId = null;
        try {
            songLyricsId = (String) callMethod(songInfo, "getLyricsId");
        } catch (Throwable ignored) {
        }
        if (songLyricsId != null && !songLyricsId.isEmpty()) {
            fullData.setLyricId(songLyricsId);
        }

        try {
            Object durObj = callMethod(songInfo, "getDuration");
            if (durObj instanceof Number num && num.longValue() > 0) {
                fullData.setDuration(num.longValue());
            }
        } catch (Throwable ignored) {
        }

        SuperLyricData cleanData = LyricSanitizer.sanitizeData(fullData);
        if (cleanData == null) {
            logW(tag, "Sanitized Apple Music lyric data is null");
            notifyLyricsInvalid();
            return;
        }

        mPendingLyricData.set(cleanData);

        TrackContext active = mActiveTrack.get();
        if (active == null) {
            long gen = mTrackGeneration.incrementAndGet();
            String tid = songLyricsId != null ? songLyricsId : "apple_track";
            active = new TrackContext(gen, tid, "", "", "", fullData.getDuration());
            mActiveTrack.set(active);
            if (mOrchestrator != null) {
                mOrchestrator.onTrackChanged(active);
            }
        }

        if (mOrchestrator != null) {
            logI(tag, "Delivering captured Apple Music full lyrics: lines=" + lineList.size()
                + ", trackId=" + active.getTrackId());
            mOrchestrator.onHookFullLyricCaptured(active, cleanData);
        }
    }

    private void notifyLyricsInvalid() {
        mPendingLyricData.set(null);
        TrackContext active = mActiveTrack.get();
        if (active != null && mOrchestrator != null) {
            mOrchestrator.onHookDeterminedInvalid(active);
        }
    }

    @Nullable
    private static String cleanHtmlText(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            if (raw.indexOf('&') >= 0 || raw.indexOf('<') >= 0) {
                String decoded = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString();
                return LyricSanitizer.cleanInvisibleChars(decoded);
            }
        } catch (Throwable ignored) {
        }
        return LyricSanitizer.cleanInvisibleChars(raw);
    }
}
