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
package com.hchen.superlyric.core;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.bridge.LyricDocAdapter;
import com.hchen.superlyric.kernel.model.LyricDoc;
import com.hchen.superlyric.kernel.orchestrate.PlaybackSignal;
import com.hchen.superlyric.kernel.orchestrate.StopEvidence;
import com.hchen.superlyric.kernel.orchestrate.StopGate;
import com.hchen.superlyric.publisher.PlaybackTracker;
import com.hchen.superlyric.utils.PlaybackSignals;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.model.StopReason;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统播放时钟、监听器、停止判定与回调编排
 *
 * <p>会话状态与 {@code activePublisher} 都在 {@link PublisherRegistry#lock} 内读写，
 * 对 {@link Host} 的回调一律在锁外发起。停止信号先由 {@link StopGate} 排期，窗口结束后
 * 由 {@link #confirmStop} 统一裁决。
 */
final class SystemPlaybackCoordinator {

    private static final String TAG = "SuperLyricService";

    interface Host {
        void onLyric(@NonNull String packageName, @NonNull SuperLyricData data);

        void onStop(@NonNull String packageName, @NonNull SuperLyricData data,
                    @NonNull StopReason reason);
    }

    final PlaybackTracker tracker = new PlaybackTracker("SuperLyric-SysTracker");
    private final ConcurrentHashMap<String, StopGate> stopGates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StopEvidence> stopEvidence = new ConcurrentHashMap<>();

    /**
     * 待确认的停止原因
     *
     * <p>确认由 {@link StopGate} 在窗口结束后统一触发，触发点无法得知排期来源；v4 的
     * STOP 帧必须区分 PAUSED / STOPPED / ERROR / PROCESS_DIED。PAUSED 不排期，因此本表
     * 只在真正的终态信号上写入；撤销时同步移除，避免下一次停止继承上一次的原因。
     */
    private final ConcurrentHashMap<String, StopReason> pendingStopReasons = new ConcurrentHashMap<>();

    private final PublisherRegistry registry;
    private final Host host;

    @Nullable
    volatile SystemPlayStateListener listener;

    SystemPlaybackCoordinator(@NonNull PublisherRegistry registry,
                              @NonNull Host host,
                              @NonNull StopGate.Scheduler scheduler) {
        this.registry = registry;
        this.host = host;
        this.scheduler = scheduler;
    }

    private final StopGate.Scheduler scheduler;

    StopGate gate(@NonNull String packageName) {
        return stopGates.computeIfAbsent(packageName, pkg -> new StopGate(
            scheduler, () -> confirmStop(pkg), StopGate.DEFAULT_CONFIRM_WINDOW_MS));
    }

    StopEvidence evidence(@NonNull String packageName) {
        return stopEvidence.computeIfAbsent(packageName, ignored -> new StopEvidence());
    }

    boolean cancelPending(@NonNull String packageName) {
        StopGate gate = stopGates.get(packageName);
        boolean cancelled = gate != null && gate.onEvidence() == StopGate.Decision.CANCELLED;
        if (cancelled) {
            pendingStopReasons.remove(packageName);
        }
        return cancelled;
    }

    void remove(@NonNull String packageName) {
        stopGates.remove(packageName);
        stopEvidence.remove(packageName);
        pendingStopReasons.remove(packageName);
    }

    void onSessionDestroyed(@NonNull String packageName) {
        if (!registry.isPublisher(packageName)) return;

        XposedLog.logI(TAG, "System session destroyed: " + packageName);
        boolean armed;
        synchronized (registry.lock) {
            armed = gate(packageName).onSignal(PlaybackSignal.SESSION_GONE) == StopGate.Decision.ARMED;
            if (armed) {
                pendingStopReasons.put(packageName, StopReason.STOPPED);
                evidence(packageName).snapshot();
            }
        }
        if (armed) {
            XposedLog.logD(TAG, "Session gone for " + packageName + "; awaiting confirmation");
        }
    }

    void onPlaybackStateChanged(@NonNull String packageName, @NonNull PlaybackState state) {
        if (!registry.isPublisher(packageName)) return;

        XposedLog.logD(TAG, "onSystemPlaybackStateChanged: pkg=" + packageName
            + ", state=" + state.getState()
            + ", pos=" + state.getPosition() + "ms"
            + ", speed=" + state.getPlaybackSpeed());

        boolean notifyLyric = false;
        SuperLyricData lyricToNotify = null;

        synchronized (registry.lock) {
            PublisherRegistry.Session session = registry.getOrCreateSession(packageName);
            session.playbackState = state;
            applyStopSignalLocked(packageName, state.getState());

            switch (state.getState()) {
                case PlaybackState.STATE_PLAYING -> {
                    if (!TextUtils.equals(registry.activePublisher, packageName)) {
                        String prevPublisher = registry.activePublisher;
                        XposedLog.logI(TAG, "Active publisher switched to playing " + packageName
                            + " (previous: " + prevPublisher + ")");
                        registry.activePublisher = packageName;
                        tracker.stop();

                        if (session.latestLyric != null) {
                            tracker.setLyrics(session.latestDoc);
                            tracker.onPlaybackStateChanged(state);
                            SuperLyricData currentData = session.latestLyric;
                            if (currentData.hasLyric()) {
                                notifyLyric = true;
                                lyricToNotify = currentData;
                            }
                        } else {
                            tracker.setLyrics(null);
                            if (!TextUtils.isEmpty(prevPublisher)) {
                                host.onStop(prevPublisher, new SuperLyricData(), StopReason.STOPPED);
                            }
                        }
                    } else {
                        tracker.onPlaybackStateChanged(state);
                    }
                }
                case PlaybackState.STATE_PAUSED -> {
                    if (TextUtils.equals(registry.activePublisher, packageName)) {
                        tracker.onPlaybackStateChanged(state);
                    }
                }
                case PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE,
                     PlaybackState.STATE_ERROR -> {
                }
                default -> {
                    if (TextUtils.equals(registry.activePublisher, packageName)) {
                        tracker.onPlaybackStateChanged(state);
                    }
                }
            }
        }

        if (notifyLyric && lyricToNotify != null) {
            host.onLyric(packageName, lyricToNotify);
        }
    }

    void onMetadataChanged(@NonNull String packageName, @Nullable MediaMetadata metadata) {
        if (!registry.isPublisher(packageName) || metadata == null) return;

        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        XposedLog.logD(TAG, "onSystemMetadataChanged: pkg=" + packageName
            + ", title=" + title + ", artist=" + artist + ", duration=" + duration);

        cancelPending(packageName);

        synchronized (registry.lock) {
            // 元数据可能先于第一条歌词到达；必须落库，否则 v4 publish 的首次校验
            // 因"尚不知当前曲目"而只能放行
            PublisherRegistry.Session session = registry.getOrCreateSession(packageName);
            session.mediaFacts.update(mediaId, title, duration > 0L ? duration : 0L);
            evidence(packageName).record();

            SuperLyricData base = session.latestLyric;
            if (base != null && base.getDuration() <= 0 && duration > 0) {
                SuperLyricData enriched = new SuperLyricData()
                    .setTitle(base.getTitle())
                    .setArtist(base.getArtist())
                    .setAlbum(base.getAlbum())
                    .setLyricId(base.getLyricId())
                    .setDuration(duration)
                    .setAllLyrics(base.getAllLyrics())
                    .setCurrentLyricIndex(base.getCurrentLyricIndex())
                    .setPosition(base.getPosition())
                    .setLyric(base.getLyric())
                    .setExtra(base.getExtra());
                if (base.hasTranslation()) enriched.setTranslation(base.getTranslation());
                if (base.hasSecondary()) enriched.setSecondary(base.getSecondary());
                session.latestLyric = enriched;

                // v4 投影同样要补：它按末行结束时间推算时长，补不上会让客户端在
                // 末行结束后提前结束进度
                LyricDoc doc = session.latestDoc;
                if (doc != null) {
                    try {
                        LyricDoc enrichedDoc = LyricDocAdapter.INSTANCE.withDuration(doc, duration);
                        if (enrichedDoc != doc) {
                            session.latestDoc = enrichedDoc;
                            session.latestV4Lyric = LyricDocAdapter.INSTANCE.toLyric(enrichedDoc);
                        }
                    } catch (Throwable t) {
                        // 元数据回调跑在 system_server 的主线程上，不能因为一次投影失败中断
                        XposedLog.logW(TAG, "Failed to enrich the v4 document for " + packageName, t);
                    }
                }
            }
        }
    }

    // 调用方必须持有 registry.lock；终态信号排期，其它信号作为新证据撤销排期
    private void applyStopSignalLocked(@NonNull String packageName, int state) {
        PlaybackSignal signal = PlaybackSignals.of(state);
        if (signal != PlaybackSignal.STOPPED && signal != PlaybackSignal.ERROR
            && signal != PlaybackSignal.SESSION_GONE && signal != PlaybackSignal.UNKNOWN) {
            evidence(packageName).record();
        }

        StopReason reason = switch (signal) {
            case ERROR -> StopReason.ERROR;
            case STOPPED, SESSION_GONE -> StopReason.STOPPED;
            // PAUSED 不排期：它不是终止，用户随时可能继续播
            default -> null;
        };

        StopGate gate = gate(packageName);
        switch (gate.onSignal(signal)) {
            case ARMED -> {
                if (reason != null) pendingStopReasons.put(packageName, reason);
                evidence(packageName).snapshot();
                XposedLog.logD(TAG, signal + " for " + packageName + "; awaiting confirmation");
            }
            case CANCELLED -> {
                pendingStopReasons.remove(packageName);
                XposedLog.logD(TAG, "Pending stop for " + packageName + " cancelled by " + signal);
            }
            case IGNORED -> {
                if (gate.isPending()) {
                    XposedLog.logD(TAG, signal + " for " + packageName
                        + " ignored while a stop is pending");
                }
            }
        }
    }

    private void confirmStop(@NonNull String packageName) {
        boolean notify = false;
        StopReason reason = StopReason.STOPPED;
        synchronized (registry.lock) {
            PublisherRegistry.Session session = registry.sessions.get(packageName);
            if (session == null) return;
            if (!evidence(packageName).isStillValid()) {
                pendingStopReasons.remove(packageName);
                XposedLog.logD(TAG, "Stop for " + packageName
                    + " not confirmed: new evidence arrived inside the window");
                return;
            }
            reason = pendingStopReasons.getOrDefault(packageName, StopReason.STOPPED);
            pendingStopReasons.remove(packageName);
            session.clear();
            if (TextUtils.equals(registry.activePublisher, packageName)) {
                registry.activePublisher = null;
                tracker.stop();
                tracker.setLyrics(null);
                notify = true;
            }
        }
        if (notify) {
            XposedLog.logD(TAG, "Playback stopped (confirmed) for " + packageName + ": " + reason);
            host.onStop(packageName, new SuperLyricData(), reason);
        }
    }
}
