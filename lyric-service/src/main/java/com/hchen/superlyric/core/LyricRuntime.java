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

import android.content.pm.ApplicationInfo;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.bridge.LyricDocAdapter;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyric.utils.PlaybackStates;
import com.hchen.superlyric.utils.PublishGuard;
import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.model.Lyric;
import com.hchen.superlyricapi.model.State;
import com.hchen.superlyricapi.model.Stop;
import com.hchen.superlyricapi.model.StopReason;
import com.hchen.superlyricapi.v4.ILyricFrameListener;
import com.hchen.superlyricapi.wire.Frame;
import com.hchen.superlyricapi.wire.WireFormatException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 非 Binder 的歌词运行时：发布者生命周期、会话、时钟、广播与两代数据边界。
 *
 * <p>会话只保存 4.0 Lyric。SuperLyricData 只出现在 3.x 入口与出口，由
 * LyricDocAdapter 转换；Binder 身份与权限判定留在两个 Service 门面。
 */
@SuppressWarnings("deprecation") // 3.x 边界：Binder 入参/出参仍使用 SuperLyricData
public final class LyricRuntime {
    private static final String TAG = "LyricRuntime";

    private static final long QUEUE_OFFER_TIMEOUT_MS = 2_000L;

    // Publisher.PublishResult 的线上取值
    static final int RESULT_OK = 0;
    static final int RESULT_PAYLOAD_TOO_LARGE = 2;
    static final int RESULT_REJECTED = 3;
    static final int RESULT_REJECTED_TRACK_MISMATCH = 4;
    static final int RESULT_REJECTED_MALFORMED = 5;
    static final int RESULT_REJECTED_NOT_REGISTERED = 6;
    static final int RESULT_REJECTED_QUOTA = 7;

    private final Object mAms;
    private final ReceiverBroadcaster mReceivers = new ReceiverBroadcaster();
    private final FrameBroadcaster mFrames = new FrameBroadcaster();

    @Nullable
    private volatile byte[] mLastStopFrame;

    private final ExecutorService mBroadcastExecutor =
        boundedSingleThread("SuperLyric-Broadcaster", 32, true);

    private final ExecutorService mFrameExecutor =
        boundedSingleThread("SuperLyric-FrameBroadcaster", 16, false);

    private final ScheduledExecutorService mStopConfirmer = Executors.newSingleThreadScheduledExecutor(
        daemonFactory("SuperLyric-StopConfirm", false)
    );

    // 死亡通知的数量由发布者配额决定（最多 32 包 x 8 pid），且必须在 AMS 回调线程之外
    // 执行；保持单线程无界队列，不在 AMS 线程内联广播。上界由 PublisherRegistry 的
    // 32 包 x 8 pid 保证，超过配额的通知到不了这里，因此不丢弃死亡通知。
    private final ExecutorService mDeathNotifier = Executors.newSingleThreadExecutor(
        daemonFactory("SuperLyric-DeathNotice", false)
    );

    private final PublisherRegistry publishers = new PublisherRegistry();
    private final SystemPlaybackCoordinator playback;

    public LyricRuntime(@NonNull Object ams) {
        this.mAms = ams;
        this.playback = new SystemPlaybackCoordinator(
            publishers,
            new SystemPlaybackCoordinator.Host() {
                @Override
                public void onLyric(@NonNull String packageName, @NonNull Lyric lyric) {
                    notifyLyric(packageName, lyric);
                }

                @Override
                public void onStop(@NonNull String packageName, @NonNull StopReason reason) {
                    notifyStop(packageName, new SuperLyricData(), reason);
                }
            },
            (delayMs, task) -> mStopConfirmer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        );
        this.playback.tracker.setOnProgressAdvanceListener(this::onLineAdvanced);
    }

    /**
     * 时钟推进到新的一行；只处理全量会话。
     */
    private void onLineAdvanced(int newIndex, long positionMs) {
        try {
            advanceLine(newIndex, positionMs);
        } catch (Throwable t) {
            XposedLog.logE(TAG, "Failed to advance line " + newIndex, t);
        }
    }

    private void advanceLine(int newIndex, long positionMs) {
        String activePackage;
        Lyric lyric;
        int index = Math.max(0, newIndex);
        long position = Math.max(0L, positionMs);
        byte[] stateFrame;

        synchronized (publishers.lock) {
            activePackage = publishers.activePublisher;
            PublisherRegistry.Session session = activePackage != null
                ? publishers.sessions.get(activePackage) : null;
            if (session == null || !session.fullLyric || session.lyric == null
                || !isPublisher(activePackage)) {
                return;
            }
            lyric = session.lyric;
            if (index >= lyric.getLineCount()) return;

            session.positionMs = position;
            session.touch();
            stateFrame = encode(Frame.state(
                buildStateLocked(session, index, position), activePackage));
        }

        notifyLegacyLyric(activePackage, lyric, index, position, true);
        notifyFrames(activePackage, stateFrame);
        mLastStopFrame = null;
    }

    public void setSystemPlayStateListener(@Nullable SystemPlayStateListener listener) {
        this.playback.listener = listener;
    }

    boolean isPublisher(@NonNull String packageName) {
        return PublisherRegistry.isPublisher(packageName);
    }

    private boolean isPublisherProcess(@NonNull String packageName, int pid) {
        return PublisherRegistry.isPublisherProcess(packageName, pid);
    }

    private PublisherRegistry.Registration ensurePublisher(int pid, @NonNull String packageName,
                                                           int uid) {
        if (isPublisherProcess(packageName, pid)) return PublisherRegistry.Registration.OK;
        PublisherRegistry.Registration registration =
            PublisherRegistry.registerPublisher(packageName, pid, uid);
        if (registration == PublisherRegistry.Registration.OK && playback.listener != null) {
            playback.listener.syncCurrentStateForPackage(packageName);
        }
        return registration;
    }

    boolean isSystemPlayStateListenerDisabled(@NonNull String packageName) {
        return PublisherRegistry.isDisabled(packageName);
    }

    void registerPublisher(int pid, @NonNull String packageName, int uid) {
        if (packageName.isEmpty()) {
            XposedLog.logW(TAG, "Registration as publisher failed! Unable to obtain package name for pid: " + pid);
            return;
        }
        PublisherRegistry.Registration registration = ensurePublisher(pid, packageName, uid);
        if (registration == PublisherRegistry.Registration.QUOTA) {
            XposedLog.logW(TAG, "Registration as publisher rejected by quota: " + packageName
                + ", pid: " + pid);
            return;
        }
    }

    void unregisterPublisher(int pid, @NonNull String packageName) {
        if (!packageName.isEmpty()) {
            removePublisherProcess(packageName, pid);
            XposedLog.logI(TAG, "Unregister publisher: " + packageName + ", pid: " + pid);
        } else {
            XposedLog.logW(TAG, "Failed to unregister as publisher for pid: " + pid);
        }
    }

    boolean isPublisherRegistered(int pid, @NonNull String packageName) {
        return PublisherRegistry.isPublisherProcess(packageName, pid);
    }

    // 3.x 入站：转换后立即进入 4.0 会话，不在会话中保存 SuperLyricData。
    void sendLyric(int pid, @NonNull String packageName, int uid, SuperLyricData data) {
        if (data == null) return;

        if (packageName.isEmpty()) {
            XposedLog.logW(TAG, "sendLyric rejected: cannot resolve package for pid " + pid);
            return;
        }
        if (ensurePublisher(pid, packageName, uid) != PublisherRegistry.Registration.OK) {
            XposedLog.logW(TAG, "sendLyric rejected: publisher not registered for " + packageName);
            return;
        }

        Lyric incoming = LyricSanitizer.sanitizeLyric(LyricDocAdapter.INSTANCE.fromLegacy(data));
        if (incoming == null) {
            XposedLog.logW(TAG, "sendLyric dropped: no readable lyric from " + packageName);
            return;
        }

        cancelPendingStop(packageName);

        boolean full = data.hasAllLyrics();
        long position = Math.max(0L, data.getPosition());
        boolean shouldBroadcast;
        boolean syncPlayState = false;
        boolean fullSource;
        int index;
        Lyric lyric;
        byte[] frame = null;

        synchronized (publishers.lock) {
            PublisherRegistry.Session session = getOrCreateSessionLocked(packageName);
            session.publishingPid = pid;
            session.published = true;
            playback.evidence(packageName).record();

            if (full) {
                session.lyric = LyricDocAdapter.INSTANCE.withDuration(
                    incoming, session.mediaFacts.durationMs());
                session.fullLyric = true;
                session.positionMs = position;
                lyric = session.lyric;
                index = legacyIndex(lyric,
                    data.hasCurrentLyricIndex()
                        ? data.getCurrentLyricIndex() : lyric.indexAt(position));
                fullSource = true;
                shouldBroadcast = claimActiveLocked(packageName, session);
                if (shouldBroadcast) {
                    playback.tracker.setLyrics(lyric);
                    if (data.hasPosition()) playback.tracker.onSessionPositionSynced(position);
                    if (session.playbackState != null
                        && session.playbackState.getState() != PlaybackState.STATE_NONE) {
                        playback.tracker.onPlaybackStateChanged(session.playbackState);
                    }
                    syncPlayState = playback.listener != null;
                    frame = fullFrameLocked(session, index, position);
                }
            } else if (session.fullLyric && session.lyric != null) {
                if (data.hasLyricId()
                    && !TextUtils.equals(data.getLyricId(), session.lyric.getId())) {
                    XposedLog.logW(TAG, "Ignoring stale incremental lyric for " + packageName
                        + ": packet trackId=" + data.getLyricId()
                        + ", session trackId=" + session.lyric.getId());
                    return;
                }
                lyric = session.lyric;
                session.positionMs = position;
                index = legacyIndex(lyric,
                    data.hasCurrentLyricIndex()
                        ? data.getCurrentLyricIndex() : lyric.indexAt(position));
                fullSource = true;
                shouldBroadcast = claimActiveLocked(packageName, session);
                if (shouldBroadcast) {
                    if (data.hasPosition()) playback.tracker.onSessionPositionSynced(position);
                    frame = encode(Frame.state(buildStateLocked(session, index, position), packageName));
                }
            } else {
                session.lyric = LyricDocAdapter.INSTANCE.withDuration(
                    incoming, session.mediaFacts.durationMs());
                session.fullLyric = false;
                session.positionMs = position;
                lyric = session.lyric;
                index = 0;
                fullSource = false;
                shouldBroadcast = claimActiveLocked(packageName, session);
                if (shouldBroadcast) {
                    playback.tracker.setLyrics(null);
                    frame = fullFrameLocked(session, 0, position);
                }
            }
        }

        if (!shouldBroadcast) return;

        notifyLegacyLyric(packageName, lyric, index, position, fullSource);
        notifyFrames(packageName, frame);
        if (syncPlayState && playback.listener != null) {
            playback.listener.syncCurrentStateForPackage(packageName);
        }
    }

    void sendStop(int pid, @NonNull String packageName, SuperLyricData data) {
        if (data == null) return;

        if (!isPublisherProcess(packageName, pid)) return;

        XposedLog.logD(TAG, "sendStop from " + packageName + " (pid " + pid + ")");
        cancelPendingStop(packageName);

        StopState stop;
        synchronized (publishers.lock) {
            stop = clearSessionLocked(packageName, pid, StopReason.STOPPED);
        }

        if (stop.wasActive()) {
            if (stop.stopFrame() != null) mLastStopFrame = stop.stopFrame();
            notifyReceiver(packageName, "onStop", capability -> data,
                (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
            notifyFrames(packageName, stop.stopFrame());
        }
    }

    // 4.0 发布：直接清洗 4.0 文档，不再经过 3.x 中转。
    int publishFrame(int pid, @NonNull String packageName, int uid, @Nullable byte[] raw) {
        if (packageName.isEmpty()) {
            XposedLog.logW(TAG, "publish rejected: cannot resolve package for pid " + pid);
            return RESULT_REJECTED_NOT_REGISTERED;
        }
        if (raw == null || raw.length == 0) {
            XposedLog.logW(TAG, "publish rejected: empty frame from " + packageName);
            return RESULT_REJECTED_MALFORMED;
        }
        if (raw.length > Frame.MAX_SIZE) {
            XposedLog.logW(TAG, "publish rejected: frame " + raw.length
                + " bytes exceeds " + Frame.MAX_SIZE + " from " + packageName);
            return RESULT_PAYLOAD_TOO_LARGE;
        }

        Frame frame;
        try {
            frame = Frame.decode(raw);
        } catch (WireFormatException e) {
            XposedLog.logW(TAG, "publish rejected: malformed frame from " + packageName
                + " (" + e.getMessage() + ")");
            return RESULT_REJECTED_MALFORMED;
        } catch (Throwable t) {
            XposedLog.logW(TAG, "publish rejected: undecodable frame from " + packageName, t);
            return RESULT_REJECTED_MALFORMED;
        }
        if (frame.getKind() != Frame.Kind.FULL || frame.getDoc() == null) {
            XposedLog.logW(TAG, "publish rejected: kind=" + frame.getKind()
                + " from " + packageName + "; only FULL frames are publishable");
            return RESULT_REJECTED_MALFORMED;
        }

        PublisherRegistry.Registration registration = ensurePublisher(pid, packageName, uid);
        if (registration == PublisherRegistry.Registration.QUOTA) {
            XposedLog.logW(TAG, "publish rejected: publisher quota reached for " + packageName);
            return RESULT_REJECTED_QUOTA;
        }
        if (registration != PublisherRegistry.Registration.OK) {
            XposedLog.logW(TAG, "publish rejected: publisher not registered for " + packageName);
            return RESULT_REJECTED_NOT_REGISTERED;
        }

        Lyric cleanLyric = LyricSanitizer.sanitizeLyric(frame.getDoc());
        if (cleanLyric == null) {
            XposedLog.logW(TAG, "publish rejected: no publishable line from " + packageName);
            return RESULT_REJECTED_MALFORMED;
        }
        if (cleanLyric.getTitle().trim().isEmpty()) {
            XposedLog.logW(TAG, "publish rejected: blank title from " + packageName);
            return RESULT_REJECTED_MALFORMED;
        }

        cancelPendingStop(packageName);

        boolean shouldBroadcast;
        boolean syncPlayState = false;
        byte[] fullFrame;
        long position;
        int index;

        synchronized (publishers.lock) {
            PublisherRegistry.Session session = getOrCreateSessionLocked(packageName);
            boolean titleUsable = session.mediaFacts.isTitleUsable();
            String sessionTitle = titleUsable ? session.mediaFacts.title() : null;
            long sessionDuration = session.mediaFacts.durationMs();
            if (!PublishGuard.matches(cleanLyric.getTitle(), cleanLyric.getDuration(),
                sessionTitle, sessionDuration)) {
                XposedLog.logW(TAG, "publish rejected: track mismatch for " + packageName
                    + " (frame title=\"" + cleanLyric.getTitle() + "\" duration="
                    + cleanLyric.getDuration() + "ms, session title=\"" + sessionTitle
                    + "\" duration=" + sessionDuration + "ms)");
                return RESULT_REJECTED_TRACK_MISMATCH;
            }

            cleanLyric = LyricDocAdapter.INSTANCE.withDuration(
                cleanLyric, session.mediaFacts.durationMs());
            position = Math.max(0L, playback.tracker.positionNow());
            index = cleanLyric.indexAt(position);
            PlaybackState state = session.playbackState;
            State snapshot = new State(cleanLyric.getId(), index, position,
                playback.tracker.currentSpeed(),
                PlaybackStates.toV4(state != null ? state.getState() : PlaybackState.STATE_NONE));
            fullFrame = encode(Frame.full(cleanLyric, snapshot, packageName));
            if (fullFrame == null) {
                XposedLog.logW(TAG, "publish rejected: encoded frame exceeds "
                    + Frame.MAX_SIZE + " bytes for " + packageName);
                return RESULT_PAYLOAD_TOO_LARGE;
            }

            session.publishingPid = pid;
            session.published = true;
            playback.evidence(packageName).record();
            session.lyric = cleanLyric;
            session.fullLyric = true;
            session.positionMs = position;
            shouldBroadcast = claimActiveLocked(packageName, session);
            if (shouldBroadcast) {
                playback.tracker.setLyrics(cleanLyric);
                if (state != null && state.getState() != PlaybackState.STATE_NONE) {
                    playback.tracker.onPlaybackStateChanged(state);
                }
                syncPlayState = playback.listener != null;
            }
        }

        if (!shouldBroadcast) {
            XposedLog.logD(TAG, "publish cached in session, suppressed by active playing "
                + publishers.activePublisher + ": " + packageName);
            return RESULT_OK;
        }

        XposedLog.logD(TAG, "publish [FULL, lines=" + cleanLyric.getLineCount() + "] from "
            + packageName + " (pid " + pid + "): trackId=" + cleanLyric.getId()
            + ", duration=" + cleanLyric.getDuration() + "ms, frame=" + fullFrame.length + " bytes");

        mLastStopFrame = null;
        notifyLegacyLyric(packageName, cleanLyric, index, position, true);
        notifyFrames(packageName, fullFrame);

        if (syncPlayState && playback.listener != null) {
            playback.listener.syncCurrentStateForPackage(packageName);
        }
        return RESULT_OK;
    }

    void clearFrame(int pid, @NonNull String packageName) {
        if (packageName.isEmpty() || !isPublisherProcess(packageName, pid)) return;

        cancelPendingStop(packageName);

        StopState stop;
        synchronized (publishers.lock) {
            stop = clearSessionLocked(packageName, pid, StopReason.CLEARED);
        }

        if (stop.wasActive()) {
            XposedLog.logI(TAG, "Cleared lyric for " + packageName + " (pid " + pid + ")");
            if (stop.stopFrame() != null) mLastStopFrame = stop.stopFrame();
            SuperLyricData empty = new SuperLyricData();
            notifyReceiver(packageName, "onStop", capability -> empty,
                (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
            notifyFrames(packageName, stop.stopFrame());
        }
    }

    @Nullable
    byte[] latestFrame() {
        synchronized (publishers.lock) {
            if (publishers.activePublisher != null) return latestFullFrameLocked();
            return mLastStopFrame;
        }
    }

    @Nullable
    private byte[] latestFullFrameLocked() {
        String active = publishers.activePublisher;
        if (active == null) return null;
        PublisherRegistry.Session session = publishers.sessions.get(active);
        if (session == null || session.lyric == null || !isPublisher(active)) return null;

        int index = session.fullLyric
            ? playback.tracker.indexNow() : 0;
        long position = session.fullLyric
            ? Math.max(0L, playback.tracker.positionNow()) : session.positionMs;
        State snapshot = buildStateLocked(session, index, position);
        return encode(Frame.full(session.lyric, snapshot, active));
    }

    int registerFrameListener(@NonNull ILyricFrameListener listener, int uid, int pid,
                              @NonNull String packageName) {
        int result = mFrames.register(listener, uid, pid, packageName);
        if (result != FrameBroadcaster.REGISTER_OK) return result;

        mFrameExecutor.execute(() -> {
            byte[] initial;
            synchronized (publishers.lock) {
                initial = latestFullFrameLocked();
            }
            if (initial != null) mFrames.deliver(listener, initial);
        });
        return FrameBroadcaster.REGISTER_OK;
    }

    void unregisterFrameListener(@NonNull ILyricFrameListener listener, int pid,
                                 @NonNull String packageName) {
        mFrames.unregister(listener, pid, packageName);
    }

    void setSystemPlayStateListenerEnabled(int pid, @NonNull String packageName, boolean enabled) {
        if (isPublisherProcess(packageName, pid)) {
            if (enabled) {
                PublisherRegistry.disabledPackages.remove(packageName);
            } else {
                PublisherRegistry.disabledPackages.add(packageName);
            }
            XposedLog.logI(TAG, "System play state listener enabled: " + enabled + ", caller: " + packageName);
        }
    }

    void registerReceiver(ISuperLyricReceiver receiver, int uid, int pid,
                          @NonNull String packageName) {
        mReceivers.register(receiver, uid, pid, packageName,
            AccessPolicy.supportsFullLegacyLyric(packageName, uid));
    }

    void unregisterReceiver(ISuperLyricReceiver receiver, int pid,
                            @NonNull String packageName) {
        mReceivers.unregister(receiver, pid, packageName);
    }

    boolean isReceiverRegistered(ISuperLyricReceiver receiver) {
        return receiver != null && mReceivers.isRegistered(receiver);
    }

    @Nullable
    SuperLyricData latestLegacy(int pid, @NonNull String packageName, int uid) {
        boolean supportsFull = AccessPolicy.supportsFullLegacyLyric(packageName, uid);

        synchronized (publishers.lock) {
            String active = publishers.activePublisher;
            if (active == null) return null;
            PublisherRegistry.Session session = publishers.sessions.get(active);
            if (session == null || session.lyric == null) return null;

            Lyric lyric = session.lyric;
            int index = session.fullLyric
                ? legacyIndex(lyric, playback.tracker.indexNow()) : 0;
            long position = session.fullLyric
                ? Math.max(0L, playback.tracker.positionNow()) : session.positionMs;

            if (index < 0 || (session.fullLyric && supportsFull)) {
                return LyricDocAdapter.INSTANCE.toLegacyFull(lyric, index, position);
            }
            return LyricDocAdapter.INSTANCE.toLegacyLine(lyric, index, position);
        }
    }

    public void onProcessDied(@NonNull String packageName, int pid) {
        PublisherRegistry.DeathResult result = publishers.onProcessDied(packageName, pid, playback);
        if (result == null) return;

        if (!result.lastPid && !result.wasActive) {
            XposedLog.logD(TAG, "Non-publishing process died for " + packageName
                + ", pid=" + pid + "; " + PublisherRegistry.processCount(packageName)
                + " publisher process(es) remain");
            return;
        }

        XposedLog.logI(TAG, "Publisher process died: " + packageName + ", pid: " + pid
            + " (lastPid=" + result.lastPid + ", wasActive=" + result.wasActive + ")");
        if (!result.wasActive) return;

        mDeathNotifier.execute(() -> {
            boolean superseded = result.lastPid
                ? isPublisher(packageName) : publishers.isStillPublishing(packageName);
            if (superseded) return;
            broadcastDeath("onProcessDied", packageName, StopReason.PROCESS_DIED);
        });
    }

    private void cancelPendingStop(@NonNull String packageName) {
        if (playback.cancelPending(packageName)) {
            XposedLog.logD(TAG, "Pending stop cancelled by a newer state or metadata: " + packageName);
        }
    }

    public void onSystemSessionDestroyed(@NonNull String packageName) {
        playback.onSessionDestroyed(packageName);
    }

    public void onSystemPlaybackStateChanged(@NonNull String packageName, @NonNull PlaybackState state) {
        playback.onPlaybackStateChanged(packageName, state);
        broadcastStateFrame(packageName);
    }

    public void onSystemMetadataChanged(@NonNull String packageName, @Nullable MediaMetadata metadata) {
        playback.onMetadataChanged(packageName, metadata);
    }

    /**
     * 活跃发布者的完整快照；由系统播放状态切换触发。
     */
    private void notifyLyric(@NonNull String packageName, @NonNull Lyric lyric) {
        int index;
        long position;
        boolean fullSource;
        byte[] frame;

        synchronized (publishers.lock) {
            PublisherRegistry.Session session = publishers.sessions.get(packageName);
            if (session == null || session.lyric == null || !isPublisher(packageName)) return;

            fullSource = session.fullLyric;
            index = fullSource ? playback.tracker.indexNow() : 0;
            position = fullSource
                ? Math.max(0L, playback.tracker.positionNow()) : session.positionMs;
            frame = encode(Frame.full(lyric, buildStateLocked(session, index, position), packageName));
        }

        mLastStopFrame = null;
        notifyLegacyLyric(packageName, lyric, index, position, fullSource);
        notifyFrames(packageName, frame);
    }

    private void notifyStop(@NonNull String packageName, @NonNull SuperLyricData data,
                            @NonNull StopReason reason) {
        byte[] frame;
        synchronized (publishers.lock) {
            frame = encode(Frame.stop(new Stop(lyricIdLocked(packageName), reason, packageName)));
            if (frame != null) mLastStopFrame = frame;
        }

        notifyReceiver(packageName, "onStop", capability -> data,
            (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
        notifyFrames(packageName, frame);
    }

    private void notifyLegacyLyric(@NonNull String packageName, @NonNull Lyric lyric,
                                   int index, long position, boolean fullSource) {
        int legacyIndex = legacyIndex(lyric, index);
        XposedLog.logD(TAG, "Legacy " + (legacyIndex < 0 ? "metadata-only" : "lyric")
            + " from " + packageName + ": index=" + legacyIndex + ", position=" + position + "ms");
        notifyReceiver(packageName, "onLyric",
            supportsFullLyric -> legacyPayload(lyric, legacyIndex, position, fullSource,
                supportsFullLyric),
            (receiver, publisher, payload) -> receiver.onLyric(publisher, payload));
    }

    @Nullable
    static SuperLyricData legacyPayload(@NonNull Lyric lyric, int legacyIndex, long position,
                                        boolean fullSource, boolean supportsFullLyric) {
        if (fullSource && (supportsFullLyric || legacyIndex < 0)) {
            return LyricDocAdapter.INSTANCE.toLegacyFull(lyric, legacyIndex, position);
        }
        return legacyIndex < 0
            ? null : LyricDocAdapter.INSTANCE.toLegacyLine(lyric, legacyIndex, position);
    }

    private void notifyReceiver(@NonNull String publisher, @NonNull String action,
                                @NonNull LegacyPayloadProvider payloads,
                                @NonNull IReceiverCallback callback) {
        if (!isPublisher(publisher)) return;
        long generation = PublisherRegistry.generations.getOrDefault(publisher, 0L);
        mBroadcastExecutor.execute(() -> {
            if (!isPublisher(publisher)
                || PublisherRegistry.generations.getOrDefault(publisher, 0L) != generation) {
                return;
            }
            CapabilityPayloads cache = new CapabilityPayloads(payloads);
            mReceivers.broadcast(action, (receiver, supportsFullLyric) -> {
                SuperLyricData payload = cache.get(supportsFullLyric);
                if (payload != null) callback.call(receiver, publisher, payload);
            });
        });
    }

    /** 按接收者能力提供 3.x 载荷；没有接收者时不会调用。 */
    @FunctionalInterface
    interface LegacyPayloadProvider {
        @Nullable
        SuperLyricData payloadFor(boolean supportsFullLyric);
    }

    /**
     * 同一次广播内每种能力至多构造一次载荷：全量映射按行、按字分配，只在确有需要时发生。
     */
    static final class CapabilityPayloads {
        private final LegacyPayloadProvider provider;
        private final SuperLyricData[] payloads = new SuperLyricData[2];
        private final boolean[] resolved = new boolean[2];

        CapabilityPayloads(@NonNull LegacyPayloadProvider provider) {
            this.provider = provider;
        }

        @Nullable
        SuperLyricData get(boolean supportsFullLyric) {
            int slot = supportsFullLyric ? 1 : 0;
            if (!resolved[slot]) {
                resolved[slot] = true;
                payloads[slot] = provider.payloadFor(supportsFullLyric);
            }
            return payloads[slot];
        }
    }

    private void notifyFrames(@NonNull String publisher, @Nullable byte[] frame) {
        if (frame == null || !isPublisher(publisher)) return;
        long generation = PublisherRegistry.generations.getOrDefault(publisher, 0L);
        mFrameExecutor.execute(() -> {
            if (!isPublisher(publisher)
                || PublisherRegistry.generations.getOrDefault(publisher, 0L) != generation) {
                return;
            }
            mFrames.broadcast(frame);
        });
    }

    private void broadcastStateFrame(@NonNull String packageName) {
        byte[] frame;
        synchronized (publishers.lock) {
            if (!TextUtils.equals(publishers.activePublisher, packageName)) return;
            PublisherRegistry.Session session = publishers.sessions.get(packageName);
            if (session == null || session.lyric == null) return;
            PlaybackState state = session.playbackState;
            if (state == null || PlaybackStates.toV4(state.getState()) == null) return;
            int index = session.fullLyric ? playback.tracker.indexNow() : 0;
            long position = session.fullLyric
                ? Math.max(0L, playback.tracker.positionNow()) : session.positionMs;
            frame = encode(Frame.state(buildStateLocked(session, index, position), packageName));
        }
        notifyFrames(packageName, frame);
        mLastStopFrame = null;
    }

    @Nullable
    private byte[] fullFrameLocked(@Nullable PublisherRegistry.Session session,
                                   int index, long position) {
        if (session == null || session.lyric == null) return null;
        return encode(Frame.full(session.lyric, buildStateLocked(session, index, position),
            session.packageName));
    }

    @NonNull
    private State buildStateLocked(@NonNull PublisherRegistry.Session session, int index,
                                   long position) {
        PlaybackState state = session.playbackState;
        float speed = session.fullLyric
            ? playback.tracker.currentSpeed()
            : (state != null ? state.getPlaybackSpeed() : 1f);
        return new State(session.lyric != null ? session.lyric.getId() : null,
            index, Math.max(0L, position), speed,
            PlaybackStates.toV4(state != null ? state.getState() : PlaybackState.STATE_NONE));
    }

    @Nullable
    private String lyricIdLocked(@NonNull String packageName) {
        PublisherRegistry.Session session = publishers.sessions.get(packageName);
        if (session == null || session.lyric == null) return null;
        return session.lyric.getId();
    }

    @Nullable
    private static byte[] encode(@Nullable Frame frame) {
        if (frame == null) return null;
        byte[] bytes;
        try {
            bytes = frame.encode();
        } catch (Throwable t) {
            XposedLog.logE(TAG, "Failed to encode frame", t);
            return null;
        }
        if (bytes.length > Frame.MAX_SIZE) {
            XposedLog.logW(TAG, "Dropping frame: " + bytes.length + " bytes exceeds "
                + Frame.MAX_SIZE);
            return null;
        }
        return bytes;
    }

    /**
     * 判定并取得 active 权
     *
     * <p>只有成功后才允许调用方写共享播放时钟；被抑制的发布必须让 tracker 保持 active
     * 发布者的歌词、位置与状态，否则当前播放应用的逐行推进会被晚到的发布覆盖。
     */
    private boolean claimActiveLocked(@NonNull String packageName,
                                      @NonNull PublisherRegistry.Session session) {
        if (publishers.activePublisher == null) {
            publishers.activePublisher = packageName;
            return true;
        }
        if (TextUtils.equals(publishers.activePublisher, packageName)) return true;

        PublisherRegistry.Session activeSession = publishers.sessions.get(publishers.activePublisher);
        boolean activePlaying = activeSession != null && activeSession.isPlaying();
        boolean currentPlaying = session.isPlaying();

        if (!activePlaying || currentPlaying) {
            XposedLog.logI(TAG, "Publisher active switched from " + publishers.activePublisher
                + " to " + packageName
                + " (activePlaying=" + activePlaying + ", currentPlaying=" + currentPlaying + ")");
            publishers.activePublisher = packageName;
            return true;
        }
        XposedLog.logD(TAG, "Publisher " + packageName
            + " sent lyric cached in session, suppressed by active playing "
            + publishers.activePublisher);
        return false;
    }

    private void removePublisherProcess(@NonNull String packageName, int pid) {
        PublisherRegistry.DeathResult result = publishers.onProcessDied(packageName, pid, playback);
        if (result == null || !result.wasActive) return;

        mDeathNotifier.execute(() -> {
            if (isPublisher(packageName)) return;
            broadcastDeath("removePublisherProcess", packageName, StopReason.CLEARED);
        });
    }

    private PublisherRegistry.Session getOrCreateSessionLocked(@NonNull String packageName) {
        PublisherRegistry.Session session = publishers.getOrCreateSession(packageName);
        session.touch();
        return session;
    }

    /**
     * 清理指定包的会话；调用方必须持有 publishers.lock。
     */
    @NonNull
    private StopState clearSessionLocked(@NonNull String packageName, int pid,
                                         @NonNull StopReason reason) {
        byte[] stopFrame = null;
        PublisherRegistry.Session session = publishers.sessions.get(packageName);
        if (session != null) {
            session.publishingPid = pid;
            session.published = true;
            playback.evidence(packageName).record();
            stopFrame = encode(Frame.stop(new Stop(lyricIdLocked(packageName), reason, packageName)));
            session.clear();
        }

        boolean wasActive = false;
        if (TextUtils.equals(publishers.activePublisher, packageName)) {
            publishers.activePublisher = null;
            playback.tracker.stop();
            playback.tracker.setLyrics(null);
            wasActive = true;
        }
        return new StopState(wasActive, stopFrame);
    }

    private void broadcastDeath(@NonNull String action, @NonNull String packageName,
                                @NonNull StopReason reason) {
        SuperLyricData empty = new SuperLyricData();
        // 与 notifyReceiver 共用单线程：RemoteCallbackList 同时只允许一次广播，
        // 从死亡通知线程直接广播会与歌词广播交错并抛 IllegalStateException。
        mBroadcastExecutor.execute(() -> mReceivers.broadcast(action,
            (receiver, supportsFullLyric) -> receiver.onStop(packageName, empty)));
        byte[] stopFrame = encode(Frame.stop(new Stop(null, reason, packageName)));
        if (stopFrame != null) {
            mLastStopFrame = stopFrame;
            mFrameExecutor.execute(() -> mFrames.broadcast(stopFrame));
        }
    }

    private record StopState(boolean wasActive, @Nullable byte[] stopFrame) {
    }

    private static int legacyIndex(@NonNull Lyric lyric, int index) {
        int count = lyric.getLineCount();
        if (count == 0) return -1;
        return Math.max(-1, Math.min(index, count - 1));
    }

    /**
     * 单线程有界执行器
     *
     * <p>队列满时在调用线程按序阻塞入队，最多等 2 秒；仍满则丢弃并计数告警。拒绝策略不得
     * 在调用线程执行广播：{@code RemoteCallbackList} 同时只允许一次广播，帧也必须由唯一的
     * 广播线程按入队顺序投递。丢弃由 {@code latest()} 与下一次状态帧补偿。
     */
    private static ExecutorService boundedSingleThread(@NonNull String name, int capacity,
                                                       boolean guardRunnable) {
        AtomicLong dropped = new AtomicLong();
        return new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
            daemonFactory(name, guardRunnable),
            (task, executor) -> {
                if (executor.isShutdown()) return;
                try {
                    if (executor.getQueue().offer(task, QUEUE_OFFER_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                XposedLog.logW(TAG, name + " queue full after " + QUEUE_OFFER_TIMEOUT_MS
                    + "ms; dropped task (" + dropped.incrementAndGet() + " dropped total)");
            });
    }

    private static ThreadFactory daemonFactory(@NonNull String name, boolean guardRunnable) {
        return runnable -> {
            Runnable action = guardRunnable ? () -> {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    XposedLog.logE(TAG, "Uncaught error in " + name + " worker thread", t);
                }
            } : runnable;
            Thread thread = new Thread(action, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) ->
                XposedLog.logE(TAG, "Uncaught exception on thread: " + t.getName(), e));
            return thread;
        };
    }

    String packageNameForPid(int pid) {
        try {
            Object pidMap = CoreTool.getField(mAms, "mPidsSelfLocked");
            if (pidMap != null) {
                Object record;
                synchronized (pidMap) {
                    record = CoreTool.callMethod(pidMap, "get", new Class<?>[]{int.class}, pid);
                }
                if (record != null) {
                    ApplicationInfo info = (ApplicationInfo) CoreTool.getField(record, "info");
                    if (info != null) return info.packageName;
                }
            }
        } catch (Throwable t) {
            // 反射失败只丢弃本次调用；异常穿过 onTransact 会让客户端进程收到 RuntimeException。
            XposedLog.logW(TAG, "Failed to resolve package for pid " + pid, t);
            return "";
        }
        XposedLog.logW(TAG, "Cannot resolve package for pid " + pid
            + "; AMS#mPidsSelfLocked may have moved. Dropping this call.");
        return "";
    }

    @FunctionalInterface
    private interface IReceiverCallback {
        void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException;
    }
}
