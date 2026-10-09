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
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.publisher.PlaybackTracker;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.ISuperLyricManager;
import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Super Lyric 核心系统服务。
 * <p>
 * 运行于 {@code system_server} 进程，主要职责：
 * <ol>
 *   <li>维护发布者（Publisher）生命周期与进程死亡感知；</li>
 *   <li>隔离管理各音乐应用的歌词会话（{@link PublisherSession}），防止多应用并发数据覆盖；</li>
 *   <li>监听全局系统媒体播放状态，基于播放焦点自治驱动中央歌词时钟（{@code mSystemTracker}）；</li>
 *   <li>注册并向所有接收者（Receiver）安全广播歌词数据；</li>
 *   <li>严格限制会话生命周期与内存占用，确保 {@code system_server} 零泄露与高稳定性。</li>
 * </ol>
 *
 * @author 焕晨HChen
 */
public final class SuperLyricService extends ISuperLyricManager.Stub {
    private static final String TAG = "SuperLyricService";
    private static final int MAX_CACHED_SESSIONS = 5;

    private final Object mAms;
    private final Set<IBinder> mReceiverBinders = ConcurrentHashMap.newKeySet();

    private final RemoteCallbackList<ISuperLyricReceiver> mCallbacks = new RemoteCallbackList<>() {
        @Override
        public void onCallbackDied(ISuperLyricReceiver callbackInterface) {
            super.onCallbackDied(callbackInterface);
            mReceiverBinders.remove(callbackInterface.asBinder());
            XposedLog.logI(TAG, "Receiver died: " + callbackInterface + ", binder: " + callbackInterface.asBinder());
        }
    };

    private final ExecutorService mBroadcastExecutor = Executors.newSingleThreadExecutor(
        new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(() -> {
                    try {
                        r.run();
                    } catch (Throwable t1) {
                        XposedLog.logE(TAG, "Uncaught error in SuperLyric-Broadcaster worker thread", t1);
                    }
                }, "SuperLyric-Broadcaster");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) -> {
                    XposedLog.logE(TAG, "Uncaught exception on thread: " + thread.getName(), ex);
                });
                return t;
            }
        }
    );

    private static final ConcurrentHashMap<String, Set<Integer>> sPublisherPids = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> sPublisherGenerations = new ConcurrentHashMap<>();
    private static final Set<String> sSystemPlayStateListenerDisabledPackages = ConcurrentHashMap.newKeySet();

    // 多应用会话隔离存储：按包名维护独立会话
    private final ConcurrentHashMap<String, PublisherSession> mSessions = new ConcurrentHashMap<>();
    private final Object mSnapshotLock = new Object();
    @Nullable
    private volatile String mActivePublisher;

    // 系统级播放进度追踪器：自治插值计算与跨行调度
    private final PlaybackTracker mSystemTracker;
    @Nullable
    private volatile SystemPlayStateListener mSystemPlayStateListener;

    public SuperLyricService(@NonNull Object ams) {
        this.mAms = ams;
        this.mSystemTracker = new PlaybackTracker("SuperLyric-SysTracker");
        this.mSystemTracker.setOnProgressAdvanceListener((newIndex, progress) -> {
            String activePkg = mActivePublisher;
            if (activePkg == null || !isPublisher(activePkg)) {
                return;
            }

            SuperLyricData cleanProgress = LyricSanitizer.sanitizeData(progress);
            if (cleanProgress == null) return;

            synchronized (mSnapshotLock) {
                PublisherSession session = mSessions.get(activePkg);
                if (session != null && session.latestLyric != null) {
                    SuperLyricData base = session.latestLyric;
                    SuperLyricData merged = new SuperLyricData()
                        .setTitle(base.getTitle())
                        .setArtist(base.getArtist())
                        .setAlbum(base.getAlbum())
                        .setLyricId(base.getLyricId())
                        .setDuration(base.getDuration())
                        .setAllLyrics(base.getAllLyrics())
                        .setCurrentLyricIndex(newIndex)
                        .setPosition(cleanProgress.getPosition())
                        .setLyric(cleanProgress.getCurrentLyric())
                        .setExtra(base.getExtra());
                    if (cleanProgress.hasTranslation()) {
                        merged.setTranslation(cleanProgress.getTranslation());
                    }
                    if (cleanProgress.hasSecondary()) {
                        merged.setSecondary(cleanProgress.getSecondary());
                    }
                    session.latestLyric = merged;
                    session.touch();
                }
            }

            XposedLog.logD(TAG, "[SysTracker] Advance line [" + newIndex + "] for " + activePkg + ": "
                + "pos=" + cleanProgress.getPosition() + "ms, "
                + "text=\"" + (cleanProgress.getCurrentLyric() != null ? cleanProgress.getCurrentLyric().getText() : "null") + "\""
                + (cleanProgress.hasTranslation() && cleanProgress.getTranslation() != null ? (", trans=\"" + cleanProgress.getTranslation().getText() + "\"") : "")
                + (cleanProgress.hasSecondary() && cleanProgress.getSecondary() != null ? (", sec=\"" + cleanProgress.getSecondary().getText() + "\"") : ""));

            notifyReceiver(activePkg, cleanProgress, (receiver, publisher, payload) -> receiver.onLyric(publisher, payload));
        });
    }

    public void setSystemPlayStateListener(@Nullable SystemPlayStateListener listener) {
        this.mSystemPlayStateListener = listener;
    }

    public static boolean isPublisher(@NonNull String packageName) {
        Set<Integer> pids = sPublisherPids.get(packageName);
        return pids != null && !pids.isEmpty();
    }

    private static boolean isPublisherProcess(@NonNull String packageName, int pid) {
        Set<Integer> pids = sPublisherPids.get(packageName);
        return pids != null && pids.contains(pid);
    }

    public static boolean isSystemPlayStateListenerDisabled(@NonNull String packageName) {
        return sSystemPlayStateListenerDisabledPackages.contains(packageName);
    }

    @Override
    public void registerPublisher() throws RemoteException {
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();

        // 仅允许第三方应用注册 publisher，拒绝非应用进程，防止伪造发布者广播垃圾数据
        if (uid < android.os.Process.FIRST_APPLICATION_UID) {
            XposedLog.logW(TAG, "Registration as publisher rejected! Non-app caller, uid: " + uid + ", pid: " + pid);
            return;
        }

        String packageName = getPackageNameWithPid(pid);
        if (!packageName.isEmpty()) {
            Set<Integer> pids = sPublisherPids.computeIfAbsent(packageName, ignored -> ConcurrentHashMap.newKeySet());
            if (pids.add(pid)) {
                sPublisherGenerations.merge(packageName, 1L, Long::sum);
            }
            XposedLog.logI(TAG, "Register publisher: " + packageName + ", pid: " + pid);
            if (mSystemPlayStateListener != null) {
                mSystemPlayStateListener.syncCurrentStateForPackage(packageName);
            }
        } else {
            XposedLog.logW(TAG, "Registration as publisher failed! Unable to obtain package name for pid: " + pid);
        }
    }

    @Override
    public void unregisterPublisher() throws RemoteException {
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (!packageName.isEmpty()) {
            removePublisherProcess(packageName, pid);
            XposedLog.logI(TAG, "Unregister publisher: " + packageName + ", pid: " + pid);
        } else {
            XposedLog.logW(TAG, "Failed to unregister as publisher for pid: " + pid);
        }
    }

    @Override
    public boolean isPublisherRegistered() throws RemoteException {
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        Set<Integer> pids = sPublisherPids.get(packageName);
        return pids != null && pids.contains(pid);
    }

    @Override
    public void sendLyric(SuperLyricData data) throws RemoteException {
        if (data == null) return;

        data = LyricSanitizer.sanitizeData(data);
        if (data == null) return;

        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (!isPublisherProcess(packageName, pid)) {
            return;
        }

        boolean isIncremental = !data.hasAllLyrics();
        boolean isDuplicateLine = false;
        boolean shouldBroadcast = false;

        synchronized (mSnapshotLock) {
            PublisherSession session = getOrCreateSessionLocked(packageName);

            if (data.hasAllLyrics()) {
                session.latestLyric = data;
            } else if (session.latestLyric != null) {
                boolean isSameTrack = !data.hasLyricId() || !session.latestLyric.hasLyricId()
                    || TextUtils.equals(data.getLyricId(), session.latestLyric.getLyricId());
                if (isSameTrack) {
                    if (data.getCurrentLyricIndex() == session.latestLyric.getCurrentLyricIndex()
                        && data.hasLyric() && session.latestLyric.hasLyric()
                        && TextUtils.equals(data.getCurrentLyric().getText(), session.latestLyric.getCurrentLyric().getText())) {
                        isDuplicateLine = true;
                    }

                    SuperLyricData merged = new SuperLyricData()
                        .setTitle(data.hasTitle() ? data.getTitle() : session.latestLyric.getTitle())
                        .setArtist(data.hasArtist() ? data.getArtist() : session.latestLyric.getArtist())
                        .setAlbum(data.hasAlbum() ? data.getAlbum() : session.latestLyric.getAlbum())
                        .setLyricId(data.hasLyricId() ? data.getLyricId() : session.latestLyric.getLyricId())
                        .setDuration(data.hasDuration() ? data.getDuration() : session.latestLyric.getDuration())
                        .setAllLyrics(session.latestLyric.getAllLyrics())
                        .setCurrentLyricIndex(data.getCurrentLyricIndex())
                        .setPosition(data.getPosition())
                        .setLyric(data.getCurrentLyric() != null ? data.getCurrentLyric() : session.latestLyric.getCurrentLyric())
                        .setExtra(data.hasExtra() ? data.getExtra() : session.latestLyric.getExtra());
                    if (data.hasTranslation()) {
                        merged.setTranslation(data.getTranslation());
                    } else if (session.latestLyric.hasTranslation()) {
                        merged.setTranslation(session.latestLyric.getTranslation());
                    }
                    if (data.hasSecondary()) {
                        merged.setSecondary(data.getSecondary());
                    } else if (session.latestLyric.hasSecondary()) {
                        merged.setSecondary(session.latestLyric.getSecondary());
                    }
                    session.latestLyric = merged;
                } else {
                    XposedLog.logW(TAG, "Ignoring stale incremental lyric for " + packageName
                        + ": packet trackId=" + data.getLyricId()
                        + ", session trackId=" + session.latestLyric.getLyricId());
                    return;
                }
            } else {
                XposedLog.logW(TAG, "Ignoring orphaned incremental lyric without full lyric for " + packageName
                    + ": packet trackId=" + data.getLyricId());
                return;
            }

            // 仲裁判定：当前上报应用是否有权成为全局活跃发布者
            if (mActivePublisher == null) {
                mActivePublisher = packageName;
                shouldBroadcast = true;
            } else if (TextUtils.equals(mActivePublisher, packageName)) {
                shouldBroadcast = true;
            } else {
                // 另一个应用处于活跃：检查活跃应用是否真实处于播放中
                PublisherSession activeSession = mSessions.get(mActivePublisher);
                boolean activePlaying = activeSession != null && activeSession.isPlaying();
                boolean currentPlaying = session.isPlaying();

                if (!activePlaying || currentPlaying) {
                    XposedLog.logI(TAG, "Publisher active switched from " + mActivePublisher + " to " + packageName
                        + " (activePlaying=" + activePlaying + ", currentPlaying=" + currentPlaying + ")");
                    mActivePublisher = packageName;
                    shouldBroadcast = true;
                } else {
                    XposedLog.logD(TAG, "Publisher " + packageName + " sent lyric cached in session, suppressed by active playing " + mActivePublisher);
                }
            }

            if (shouldBroadcast) {
                if (data.hasAllLyrics()) {
                    mSystemTracker.setLyrics(data);
                    if (data.hasPosition()) {
                        mSystemTracker.onPositionSynced(data.getPosition());
                    }
                    if (session.playbackState != null) {
                        mSystemTracker.onPlaybackStateChanged(session.playbackState);
                    }
                    if (mSystemPlayStateListener != null) {
                        mSystemPlayStateListener.syncCurrentStateForPackage(packageName);
                    }
                } else {
                    if (data.hasPosition()) {
                        mSystemTracker.onPositionSynced(data.getPosition());
                    }
                }
            }
        }

        if (!shouldBroadcast || (isIncremental && isDuplicateLine)) {
            return;
        }

        XposedLog.logD(TAG, "sendLyric [" + (data.hasAllLyrics() ? ("FULL, lines=" + data.getAllLyricsCount()) : "PROGRESS")
            + "] from " + packageName + " (pid " + pid + "): "
            + "trackId=" + data.getLyricId()
            + ", idx=" + data.getCurrentLyricIndex()
            + ", pos=" + data.getPosition() + "/" + data.getDuration() + "ms"
            + ", text=\"" + (data.getCurrentLyric() != null ? data.getCurrentLyric().getText() : "null") + "\""
            + (data.hasTranslation() && data.getTranslation() != null ? (", trans=\"" + data.getTranslation().getText() + "\"") : "")
            + (data.hasSecondary() && data.getSecondary() != null ? (", sec=\"" + data.getSecondary().getText() + "\"") : ""));

        SuperLyricData broadcastPayload = data;
        if (isIncremental) {
            synchronized (mSnapshotLock) {
                PublisherSession session = mSessions.get(packageName);
                if (session != null && session.latestLyric != null) {
                    SuperLyricData base = session.latestLyric;
                    if (!data.hasTitle() || !data.hasArtist() || !data.hasAlbum() || !data.hasLyricId() || !data.hasDuration() || data.getDuration() <= 0) {
                        SuperLyricData enriched = new SuperLyricData()
                            .setTitle(data.hasTitle() ? data.getTitle() : base.getTitle())
                            .setArtist(data.hasArtist() ? data.getArtist() : base.getArtist())
                            .setAlbum(data.hasAlbum() ? data.getAlbum() : base.getAlbum())
                            .setLyricId(data.hasLyricId() ? data.getLyricId() : base.getLyricId())
                            .setDuration(data.hasDuration() && data.getDuration() > 0 ? data.getDuration() : base.getDuration())
                            .setCurrentLyricIndex(data.getCurrentLyricIndex())
                            .setPosition(data.getPosition())
                            .setLyric(data.getCurrentLyric())
                            .setExtra(data.hasExtra() ? data.getExtra() : base.getExtra());
                        if (data.hasTranslation()) {
                            enriched.setTranslation(data.getTranslation());
                        } else if (base.hasTranslation()) {
                            enriched.setTranslation(base.getTranslation());
                        }
                        if (data.hasSecondary()) {
                            enriched.setSecondary(data.getSecondary());
                        } else if (base.hasSecondary()) {
                            enriched.setSecondary(base.getSecondary());
                        }
                        broadcastPayload = enriched;
                    }
                }
            }
        }

        notifyReceiver(packageName, broadcastPayload, (receiver, publisher, payload) -> receiver.onLyric(publisher, payload));
    }

    @Override
    public void sendStop(SuperLyricData data) throws RemoteException {
        if (data == null) return;

        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (!isPublisherProcess(packageName, pid)) {
            return;
        }

        XposedLog.logD(TAG, "sendStop from " + packageName + " (pid " + pid + ")");

        boolean wasActive = false;
        synchronized (mSnapshotLock) {
            PublisherSession session = mSessions.get(packageName);
            if (session != null) {
                session.clear();
            }
            if (TextUtils.equals(mActivePublisher, packageName)) {
                mActivePublisher = null;
                mSystemTracker.stop();
                mSystemTracker.setLyrics(null);
                wasActive = true;
            }
        }

        if (wasActive) {
            notifyReceiver(packageName, data, (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
        }
    }

    @Override
    public void setSystemPlayStateListenerEnabled(boolean enabled) throws RemoteException {
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (isPublisherProcess(packageName, pid)) {
            if (enabled) {
                sSystemPlayStateListenerDisabledPackages.remove(packageName);
            } else {
                sSystemPlayStateListenerDisabledPackages.add(packageName);
            }
            XposedLog.logI(TAG, "System play state listener enabled: " + enabled + ", caller: " + packageName);
        }
    }

    @Override
    public void registerReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver != null) {
            int pid = Binder.getCallingPid();
            String packageName = getPackageNameWithPid(pid);
            mCallbacks.register(receiver);
            mReceiverBinders.add(receiver.asBinder());
            XposedLog.logI(TAG, "Register receiver: " + receiver + ", pkg: " + packageName + ", pid: " + pid + ", binder: " + receiver.asBinder());
        }
    }

    @Override
    public void unregisterReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver != null) {
            int pid = Binder.getCallingPid();
            String packageName = getPackageNameWithPid(pid);
            mCallbacks.unregister(receiver);
            mReceiverBinders.remove(receiver.asBinder());
            XposedLog.logI(TAG, "Unregister receiver: " + receiver + ", pkg: " + packageName + ", pid: " + pid + ", binder: " + receiver.asBinder());
        }
    }

    @Override
    public boolean isReceiverRegistered(ISuperLyricReceiver receiver) throws RemoteException {
        return receiver != null && mReceiverBinders.contains(receiver.asBinder());
    }

    @Nullable
    @Override
    public SuperLyricData getLatestLyric() throws RemoteException {
        synchronized (mSnapshotLock) {
            String active = mActivePublisher;
            if (active != null) {
                PublisherSession session = mSessions.get(active);
                if (session != null) {
                    return session.latestLyric;
                }
            }
            return null;
        }
    }

    public void onProcessDied(@NonNull String packageName, int pid) {
        Set<Integer> pids = sPublisherPids.get(packageName);
        if (pids == null || !pids.remove(pid)) {
            return;
        }
        if (!pids.isEmpty()) {
            return;
        }
        sPublisherPids.remove(packageName, pids);
        sPublisherGenerations.remove(packageName);
        sSystemPlayStateListenerDisabledPackages.remove(packageName);
        XposedLog.logI(TAG, "Publisher process died: " + packageName + ", pid: " + pid);

        boolean wasActive = false;
        synchronized (mSnapshotLock) {
            PublisherSession session = mSessions.remove(packageName);
            if (session != null) {
                session.clear();
            }
            if (TextUtils.equals(mActivePublisher, packageName)) {
                mActivePublisher = null;
                mSystemTracker.stop();
                mSystemTracker.setLyrics(null);
                wasActive = true;
            }
        }

        if (wasActive) {
            long generation = sPublisherGenerations.getOrDefault(packageName, 0L);
            mBroadcastExecutor.execute(() -> {
                if (isPublisher(packageName) || sPublisherGenerations.getOrDefault(packageName, 0L) != generation) {
                    return;
                }

                broadcastToReceivers("onProcessDied", receiver -> receiver.onStop(packageName, new SuperLyricData()));
            });
        }
    }

    public void onSystemPlaybackStateChanged(@NonNull String packageName, @NonNull PlaybackState state) {
        if (!isPublisher(packageName)) {
            return;
        }

        XposedLog.logD(TAG, "onSystemPlaybackStateChanged: pkg=" + packageName
            + ", state=" + state.getState()
            + ", pos=" + state.getPosition() + "ms"
            + ", speed=" + state.getPlaybackSpeed());

        boolean notifyLyric = false;
        SuperLyricData lyricToNotify = null;
        boolean notifyStop = false;
        SuperLyricData stopToNotify = null;

        synchronized (mSnapshotLock) {
            PublisherSession session = getOrCreateSessionLocked(packageName);
            session.playbackState = state;

            switch (state.getState()) {
                case PlaybackState.STATE_PLAYING -> {
                    if (!TextUtils.equals(mActivePublisher, packageName)) {
                        String prevPublisher = mActivePublisher;
                        XposedLog.logI(TAG, "Active publisher switched to playing " + packageName + " (previous: " + prevPublisher + ")");
                        mActivePublisher = packageName;
                        mSystemTracker.stop();

                        if (session.latestLyric != null) {
                            mSystemTracker.setLyrics(session.latestLyric);
                            mSystemTracker.onPlaybackStateChanged(state);
                            SuperLyricData currentData = session.latestLyric;
                            if (currentData.hasLyric()) {
                                notifyLyric = true;
                                lyricToNotify = currentData;
                            }
                        } else {
                            mSystemTracker.setLyrics(null);
                            // 若之前存在其他发布者，停止上一发布者的歌词显示；绝不向正在起播的新应用发送 onStop
                            if (!TextUtils.isEmpty(prevPublisher)) {
                                notifyReceiver(prevPublisher, new SuperLyricData(), (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
                            }
                        }
                    } else {
                        mSystemTracker.onPlaybackStateChanged(state);
                    }
                }
                case PlaybackState.STATE_PAUSED -> {
                    if (TextUtils.equals(mActivePublisher, packageName)) {
                        mSystemTracker.onPlaybackStateChanged(state);
                        notifyStop = true;
                        stopToNotify = new SuperLyricData();
                    }
                }
                case PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE,
                     PlaybackState.STATE_ERROR -> {
                    if (TextUtils.equals(mActivePublisher, packageName)) {
                        mActivePublisher = null;
                        mSystemTracker.stop();
                        mSystemTracker.setLyrics(null);
                        notifyStop = true;
                        stopToNotify = new SuperLyricData();
                    }
                    session.clear();
                }
                default -> {
                    if (TextUtils.equals(mActivePublisher, packageName)) {
                        mSystemTracker.onPlaybackStateChanged(state);
                    }
                }
            }
        }

        if (notifyLyric && lyricToNotify != null) {
            notifyReceiver(packageName, lyricToNotify, (receiver, publisher, payload) -> receiver.onLyric(publisher, payload));
        } else if (notifyStop && stopToNotify != null) {
            notifyReceiver(packageName, stopToNotify, (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
        }
    }

    public void onSystemMetadataChanged(@NonNull String packageName, @Nullable MediaMetadata metadata) {
        if (!isPublisher(packageName) || metadata == null) {
            return;
        }

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        XposedLog.logD(TAG, "onSystemMetadataChanged: pkg=" + packageName + ", title=" + title + ", artist=" + artist + ", duration=" + duration);

        synchronized (mSnapshotLock) {
            PublisherSession session = mSessions.get(packageName);
            if (session == null) {
                return;
            }

            // 仅在当前会话已存在歌词但缺失时长等信息时做安全元数据增强，绝不依据易变的 title 判定切歌或清空歌词
            if (session.latestLyric != null) {
                if (session.latestLyric.getDuration() <= 0 && duration > 0) {
                    session.latestLyric.setDuration(duration);
                }
            }
        }
    }

    @FunctionalInterface
    private interface ReceiverInvocation {
        void invoke(@NonNull ISuperLyricReceiver receiver) throws Throwable;
    }

    private void broadcastToReceivers(@NonNull String action, @NonNull ReceiverInvocation invocation) {
        int itemCount = mCallbacks.beginBroadcast();
        List<ISuperLyricReceiver> deadReceivers = null;
        try {
            for (int i = 0; i < itemCount; i++) {
                ISuperLyricReceiver receiver = mCallbacks.getBroadcastItem(i);
                if (receiver == null) {
                    continue;
                }
                try {
                    invocation.invoke(receiver);
                } catch (Throwable t) {
                    boolean isDead = (t instanceof DeadObjectException)
                        || !receiver.asBinder().isBinderAlive()
                        || (t.getMessage() != null && t.getMessage().contains("remote process probably died"));
                    if (isDead) {
                        if (deadReceivers == null) {
                            deadReceivers = new ArrayList<>();
                        }
                        deadReceivers.add(receiver);
                        XposedLog.logD(TAG, "Receiver is dead or unreachable during " + action + ": " + receiver.asBinder());
                    } else {
                        XposedLog.logD(TAG, "Failed to deliver callback during " + action + ": " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            XposedLog.logE(TAG, "Exception during broadcast iteration (" + action + ")", t);
        } finally {
            try {
                mCallbacks.finishBroadcast();
            } catch (Throwable t) {
                XposedLog.logE(TAG, "Exception during finishBroadcast (" + action + ")", t);
            }
            if (deadReceivers != null) {
                for (ISuperLyricReceiver dead : deadReceivers) {
                    mCallbacks.unregister(dead);
                    mReceiverBinders.remove(dead.asBinder());
                    XposedLog.logI(TAG, "Unregistered dead receiver: " + dead.asBinder());
                }
            }
        }
    }

    private void notifyReceiver(String publisher, SuperLyricData data, IReceiverCallback callback) {
        if (isPublisher(publisher)) {
            long generation = sPublisherGenerations.getOrDefault(publisher, 0L);
            mBroadcastExecutor.execute(() -> {
                if (!isPublisher(publisher) || sPublisherGenerations.getOrDefault(publisher, 0L) != generation) {
                    return;
                }

                broadcastToReceivers("notifyReceiver", receiver -> callback.call(receiver, publisher, data));
            });
        }
    }

    private void removePublisherProcess(@NonNull String packageName, int pid) {
        Set<Integer> pids = sPublisherPids.get(packageName);
        if (pids == null) return;
        pids.remove(pid);
        if (pids.isEmpty()) {
            sPublisherPids.remove(packageName, pids);
            sPublisherGenerations.remove(packageName);
            sSystemPlayStateListenerDisabledPackages.remove(packageName);

            boolean wasActive = false;
            synchronized (mSnapshotLock) {
                PublisherSession session = mSessions.remove(packageName);
                if (session != null) {
                    session.clear();
                }
                if (TextUtils.equals(mActivePublisher, packageName)) {
                    mActivePublisher = null;
                    mSystemTracker.stop();
                    mSystemTracker.setLyrics(null);
                    wasActive = true;
                }
            }

            if (wasActive) {
                mBroadcastExecutor.execute(() -> {
                    broadcastToReceivers("removePublisherProcess", receiver -> receiver.onStop(packageName, new SuperLyricData()));
                });
            }
        }
    }

    @NonNull
    private PublisherSession getOrCreateSessionLocked(@NonNull String packageName) {
        PublisherSession session = mSessions.get(packageName);
        if (session == null) {
            trimSessionsLocked();
            session = new PublisherSession(packageName);
            mSessions.put(packageName, session);
        }
        session.touch();
        return session;
    }

    private void trimSessionsLocked() {
        if (mSessions.size() < MAX_CACHED_SESSIONS) return;
        String oldestPkg = null;
        long oldestTime = Long.MAX_VALUE;
        for (PublisherSession s : mSessions.values()) {
            if (TextUtils.equals(s.packageName, mActivePublisher) || s.isPlaying()) {
                continue;
            }
            if (s.lastActiveTime < oldestTime) {
                oldestTime = s.lastActiveTime;
                oldestPkg = s.packageName;
            }
        }
        if (oldestPkg != null) {
            PublisherSession evicted = mSessions.remove(oldestPkg);
            if (evicted != null) {
                evicted.clear();
                XposedLog.logD(TAG, "Evicted idle lyric session: " + oldestPkg);
            }
        }
    }

    private String getPackageNameWithPid(int pid) {
        Object pidMap = CoreTool.getField(mAms, "mPidsSelfLocked");
        if (pidMap != null) {
            Object record;
            synchronized (pidMap) {
                record = CoreTool.callMethod(pidMap, "get", new Class[]{int.class}, pid);
            }
            if (record != null) {
                ApplicationInfo info = (ApplicationInfo) CoreTool.getField(record, "info");
                if (info != null) {
                    return info.packageName;
                }
            }
        }
        return "";
    }

    @FunctionalInterface
    private interface IReceiverCallback {
        void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException;
    }

    /**
     * 独立应用歌词会话实体。
     * <p>
     * 隔离保存各应用的最新歌词数据、当前系统播放状态、活跃时间戳等，
     * 彻底解决多应用同时运行或非正常切换时的快照覆盖与竞争问题。
     */
    private static final class PublisherSession {
        @NonNull
        final String packageName;
        @Nullable
        volatile SuperLyricData latestLyric;
        @Nullable
        volatile PlaybackState playbackState;
        volatile long lastActiveTime;

        PublisherSession(@NonNull String packageName) {
            this.packageName = packageName;
            this.lastActiveTime = SystemClock.elapsedRealtime();
        }

        boolean isPlaying() {
            PlaybackState state = this.playbackState;
            return state != null && state.getState() == PlaybackState.STATE_PLAYING;
        }

        void touch() {
            this.lastActiveTime = SystemClock.elapsedRealtime();
        }

        void clear() {
            this.latestLyric = null;
            this.playbackState = null;
        }
    }
}
