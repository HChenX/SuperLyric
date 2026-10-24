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

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Super Lyric 核心系统服务。
 * <p>
 * 运行于 {@code system_server} 进程，主要职责：
 * <ol>
 *   <li>维护发布者（Publisher）生命周期与进程死亡感知；</li>
 *   <li>隔离管理各音乐应用的歌词会话（{@link PublisherRegistry.Session}），防止多应用并发数据覆盖；</li>
 *   <li>监听全局系统媒体播放状态，基于播放焦点自治驱动中央歌词时钟（{@code playback.tracker}）；</li>
 *   <li>注册并向所有接收者（Receiver）安全广播歌词数据；</li>
 *   <li>严格限制会话生命周期与内存占用，确保 {@code system_server} 零泄露与高稳定性。</li>
 * </ol>
 *
 * @author 焕晨HChen
 */
public final class SuperLyricService extends ISuperLyricManager.Stub {
    private static final String TAG = "SuperLyricService";

    /** "还没有任何进程发布过"的哨兵。Binder 的 pid 恒 &gt; 0 */
    private static final int NO_PID = 0;

    private final Object mAms;
    private final ReceiverBroadcaster mReceivers = new ReceiverBroadcaster();

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
     * 必须与广播线程分开：{@link #mBroadcastExecutor} 会<b>同步</b>调用每个接收者的 Binder 接口，
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
     * 不能复用 {@link #mBroadcastExecutor}：那条线程会<b>同步</b>等待每个接收者的 Binder 返回，
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

    private final PublisherRegistry publishers = new PublisherRegistry();

    private final SystemPlaybackCoordinator playback;

    public SuperLyricService(@NonNull Object ams) {
        this.mAms = ams;
        this.playback = new SystemPlaybackCoordinator(
            publishers,
            new SystemPlaybackCoordinator.Host() {
                @Override
                public void onLyric(@NonNull String packageName, @NonNull SuperLyricData data) {
                    notifyReceiver(packageName, data,
                        (receiver, publisher, payload) -> receiver.onLyric(publisher, payload));
                }

                @Override
                public void onStop(@NonNull String packageName, @NonNull SuperLyricData data) {
                    notifyReceiver(packageName, data,
                        (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
                }
            },
            (delayMs, task) -> mStopConfirmer.schedule(task, delayMs, TimeUnit.MILLISECONDS));
        this.playback.tracker.setOnProgressAdvanceListener((newIndex, progress) -> {
            SuperLyricData cleanProgress = LyricSanitizer.sanitizeData(progress);
            if (cleanProgress == null) return;

            // 活跃发布者与目标会话必须**在同一把锁内**读取：
            // 否则可能把进度写进 A 的会话，却按 B 的包名广播出去。
            String activePkg;
            synchronized (publishers.lock) {
                activePkg = publishers.activePublisher;
                PublisherRegistry.Session session = activePkg != null ? publishers.sessions.get(activePkg) : null;
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
        this.playback.listener = listener;
    }

    public static boolean isPublisher(@NonNull String packageName) {
        return PublisherRegistry.isPublisher(packageName);
    }

    private static boolean isPublisherProcess(@NonNull String packageName, int pid) {
        return PublisherRegistry.isPublisherProcess(packageName, pid);
    }

    public static boolean isSystemPlayStateListenerDisabled(@NonNull String packageName) {
        return PublisherRegistry.isDisabled(packageName);
    }

    @Override
    public void registerPublisher() throws RemoteException {
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (packageName.isEmpty()) {
            XposedLog.logW(TAG, "Registration as publisher failed! Unable to obtain package name for pid: " + pid);
            return;
        }
        if (!PublisherRegistry.registerPublisher(packageName, pid, Binder.getCallingUid())) return;
        if (playback.listener != null) {
            playback.listener.syncCurrentStateForPackage(packageName);
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
        return PublisherRegistry.isPublisherProcess(packageName, pid);
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

        synchronized (publishers.lock) {
            PublisherRegistry.Session session = getOrCreateSessionLocked(packageName);
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
            if (publishers.activePublisher == null) {
                publishers.activePublisher = packageName;
                shouldBroadcast = true;
            } else if (TextUtils.equals(publishers.activePublisher, packageName)) {
                shouldBroadcast = true;
            } else {
                // 另一个应用处于活跃：检查活跃应用是否真实处于播放中
                PublisherRegistry.Session activeSession = publishers.sessions.get(publishers.activePublisher);
                boolean activePlaying = activeSession != null && activeSession.isPlaying();
                boolean currentPlaying = session.isPlaying();

                if (!activePlaying || currentPlaying) {
                    XposedLog.logI(TAG, "Publisher active switched from " + publishers.activePublisher + " to " + packageName
                        + " (activePlaying=" + activePlaying + ", currentPlaying=" + currentPlaying + ")");
                    publishers.activePublisher = packageName;
                    shouldBroadcast = true;
                } else {
                    XposedLog.logD(TAG, "Publisher " + packageName + " sent lyric cached in session, suppressed by active playing " + publishers.activePublisher);
                }
            }

            if (shouldBroadcast) {
                if (data.hasAllLyrics()) {
                    playback.tracker.setLyrics(data);
                    if (data.hasPosition()) {
                        playback.tracker.onPositionSynced(data.getPosition());
                    }
                    // 只在有**明确**状态时用它重新锚定时钟：`NONE` 是"还没有状态"，喂给
                    // tracker 会被映射成 STOPPED 并把时钟冻住，直到宿主下一次 PLAYING
                    if (session.playbackState != null
                        && session.playbackState.getState() != PlaybackState.STATE_NONE) {
                        playback.tracker.onPlaybackStateChanged(session.playbackState);
                    }
                    if (playback.listener != null) {
                        // 只登记，真正的调用在锁外（见下方说明）
                        syncPlayState = true;
                    }
                } else {
                    if (data.hasPosition()) {
                        playback.tracker.onPositionSynced(data.getPosition());
                    }
                }
            }
        }

        // 锁外调用：它会读 MediaController 的播放状态并可能注册回调——**是对第三方 App 的
        // Binder 调用**；更要紧的是它会回调进 onSystemPlaybackStateChanged 再取 publishers.lock，
        // 而广播路径会持接收者锁走这条路，锁内调用即构成 AB-BA 死锁
        if (syncPlayState && playback.listener != null) {
            playback.listener.syncCurrentStateForPackage(packageName);
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
            synchronized (publishers.lock) {
                PublisherRegistry.Session session = publishers.sessions.get(packageName);
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
        synchronized (publishers.lock) {
            PublisherRegistry.Session session = publishers.sessions.get(packageName);
            if (session != null) {
                session.publishingPid = pid;
                session.published = true;
                stopEvidence(packageName).record();
                session.clear();
            }
            if (TextUtils.equals(publishers.activePublisher, packageName)) {
                publishers.activePublisher = null;
                playback.tracker.stop();
                playback.tracker.setLyrics(null);
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
                PublisherRegistry.disabledPackages.remove(packageName);
            } else {
                PublisherRegistry.disabledPackages.add(packageName);
            }
            XposedLog.logI(TAG, "System play state listener enabled: " + enabled + ", caller: " + packageName);
        }
    }

    @Override
    public void registerReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver == null) return;
        int pid = Binder.getCallingPid();
        mReceivers.register(receiver, Binder.getCallingUid(), pid, getPackageNameWithPid(pid));
    }

    @Override
    public void unregisterReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver == null) return;
        int pid = Binder.getCallingPid();
        mReceivers.unregister(receiver, pid, getPackageNameWithPid(pid));
    }

    @Override
    public boolean isReceiverRegistered(ISuperLyricReceiver receiver) throws RemoteException {
        return receiver != null && mReceivers.isRegistered(receiver);
    }

    @Nullable
    @Override
    public SuperLyricData getLatestLyric() throws RemoteException {
        synchronized (publishers.lock) {
            String active = publishers.activePublisher;
            if (active != null) {
                PublisherRegistry.Session session = publishers.sessions.get(active);
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
        PublisherRegistry.DeathResult result = publishers.onProcessDied(packageName, pid, playback);
        if (result == null) return;

        if (!result.lastPid && !result.wasActive) {
            XposedLog.logD(TAG, "Non-publishing process died for " + packageName
                + ", pid=" + pid + "; " + PublisherRegistry.processCount(packageName)
                + " publisher process(es) remain");
            return;
        }

        XposedLog.logI(TAG, "Publisher process died: " + packageName + ", pid=" + pid
            + " (lastPid=" + result.lastPid + ", wasActive=" + result.wasActive + ")");
        if (!result.wasActive) return;

        mDeathNotifier.execute(() -> {
            boolean superseded = result.lastPid
                ? isPublisher(packageName) : isStillPublishing(packageName);
            if (superseded) return;
            broadcastToReceivers("onProcessDied",
                receiver -> receiver.onStop(packageName, new SuperLyricData()));
        });
    }

    private boolean isStillPublishing(@NonNull String packageName) {
        return publishers.isStillPublishing(packageName);
    }

    /**
     * 各发布者的停止判定
     *
     * 判定规则本身在内核的 {@link StopGate}，与宿主进程内的 Provider <b>共用同一套</b>：
     * `STATE_NONE` 在中性语义下不推进停止，两侧必须保持同一映射
     *
     * <p>实例<b>不回收</b>：只有注册成发布者的包才会走到这里（见
     * {@link #onSystemPlaybackStateChanged} 首行的 isPublisher 判定），数量被支持的宿主
     * 应用数量所限，而一个 StopGate 只有几个字段。回收反而会引入竞态：删掉后重建的新实例
     * 令牌从头开始，旧实例上仍在排期的任务会重新对上号
     */
    /**
     * 每包的"播放仍在继续"证据计数（见 {@link StopEvidence}）
     *
     * <p>{@link StopGate} 的令牌只能证明"排期时没人撤销"，证明不了"确认成立之后也没人发布"。
     * 因此排期时 {@code snapshot()}、确认前 {@code isStillValid()}：期间来过歌词/元数据/其它播放
     * 状态，这次确认就不成立
     *
     * <p>与 {@link SystemPlaybackCoordinator#stopGates} 一样<b>不回收</b>：只有注册成发布者的包会写进来，数量被支持的
     * 宿主应用数所限；回收会让计数归零，使"期间有没有新证据"失去判别力
     */
    @NonNull
    private StopEvidence stopEvidence(@NonNull String packageName) {
        return playback.evidence(packageName);
    }

    /** 撤销待确认的停止。只在真的撤掉了什么时留痕，否则元数据风暴会刷屏。 */
    private void cancelPendingStop(@NonNull String packageName) {
        if (playback.cancelPending(packageName)) {
            XposedLog.logD(TAG, "Pending stop cancelled by a newer state or metadata: " + packageName);
        }
    }

    /**
     * 宿主的 MediaSession 被销毁
     *
     * 这是"播放源已经不存在"的<b>确定</b>信号，不是"不知道"——与 `STATE_NONE` 截然不同。
     * 宿主<b>释放了会话而进程仍在</b>时，这是服务端能收到的唯一通知
     * 任何通知：`publishers.activePublisher` 一直挂着、时钟继续插值，界面就停在最后一句上
     */
    public void onSystemSessionDestroyed(@NonNull String packageName) {
        playback.onSessionDestroyed(packageName);
    }

    public void onSystemPlaybackStateChanged(@NonNull String packageName, @NonNull PlaybackState state) {
        playback.onPlaybackStateChanged(packageName, state);
    }

    public void onSystemMetadataChanged(@NonNull String packageName, @Nullable MediaMetadata metadata) {
        playback.onMetadataChanged(packageName, metadata);
    }

    private void broadcastToReceivers(@NonNull String action,
                                      @NonNull ReceiverBroadcaster.Invocation invocation) {
        mReceivers.broadcast(action, invocation);
    }

    private void notifyReceiver(String publisher, SuperLyricData data, IReceiverCallback callback) {
        if (isPublisher(publisher)) {
            long generation = PublisherRegistry.generations.getOrDefault(publisher, 0L);
            mBroadcastExecutor.execute(() -> {
                if (!isPublisher(publisher) || PublisherRegistry.generations.getOrDefault(publisher, 0L) != generation) {
                    return;
                }

                broadcastToReceivers("notifyReceiver", receiver -> callback.call(receiver, publisher, data));
            });
        }
    }

    private void removePublisherProcess(@NonNull String packageName, int pid) {
        PublisherRegistry.DeathResult result = publishers.onProcessDied(packageName, pid, playback);
        if (result == null || !result.wasActive) return;

        mDeathNotifier.execute(() -> {
            if (isPublisher(packageName)) return;
            broadcastToReceivers("removePublisherProcess",
                receiver -> receiver.onStop(packageName, new SuperLyricData()));
        });
    }

    private PublisherRegistry.Session getOrCreateSessionLocked(@NonNull String packageName) {
        PublisherRegistry.Session session = publishers.getOrCreateSession(packageName);
        session.touch();
        return session;
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
}
