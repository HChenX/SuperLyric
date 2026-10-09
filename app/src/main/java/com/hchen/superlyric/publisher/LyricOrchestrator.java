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
package com.hchen.superlyric.publisher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.engine.ILegacyLyricEngine;
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 双轨协同调度器。
 * <p>
 * 严格执行严格串行降级策略：
 * <ol>
 *   <li>默认优先交由 {@link IHookLyricEngine} 拦截宿主内部解析后的全量歌词；</li>
 *   <li>仅当 Hook 明确判定无效（无歌词、解析崩溃等）时，平滑降级至 {@link INetworkLyricEngine} 拉取网络全量歌词；</li>
 *   <li>若网络引擎亦失效或不支持，且配置了 {@link ILegacyLyricEngine}，则最终回退至单句 Hook 兜底，防止应用彻底失效。</li>
 * </ol>
 *
 * @author 焕晨HChen
 */
public final class LyricOrchestrator {
    private static final String TAG = "LyricOrchestrator";

    public enum State {
        IDLE,
        HOOK_RESOLVING,
        NETWORK_FETCHING,
        FULL_ACTIVE,
        LEGACY_FALLBACK
    }

    @Nullable
    private final IHookLyricEngine mHookEngine;
    @Nullable
    private final INetworkLyricEngine mNetworkEngine;
    @Nullable
    private final ILegacyLyricEngine mLegacyEngine;
    @NonNull
    private final PlaybackTracker mTracker;

    private final AtomicReference<State> mState = new AtomicReference<>(State.IDLE);
    private final AtomicReference<TrackContext> mCurrentTrack = new AtomicReference<>();

    public LyricOrchestrator(@Nullable IHookLyricEngine hookEngine,
                             @Nullable INetworkLyricEngine networkEngine,
                             @Nullable ILegacyLyricEngine legacyEngine,
                             @NonNull PlaybackTracker tracker) {
        this.mHookEngine = hookEngine;
        this.mNetworkEngine = networkEngine;
        this.mLegacyEngine = legacyEngine;
        this.mTracker = tracker;
    }

    public State getState() {
        return mState.get();
    }

    /**
     * 切歌事件触发：重置状态并优先由 Hook 引擎尝试获取。
     */
    public synchronized void onTrackChanged(@NonNull TrackContext context) {
        TrackContext current = mCurrentTrack.get();
        if (current != null && current.getTrackId().equals(context.getTrackId())) {
            // 同一首歌曲：严禁因蓝牙歌词导致 MediaSession title 变更而误判为切歌或重置时钟
            // 仅在缺失必要信息时进行无害元数据补全，保持当前曲目真实元数据稳定
            TrackContext merged = new TrackContext(
                current.getGeneration(),
                current.getTrackId(),
                !current.getTitle().isEmpty() ? current.getTitle() : context.getTitle(),
                !current.getArtist().isEmpty() ? current.getArtist() : context.getArtist(),
                !current.getAlbum().isEmpty() ? current.getAlbum() : context.getAlbum(),
                current.getDuration() > 0 ? current.getDuration() : context.getDuration()
            );
            mCurrentTrack.set(merged);
            AndroidLog.logD(TAG, "Track metadata updated for same track: " + context.getTrackId() + ", skipping re-resolution");
            return;
        }

        mCurrentTrack.set(context);
        mTracker.stop();
        AbsPublisher.publishStop();

        if (mNetworkEngine != null) {
            mNetworkEngine.cancel();
            mNetworkEngine.onTrackChanged(context);
        }
        if (mLegacyEngine != null) {
            mLegacyEngine.disableLegacyHook();
            mLegacyEngine.onTrackChanged(context);
        }

        if (mHookEngine != null) {
            AndroidLog.logD(TAG, "Track changed: " + context + ", starting hook resolution");
            mState.set(State.HOOK_RESOLVING);
            mHookEngine.onTrackChanged(context);
            try {
                SuperLyricData hookData = mHookEngine.tryExtractFullLyric(context);
                if (hookData != null && hookData.hasAllLyrics()) {
                    onFullLyricResolved(context, hookData, "Hook");
                    return;
                }
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Hook resolution determined ineffective for " + context.getTrackId() + ": " + t.getMessage());
                // Hook 确定无效，触发降级
                onHookDeterminedInvalid(context);
                return;
            }
            if (mNetworkEngine != null) {
                AndroidLog.logI(TAG, "No immediate hook lyrics for " + context.getTrackId() + ", actively requesting via network engine");
                onHookDeterminedInvalid(context);
                return;
            }
        } else if (mNetworkEngine != null) {
            AndroidLog.logI(TAG, "Track changed (NETWORK_ONLY): " + context + ", actively fetching via network engine");
            startNetworkFetch(context);
        } else {
            fallbackToLegacy(context);
        }
    }

    /**
     * Hook 引擎异步截获到解析产物时通知调度器。
     */
    public synchronized void onHookFullLyricCaptured(@NonNull TrackContext context, @NonNull SuperLyricData fullData) {
        TrackContext current = mCurrentTrack.get();
        if (current == null || !current.getTrackId().equals(context.getTrackId())) {
            AndroidLog.logD(TAG, "Discard stale hook lyric for: " + context.getTrackId());
            return;
        }

        onFullLyricResolved(context, fullData, "HookAsync");
    }

    /**
     * Hook 确定无效（由 HookEngine 显式上报无歌词或捕获异常）。
     */
    public synchronized void onHookDeterminedInvalid(@NonNull TrackContext context) {
        TrackContext current = mCurrentTrack.get();
        if (current == null || !current.matches(context.getTrackId(), context.getGeneration())) {
            return;
        }

        // 仅在当前处于 HOOK_RESOLVING 时转移
        if (mState.get() != State.HOOK_RESOLVING) return;

        if (mNetworkEngine != null) {
            AndroidLog.logI(TAG, "Hook ineffective, falling back to network engine for " + context.getTrackId());
            startNetworkFetch(context);
        } else {
            fallbackToLegacy(context);
        }
    }

    /**
     * 启动网络引擎拉取全量歌词。
     */
    private void startNetworkFetch(@NonNull TrackContext context) {
        if (mNetworkEngine == null) {
            fallbackToLegacy(context);
            return;
        }

        mState.set(State.NETWORK_FETCHING);
        mNetworkEngine.fetchFullLyric(context)
            .thenAccept(netData -> {
                synchronized (LyricOrchestrator.this) {
                    TrackContext active = mCurrentTrack.get();
                    if (active != null && active.matches(context.getTrackId(), context.getGeneration())) {
                        if (mState.get() == State.FULL_ACTIVE) {
                            AndroidLog.logD(TAG, "Network lyric fetched but full lyric already active, skipping overwrite");
                            return;
                        }
                        if (netData != null && netData.hasAllLyrics()) {
                            onFullLyricResolved(context, netData, "Network");
                        } else {
                            fallbackToLegacy(context);
                        }
                    }
                }
            })
            .exceptionally(err -> {
                synchronized (LyricOrchestrator.this) {
                    AndroidLog.logW(TAG, "Network fetch failed for " + context.getTrackId(), err);
                    fallbackToLegacy(context);
                }
                return null;
            });
    }

    /**
     * 全量歌词成功解析就绪：发布全量包并移交 Tracker 推进进度。
     */
    private void onFullLyricResolved(@NonNull TrackContext context, @NonNull SuperLyricData fullData, String sourceTag) {
        mState.set(State.FULL_ACTIVE);
        if (mLegacyEngine != null) {
            mLegacyEngine.disableLegacyHook();
        }

        // 确保元数据完备
        if (!fullData.hasTitle() && !context.getTitle().isEmpty())
            fullData.setTitle(context.getTitle());
        if (!fullData.hasArtist() && !context.getArtist().isEmpty())
            fullData.setArtist(context.getArtist());
        if (!fullData.hasAlbum() && !context.getAlbum().isEmpty())
            fullData.setAlbum(context.getAlbum());
        if (!fullData.hasLyricId()) fullData.setLyricId(context.getTrackId());
        if (!fullData.hasDuration() && context.getDuration() > 0)
            fullData.setDuration(context.getDuration());

        // 向下兼容：首句填充到单行通道
        if (fullData.hasAllLyrics() && fullData.getAllLyricsCount() > 0 && !fullData.hasLyric()) {
            fullData.setLyric(fullData.getAllLyrics()[0]);
            fullData.setCurrentLyricIndex(0);
        }

        boolean hasTranslation = false;
        boolean hasWords = false;
        long firstStart = 0;
        long lastEnd = 0;
        int lineCount = fullData.getAllLyricsCount();
        if (fullData.hasAllLyrics() && lineCount > 0) {
            SuperLyricLine[] lines = fullData.getAllLyrics();
            firstStart = lines[0].getStartTime();
            lastEnd = lines[lines.length - 1].getEndTime();
            for (SuperLyricLine line : lines) {
                if (line.hasTranslation()) hasTranslation = true;
                if (line.getWords() != null && line.getWords().length > 0) hasWords = true;
                if (hasTranslation && hasWords) break;
            }
        }

        AndroidLog.logI(TAG, "Full lyric active via [" + sourceTag + "] for track=" + context.getTrackId()
            + " | title=" + fullData.getTitle()
            + " | artist=" + fullData.getArtist()
            + " | lines=" + lineCount
            + " | span=[" + firstStart + "ms.." + lastEnd + "ms]"
            + " | hasTrans=" + hasTranslation
            + " | hasWords=" + hasWords);

        AbsPublisher.publishFullLyric(fullData);
        mTracker.setLyrics(fullData);
    }

    /**
     * 最终降级至单句 Hook 兜底模式。
     */
    private void fallbackToLegacy(@NonNull TrackContext context) {
        if (mLegacyEngine != null) {
            AndroidLog.logI(TAG, "All full lyric sources ineffective, falling back to legacy single-line hook for " + context.getTrackId());
            mState.set(State.LEGACY_FALLBACK);
            mTracker.stop();
            mLegacyEngine.enableLegacyHook();
        } else {
            AndroidLog.logD(TAG, "No legacy hook available, keeping blank for " + context.getTrackId());
            mState.set(State.IDLE);
            mTracker.stop();
            AbsPublisher.publishStop();
        }
    }

    public synchronized void onPlaybackStopped() {
        AndroidLog.logD(TAG, "onPlaybackStopped: stopping tracker and resetting orchestrator to IDLE");
        mTracker.stop();
        AbsPublisher.publishStop();
        if (mLegacyEngine != null) {
            mLegacyEngine.disableLegacyHook();
        }
        mState.set(State.IDLE);
    }
}
