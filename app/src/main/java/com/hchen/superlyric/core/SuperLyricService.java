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
import com.hchen.superlyric.kernel.orchestrate.PlaybackSignal;
import com.hchen.superlyric.kernel.orchestrate.StopEvidence;
import com.hchen.superlyric.kernel.orchestrate.StopGate;
import com.hchen.superlyric.publisher.PlaybackTracker;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyric.utils.PlaybackSignals;
import com.hchen.superlyricapi.ISuperLyricManager;
import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    /** "还没有任何进程发布过"的哨兵。Binder 的 pid 恒 &gt; 0 */
    private static final int NO_PID = 0;

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

    /**
     * 停止确认的计时器
     *
     * 必须与广播线程分开：[#mBroadcastExecutor] 会**同步**调用每个接收者的 Binder 接口，
     * 一个卡住的或垂死的接收者足以把那一条线程堵住数秒，确认计时不能跟着一起被拖住
     */
    private final ScheduledExecutorService mStopConfirmer = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SuperLyric-StopConfirm");
                t.setDaemon(true);
                return t;
            }
        }
    );

    /**
     * 发布者死亡 / 反注册时的停止通知专用线程
     * <p>
     * 不能复用 {@link #mBroadcastExecutor}：那条线程会**同步**等待每个接收者的 Binder 返回，
     * 一个卡住的消费者能把它堵住数秒，死亡通知便一直排在它后面发不出去，消费方会永远
     * 停在最后一句歌词上。两条线程并发广播是安全的（{@link RemoteCallbackList} 自身同步），
     * 而"先播出歌词、后播出停止"由世代守卫保证——死亡时世代已被移除，排队中的旧歌词
     * 到达时对不上世代，会被丢弃
     */
    private final ExecutorService mDeathNotifier = Executors.newSingleThreadExecutor(
        new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SuperLyric-DeathNotice");
                t.setDaemon(true);
                return t;
            }
        }
    );

    private static final ConcurrentHashMap<String, Set<Integer>> sPublisherPids = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> sPublisherGenerations = new ConcurrentHashMap<>();

    /**
     * 发布者世代的全局递增源
     * <p>
     * 世代必须**永不重复**：按包名从 1 累加的话，一个包死亡（世代被移除）后重新注册又会
     * 从 1 开始，"死亡前捕获的世代"与"重注册后的世代"可能相等，守卫就形同虚设
     */
    private static final AtomicLong sPublisherGenerationSequence = new AtomicLong();
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
            SuperLyricData cleanProgress = LyricSanitizer.sanitizeData(progress);
            if (cleanProgress == null) return;

            // 活跃发布者与目标会话必须**在同一把锁内**读取：
            // 否则可能把进度写进 A 的会话，却按 B 的包名广播出去。
            String activePkg;
            synchronized (mSnapshotLock) {
                activePkg = mActivePublisher;
                PublisherSession session = activePkg != null ? mSessions.get(activePkg) : null;
                if (session == null || session.latestLyric == null || !isPublisher(activePkg)) {
                    return;
                }

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
                    .setLyric(cleanProgress.getLyric())
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

            XposedLog.logD(TAG, "[SysTracker] Advance line [" + newIndex + "] for " + activePkg + ": "
                + "pos=" + cleanProgress.getPosition() + "ms, "
                + "text=\"" + (cleanProgress.getLyric() != null ? cleanProgress.getLyric().getText() : "null") + "\""
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
                sPublisherGenerations.put(packageName, sPublisherGenerationSequence.incrementAndGet());
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

        // 有歌词进来就说明播放仍在继续（切歌后取词成功）
        cancelPendingStop(packageName);

        boolean isIncremental = !data.hasAllLyrics();
        boolean isDuplicateLine = false;
        boolean shouldBroadcast = false;
        boolean syncPlayState = false;

        synchronized (mSnapshotLock) {
            PublisherSession session = getOrCreateSessionLocked(packageName);
            // 记下"正在发布的是这个进程"，供进程死亡时区分它是不是无关子进程
            session.publishingPid = pid;
            session.published = true;
            // 计数在锁内自增：它要与 confirmStop 的读取互斥，否则"窗口内有没有新证据"判不准
            stopEvidence(packageName).record();

            if (data.hasAllLyrics()) {
                session.latestLyric = data;
            } else if (session.latestLyric != null) {
                boolean isSameTrack = !data.hasLyricId() || !session.latestLyric.hasLyricId()
                    || TextUtils.equals(data.getLyricId(), session.latestLyric.getLyricId());
                if (isSameTrack) {
                    if (data.getCurrentLyricIndex() == session.latestLyric.getCurrentLyricIndex()
                        && data.hasLyric() && session.latestLyric.hasLyric()
                        && TextUtils.equals(data.getLyric().getText(), session.latestLyric.getLyric().getText())) {
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
                        .setLyric(data.getLyric() != null ? data.getLyric() : session.latestLyric.getLyric())
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
                    // 只在有**明确**状态时用它重新锚定时钟：`NONE` 是"还没有状态"，喂给
                    // tracker 会被映射成 STOPPED 并把时钟冻住，直到宿主下一次 PLAYING
                    if (session.playbackState != null
                        && session.playbackState.getState() != PlaybackState.STATE_NONE) {
                        mSystemTracker.onPlaybackStateChanged(session.playbackState);
                    }
                    if (mSystemPlayStateListener != null) {
                        // 只登记，真正的调用在锁外（见下方说明）
                        syncPlayState = true;
                    }
                } else {
                    if (data.hasPosition()) {
                        mSystemTracker.onPositionSynced(data.getPosition());
                    }
                }
            }
        }

        // 锁外调用：它会读 MediaController 的播放状态并可能注册回调——**是对第三方 App 的
        // Binder 调用**；更要紧的是它会回调进 onSystemPlaybackStateChanged 再取 mSnapshotLock，
        // 而 SystemPlayStateListener 会持 mCallbacks 走这条路，锁内调用即构成 AB-BA 死锁
        if (syncPlayState && mSystemPlayStateListener != null) {
            mSystemPlayStateListener.syncCurrentStateForPackage(packageName);
        }

        if (!shouldBroadcast || (isIncremental && isDuplicateLine)) {
            return;
        }

        XposedLog.logD(TAG, "sendLyric [" + (data.hasAllLyrics() ? ("FULL, lines=" + data.getAllLyricsCount()) : "PROGRESS")
            + "] from " + packageName + " (pid " + pid + "): "
            + "trackId=" + data.getLyricId()
            + ", idx=" + data.getCurrentLyricIndex()
            + ", pos=" + data.getPosition() + "/" + data.getDuration() + "ms"
            + ", text=\"" + (data.getLyric() != null ? data.getLyric().getText() : "null") + "\""
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
                            .setLyric(data.getLyric())
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

        // 发布者自己下发的停止是权威结论，作废一切待确认的停止
        cancelPendingStop(packageName);

        boolean wasActive = false;
        synchronized (mSnapshotLock) {
            PublisherSession session = mSessions.get(packageName);
            if (session != null) {
                session.publishingPid = pid;
                session.published = true;
                stopEvidence(packageName).record();
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

    /**
     * 本服务实现的 API 版本号，供客户端做版本协商
     */
    @Override
    public int getServerApiVersion() throws RemoteException {
        return com.hchen.superlyricapi.BuildConfig.API_VERSION;
    }

    public void onProcessDied(@NonNull String packageName, int pid) {
        Set<Integer> pids = sPublisherPids.get(packageName);
        if (pids == null || !pids.remove(pid)) {
            return;
        }

        boolean lastPid = pids.isEmpty();
        if (lastPid) {
            sPublisherPids.remove(packageName, pids);
            sPublisherGenerations.remove(packageName);
            sSystemPlayStateListenerDisabledPackages.remove(packageName);
        }

        boolean wasActive = false;
        synchronized (mSnapshotLock) {
            PublisherSession session = mSessions.get(packageName);
            // 死的这个是不是**本会话的驱动者**。一个宿主会有多个进程注册成发布者
            // （子进程也会走到 AbsPublisher#onPackageReady），但只有其中一个在真正发布。
            // 只看"包还有没有进程注册"不够：QQ 的播放进程本身就是 `:QQPlayerService` 子进程，
            // 它死了而主进程还活着时，会话与 mActivePublisher 会一直挂着，
            // 时钟继续在没人播放的曲目上往前爬
            //
            // 两半缺一不可：
            // - 发布过内容的会话只认发布它的那个 pid：别的进程死了不动它，否则一次无关的
            //   子进程死亡就会把正在播的显示清掉
            // - **从未发布过内容的会话任何人都可以回收**：它没有任何内容可丢，而留着它会
            //   钉住一个"播放中"的会话（trimSessionsLocked 不淘汰播放中的会话），
            //   于是会话槽位与 mActivePublisher 一起泄漏
            boolean wasPublishing = session != null
                && (session.publishingPid == pid || !session.published);

            if (!lastPid && !wasPublishing) {
                // 无关子进程：注册痕迹上面已经摘掉了，显示与会话一概不动
                XposedLog.logD(TAG, "Non-publishing process died for " + packageName
                    + ", pid=" + pid + "; " + pids.size() + " publisher process(es) remain");
                return;
            }

            if (session != null) {
                session.publishingPid = NO_PID;
                session.clear();
            }
            if (lastPid) {
                mSessions.remove(packageName);
            }
            if (TextUtils.equals(mActivePublisher, packageName)) {
                mActivePublisher = null;
                mSystemTracker.stop();
                mSystemTracker.setLyrics(null);
                wasActive = true;
            }
        }

        XposedLog.logI(TAG, "Publisher process died: " + packageName + ", pid=" + pid
            + " (lastPid=" + lastPid + ", wasActive=" + wasActive + ")");

        if (!wasActive) {
            return;
        }

        mDeathNotifier.execute(() -> {
            // 期间这个包又有人接管了就不发：消费方刚要清空，新歌词马上补上，
            // 再补一次停止只会让它闪一下。
            //
            // 两种情形必须分开判：
            // - 整个包都没进程了（lastPid）：只要它重新注册过就别发
            // - 只是发布进程死了、同包还留着子进程（!lastPid）：这时 isPublisher 恒为真，
            //   拿它当判据会把通知**永远**吞掉，只该看"现在还有没有人在发布"
            //
            // 也不能拿"移除前捕获的世代"与"移除后的取值"相比：移除后取到的恒为 0
            // （未注册），与捕获值必然不等，守卫会恒真、通知永远发不出去
            boolean superseded = lastPid ? isPublisher(packageName) : isStillPublishing(packageName);
            if (superseded) {
                return;
            }

            broadcastToReceivers("onProcessDied", receiver -> receiver.onStop(packageName, new SuperLyricData()));
        });
    }

    /**
     * 这个包当前是否有进程**正在发布**
     *
     * <p>与 {@link #isPublisher} 的区别：后者只回答"还有没有进程注册过"，
     * 而一个包可以有多个注册进程，其中只有一两个在真正发布
     */
    private boolean isStillPublishing(@NonNull String packageName) {
        synchronized (mSnapshotLock) {
            PublisherSession session = mSessions.get(packageName);
            return session != null && session.publishingPid != NO_PID;
        }
    }

    /**
     * 各发布者的停止判定
     *
     * 判定规则本身在内核的 {@link StopGate}，与宿主进程内的 Provider **共用同一套**——
     * 此前两侧各写一套，而 `STATE_NONE` 在两侧被映射成了相反的含义
     *
     * <p>实例**不回收**：只有注册成发布者的包才会走到这里（见
     * {@link #onSystemPlaybackStateChanged} 首行的 isPublisher 判定），数量被支持的宿主
     * 应用数量所限，而一个 StopGate 只有几个字段。回收反而会引入竞态：删掉后重建的新实例
     * 令牌从头开始，旧实例上仍在排期的任务会重新对上号
     */
    private final ConcurrentHashMap<String, StopGate> mStopGates = new ConcurrentHashMap<>();

    @NonNull
    private StopGate stopGate(@NonNull String packageName) {
        return mStopGates.computeIfAbsent(packageName, pkg -> new StopGate(
            (delayMs, task) -> mStopConfirmer.schedule(task, delayMs, TimeUnit.MILLISECONDS),
            () -> confirmStop(pkg),
            StopGate.DEFAULT_CONFIRM_WINDOW_MS));
    }

    /**
     * 把播放状态喂给停止判定，并按判定留痕
     *
     * 调用方须持有 {@link #mSnapshotLock}
     */
    private void applyStopSignalLocked(@NonNull String packageName, int state) {
        PlaybackSignal signal = PlaybackSignals.of(state);

        // 非终态即"播放仍在继续"的证据，且必须在判定**之前**记：判定为 `IGNORED` 的那一半
        // （令牌已被确认消费）正是要拦的情形——此时 StopGate 已经放行，只有这笔账拦得住。
        // `UNKNOWN` 不记（"不知道不是证据"），与服务端另一处证据来源（歌词/元数据）合起来，
        // 与宿主侧的 `MusicProvider#stopEvidence` 口径一致
        if (signal != PlaybackSignal.STOPPED && signal != PlaybackSignal.ERROR
            && signal != PlaybackSignal.SESSION_GONE && signal != PlaybackSignal.UNKNOWN) {
            stopEvidence(packageName).record();
        }

        StopGate gate = stopGate(packageName);
        switch (gate.onSignal(signal)) {
            case ARMED -> {
                stopEvidence(packageName).snapshot();
                XposedLog.logD(TAG, signal + " for " + packageName + "; awaiting confirmation");
            }
            case CANCELLED ->
                XposedLog.logD(TAG, "Pending stop for " + packageName + " cancelled by " + signal);
            case IGNORED -> {
                // UNKNOWN 在过渡期会成串出现，无差别输出会刷屏；只在确实有停止悬着时留痕
                if (gate.isPending()) {
                    XposedLog.logD(TAG, signal + " for " + packageName + " ignored while a stop is pending");
                }
            }
        }
    }

    /**
     * 每包的"播放仍在继续"证据计数（见 {@link StopEvidence}）
     *
     * <p>{@link StopGate} 的令牌只能证明"排期时没人撤销"，证明不了"确认成立之后也没人发布"。
     * 因此排期时 {@code snapshot()}、确认前 {@code isStillValid()}：期间来过歌词/元数据/其它播放
     * 状态，这次确认就不成立
     *
     * <p>与 {@link #mStopGates} 一样**不回收**：只有注册成发布者的包会写进来，数量被支持的
     * 宿主应用数所限；回收会让计数归零，使"期间有没有新证据"失去判别力
     */
    private final ConcurrentHashMap<String, StopEvidence> mStopEvidence = new ConcurrentHashMap<>();

    @NonNull
    private StopEvidence stopEvidence(@NonNull String packageName) {
        return mStopEvidence.computeIfAbsent(packageName, ignored -> new StopEvidence());
    }

    /** 撤销待确认的停止。只在真的撤掉了什么时留痕，否则元数据风暴会刷屏。 */
    private void cancelPendingStop(@NonNull String packageName) {
        StopGate gate = mStopGates.get(packageName);
        if (gate != null && gate.onEvidence() == StopGate.Decision.CANCELLED) {
            XposedLog.logD(TAG, "Pending stop cancelled by a newer state or metadata: " + packageName);
        }
    }

    /** 窗口内无人撤销，停止成立：清空会话并通知消费者。 */
    private void confirmStop(@NonNull String packageName) {
        SuperLyricData stopToNotify = null;
        synchronized (mSnapshotLock) {
            // 排期是否过期由 StopGate 的令牌负责；这里处理两件事："会话已经不在"，
            // 以及"排期之后又来了新证据"——后者是令牌那一关**拦不住**的：
            // 它只能证明"排期时没有人撤销"，证明不了"此后也没人发布"。
            // 少了这一条，恰好在窗口边缘到达的歌词会被随后的确认清掉
            PublisherSession session = mSessions.get(packageName);
            if (session == null) {
                return;
            }
            if (!stopEvidence(packageName).isStillValid()) {
                XposedLog.logD(TAG, "Stop for " + packageName
                    + " not confirmed: new evidence arrived inside the window");
                return;
            }
            session.clear();
            if (TextUtils.equals(mActivePublisher, packageName)) {
                mActivePublisher = null;
                mSystemTracker.stop();
                mSystemTracker.setLyrics(null);
                stopToNotify = new SuperLyricData();
            }
        }

        if (stopToNotify != null) {
            XposedLog.logD(TAG, "Playback stopped (confirmed) for " + packageName);
            SuperLyricData payload = stopToNotify;
            notifyReceiver(packageName, payload, (receiver, publisher, data) -> receiver.onStop(publisher, data));
        }
    }

    /**
     * 宿主的 MediaSession 被销毁
     *
     * 这是"播放源已经不存在"的**确定**信号，不是"不知道"——与 `STATE_NONE` 截然不同。
     * 此前服务端没有任何入口处理它，于是宿主**释放了会话而进程仍在**时，服务端收不到
     * 任何通知：`mActivePublisher` 一直挂着、时钟继续插值，界面就停在最后一句上
     */
    public void onSystemSessionDestroyed(@NonNull String packageName) {
        if (!isPublisher(packageName)) {
            return;
        }

        XposedLog.logI(TAG, "System session destroyed: " + packageName);
        // 排期与证据计数的读取必须和确认、发布互斥，否则记下的就是别处的时刻
        boolean armed;
        synchronized (mSnapshotLock) {
            armed = stopGate(packageName).onSignal(PlaybackSignal.SESSION_GONE) == StopGate.Decision.ARMED;
            if (armed) {
                stopEvidence(packageName).snapshot();
            }
        }
        if (armed) {
            XposedLog.logD(TAG, "Session gone for " + packageName + "; awaiting confirmation");
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

        synchronized (mSnapshotLock) {
            PublisherSession session = getOrCreateSessionLocked(packageName);
            session.playbackState = state;

            // 停止判定与下面的副作用分开：判定统一在内核（与宿主侧同一套），
            // 这里只负责"入播放状态后该做什么"
            applyStopSignalLocked(packageName, state.getState());

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
                    }
                }
                // 这三个状态一律**不推进时钟**，但含义并不相同：STOPPED / ERROR 是终态，
                // 要等窗口确认；NONE 是"不知道"，既不该确认也不该拿它的位置去推进进度。
                // 分支合并只是因为这个副作用相同，判定已在上面的 applyStopSignalLocked 里做完
                case PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE,
                     PlaybackState.STATE_ERROR -> {
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

        // 宿主还在宣布曲目，就说明播放没停
        cancelPendingStop(packageName);

        synchronized (mSnapshotLock) {
            PublisherSession session = mSessions.get(packageName);
            if (session == null) {
                return;
            }
            // 元数据与歌词一样是"播放仍在继续"的证据，计数必须在锁内自增：
            // 排期之后到达的元数据要能否决那次确认（见 confirmStop）
            stopEvidence(packageName).record();

            // 仅在当前会话已存在歌词但缺失时长时做安全元数据增强，绝不依据易变的 title 判定切歌或清空歌词。
            // **不能原地改**：这个实例可能正被另一个线程（框架侧序列化 Parcel）持有，
            // 原地改等于让它读到改到一半的值。这里构造副本，与进度包的写法一致
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
                if (base.hasTranslation()) {
                    enriched.setTranslation(base.getTranslation());
                }
                if (base.hasSecondary()) {
                    enriched.setSecondary(base.getSecondary());
                }
                session.latestLyric = enriched;
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
                    session.publishingPid = NO_PID;
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
                mDeathNotifier.execute(() -> {
                    // 反注册与死亡对消费方是同一件事，守卫也必须一致：这个包在我们排队期间
                    // 重新注册的话，这次 onStop 会误杀新会话，而它此时可能已经在播了。
                    //
                    // 判据只能是"现在是否还注册着"——**不能**拿移除前捕获的世代与移除后的
                    // 取值相比（移除后恒为 0，必然不等，守卫会恒真、通知永远发不出去）
                    if (isPublisher(packageName)) {
                        return;
                    }

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
        // 循环而非"淘汰一个就收工"：多个新包接连出现时，每次只淘汰一个追不上增长速度。
        // 退出条件是 size 低于上限，调用方随后插入新会话正好回到上限
        while (mSessions.size() >= MAX_CACHED_SESSIONS) {
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
            if (oldestPkg == null) {
                // 全部活跃或播放中：宁可暂时超上限，也不能清掉正在用的会话
                XposedLog.logW(TAG, "Cannot trim lyric sessions: all " + mSessions.size()
                    + " are active or playing; over MAX_CACHED_SESSIONS=" + MAX_CACHED_SESSIONS);
                return;
            }

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
        // 取不到包名会让 sendLyric / sendStop 静默丢弃全部报文——发布链路整体失效却毫无痕迹。
        // 该字段是 AMS 的私有实现，会随版本移动或改名，必须留下痕迹
        XposedLog.logW(TAG, "Cannot resolve package for pid " + pid
            + "; AMS#mPidsSelfLocked may have moved. Dropping this call.");
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

        /**
         * 最近一个真正向本服务发布过内容（歌词或停止）的进程 pid
         *
         * <p>一个宿主可以有多个进程注册成发布者，只有其中一个在真正发布。这个字段让
         * "正在发布的那一个死了"与"无关子进程死了"能被区分开——见 {@link #onProcessDied}
         */
        volatile int publishingPid = NO_PID;

        /**
         * 本会话是否收到过真正的发布（歌词或停止）
         *
         * <p>会话并不只由发布创建：[#onSystemPlaybackStateChanged] 与
         * [#onSystemMetadataChanged] 都会建会话，而它们都来自 system_server，拿不到宿主
         * 的 pid。因此"没人发布过"与"发布者 pid 是别人"必须能分开——见 {@link #onProcessDied}
         */
        volatile boolean published;

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
