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
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.bridge.LyricDocAdapter;
import com.hchen.superlyric.kernel.clock.PlaybackClock;
import com.hchen.superlyric.kernel.model.LyricDoc;
import com.hchen.superlyric.kernel.orchestrate.StopEvidence;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyric.utils.PlaybackStates;
import com.hchen.superlyric.utils.PublishGuard;
import com.hchen.superlyricapi.BuildConfig;
import com.hchen.superlyricapi.ISuperLyricManager;
import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.model.Lyric;
import com.hchen.superlyricapi.model.State;
import com.hchen.superlyricapi.model.Stop;
import com.hchen.superlyricapi.model.StopReason;
import com.hchen.superlyricapi.v4.ILyricFrameListener;
import com.hchen.superlyricapi.wire.Frame;
import com.hchen.superlyricapi.wire.WireFormatException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Super Lyric 核心系统服务
 *
 * <p>运行于 {@code system_server}，同时承载两代 Binder 接口的状态与广播：
 * <ul>
 *   <li>{@code super_lyric}（{@link ISuperLyricManager}）：3.x 的 Parcelable 通道；</li>
 *   <li>{@code super_lyric.v4}（v4 帧通道）：由 {@link SuperLyricV4Service} 转发到本类。</li>
 * </ul>
 *
 * <p>职责：
 * <ol>
 *   <li>维护发布者生命周期与进程死亡感知；</li>
 *   <li>按包隔离歌词会话（{@link PublisherRegistry.Session}），避免多应用并发覆盖；</li>
 *   <li>监听全局系统媒体播放状态，基于播放焦点驱动中央歌词时钟（{@code playback.tracker}）；</li>
 *   <li>按订阅者能力分别广播：3.x 接收器收 Parcelable 数据，v4 监听器收帧；</li>
 *   <li>限制会话数量与生命周期，避免 {@code system_server} 内的状态累积。</li>
 * </ol>
 *
 * <p>进度归系统：发布端只提交全量文档，position、speed、state 全部来自
 * {@link PlaybackState}，跨行与状态变化时由本类下发 State 帧。
 *
 * @author 焕晨HChen
 */
public final class SuperLyricService extends ISuperLyricManager.Stub {
    private static final String TAG = "SuperLyricService";

    /**
     * {@code Publisher.PublishResult} 的线上取值
     *
     * 客户端枚举的 {@code code} 是 internal 的，Java 侧不能直接引用；AIDL 的返回值语义
     * 已冻结，这里按同一份数值对齐。
     */
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

    /**
     * 最近一次停止帧
     *
     * {@code latest()} 在会话结束后返回它；注册首帧不补发 STOP（AIDL 的 registerListener
     * 契约只发在播 FULL）。有新的 FULL/STATE 到达时清空，与客户端 lastStop 的
     * "收到新歌词后清空"一致。
     */
    @Nullable
    private volatile byte[] mLastStopFrame;

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
     * v4 帧广播线程
     *
     * <p>与 3.x 通道分开：v4 是主通道，3.x 接收者的 500ms 超时不得把帧的投递拖在后面。
     */
    private final ExecutorService mFrameExecutor = Executors.newSingleThreadExecutor(
        new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SuperLyric-FrameBroadcaster");
                t.setDaemon(true);
                return t;
            }
        }
    );

    /**
     * 停止确认的计时器
     * <p>
     * 必须与广播线程分开：{@link #mBroadcastExecutor} 会同步调用每个接收者的 Binder 接口，
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
     * 不能复用 {@link #mBroadcastExecutor}：那条线程会同步等待每个接收者的 Binder 返回，
     * 一个卡住的消费者能把它堵住数秒，死亡通知便一直排在它后面发不出去，消费方会永远
     * 停在最后一句歌词上。两条线程并发广播是安全的（{@code RemoteCallbackList} 自身同步），
     * 而"先播出歌词、后播出停止"由世代守卫保证：死亡时世代已被移除，排队中的歌词
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
                    mLastStopFrame = null;
                    notifyFrames(packageName, fullFrame(packageName));
                }

                @Override
                public void onStop(@NonNull String packageName, @NonNull SuperLyricData data,
                                   @NonNull StopReason reason) {
                    notifyReceiver(packageName, data,
                        (receiver, publisher, payload) -> receiver.onStop(publisher, payload));

                    byte[] frame;
                    synchronized (publishers.lock) {
                        frame = encode(Frame.stop(new Stop(lyricIdLocked(packageName), reason, packageName)));
                    }
                    if (frame != null) mLastStopFrame = frame;
                    notifyFrames(packageName, frame);
                }
            },
            (delayMs, task) -> mStopConfirmer.schedule(task, delayMs, TimeUnit.MILLISECONDS));
        this.playback.tracker.setOnProgressAdvanceListener(this::onLineAdvanced);
    }

    /**
     * 时钟推进到新的一行
     *
     * 在追踪线程上调用：只做读快照与派发，实际的 Binder 调用在专用广播线程上执行。
     */
    private void onLineAdvanced(int newIndex, long positionMs) {
        try {
            advanceLine(newIndex, positionMs);
        } catch (Throwable t) {
            // 这段跑在 PlaybackTracker 的 HandlerThread 上；未捕获异常会让整个 system_server 进程退出
            XposedLog.logE(TAG, "Failed to advance line " + newIndex, t);
        }
    }

    private void advanceLine(int newIndex, long positionMs) {
        String activePkg;
        SuperLyricData lineData;
        byte[] stateFrame;

        synchronized (publishers.lock) {
            activePkg = publishers.activePublisher;
            PublisherRegistry.Session session = activePkg != null
                ? publishers.sessions.get(activePkg) : null;
            if (session == null || session.latestDoc == null || !isPublisher(activePkg)) {
                return;
            }

            lineData = LyricDocAdapter.INSTANCE.toLineData(session.latestDoc, newIndex, positionMs);
            if (lineData == null) return;

            // 3.x 通道的 getLatestLyric() 必须能自愈：只存增量包会让 3.x 客户端拉到一份
            // 没有全量行的数据，界面停在上一首。因此落库全量，广播增量。
            session.latestLyric = LyricDocAdapter.INSTANCE.toFullData(
                session.latestDoc, newIndex, positionMs);
            session.touch();

            stateFrame = session.latestV4Lyric != null
                ? encode(Frame.state(buildStateLocked(session, newIndex, positionMs), activePkg))
                : null;
        }

        XposedLog.logD(TAG, "[SysTracker] Advance line [" + newIndex + "] for " + activePkg
            + ": pos=" + positionMs + "ms");

        notifyReceiver(activePkg, lineData,
            (receiver, publisher, payload) -> receiver.onLyric(publisher, payload));
        notifyFrames(activePkg, stateFrame);
        mLastStopFrame = null;
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
        if (!canUseLegacyBinder()) return;
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (packageName.isEmpty()) {
            XposedLog.logW(TAG, "Registration as publisher failed! Unable to obtain package name for pid: " + pid);
            return;
        }
        PublisherRegistry.Registration registration =
            PublisherRegistry.registerPublisher(packageName, pid, Binder.getCallingUid());
        if (registration == PublisherRegistry.Registration.QUOTA) {
            XposedLog.logW(TAG, "Registration as publisher rejected by quota: " + packageName
                + ", pid: " + pid);
            return;
        }
        if (registration != PublisherRegistry.Registration.OK) return;
        if (playback.listener != null) {
            playback.listener.syncCurrentStateForPackage(packageName);
        }
    }

    @Override
    public void unregisterPublisher() throws RemoteException {
        if (!canUseLegacyBinder()) return;
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
        if (!canUseLegacyBinder()) return false;
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        return PublisherRegistry.isPublisherProcess(packageName, pid);
    }

    @Override
    public void sendLyric(SuperLyricData data) throws RemoteException {
        if (!canUseLegacyBinder()) return;
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
        byte[] v4Frame = null;

        synchronized (publishers.lock) {
            PublisherRegistry.Session session = getOrCreateSessionLocked(packageName);
            // 记下"正在发布的是这个进程"，供进程死亡时区分它是不是无关子进程
            session.publishingPid = pid;
            session.published = true;
            // 计数在锁内自增：它要与 confirmStop 的读取互斥，否则"窗口内有没有新证据"判不准
            stopEvidence(packageName).record();

            if (data.hasAllLyrics()) {
                session.latestLyric = data;
                // 3.x 发布者也须能被 v4 订阅者消费：文档是两代通道的共同投影源。
                // 3.x 允许 duration=0，投影前先用系统元数据补齐，避免客户端在末行提前结束进度
                try {
                    LyricDoc doc = LyricDocAdapter.INSTANCE.toDoc(data);
                    doc = LyricDocAdapter.INSTANCE.withDuration(doc, session.mediaFacts.durationMs());
                    session.latestDoc = doc;
                    session.latestV4Lyric = LyricDocAdapter.INSTANCE.toLyric(doc);
                } catch (Throwable t) {
                    // 3.x 客户端的数据不可信；投影失败只影响 v4 订阅者，不影响 3.x 通道
                    session.latestDoc = null;
                    session.latestV4Lyric = null;
                    XposedLog.logW(TAG, "Failed to project legacy lyric to v4 for " + packageName, t);
                }
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
            shouldBroadcast = claimActiveLocked(packageName, session);

            if (shouldBroadcast) {
                if (data.hasAllLyrics()) {
                    playback.tracker.setLyrics(session.latestDoc);
                    if (data.hasPosition()) {
                        playback.tracker.onSessionPositionSynced(data.getPosition());
                    }
                    // 只在状态明确时用它重新锚定时钟：NONE 表示"还没有状态"，喂给
                    // tracker 会被映射成 STOPPED 并停止推进，直到宿主下一次 PLAYING
                    if (session.playbackState != null
                        && session.playbackState.getState() != PlaybackState.STATE_NONE) {
                        playback.tracker.onPlaybackStateChanged(session.playbackState);
                    }
                    if (playback.listener != null) {
                        // 只登记，真正的调用在锁外（见下方说明）
                        syncPlayState = true;
                    }
                    v4Frame = fullFrameLocked(session);
                } else {
                    if (data.hasPosition()) {
                        playback.tracker.onSessionPositionSynced(data.getPosition());
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

        mLastStopFrame = null;
        notifyReceiver(packageName, broadcastPayload,
            (receiver, publisher, payload) -> receiver.onLyric(publisher, payload));
        notifyFrames(packageName, v4Frame);

        // 锁外调用：它会读取 MediaController 的播放状态并可能注册回调，是对第三方 App 的
        // Binder 调用；且系统播放回调会再取 publishers.lock，而广播路径持接收者锁走这条路，
        // 锁内调用即构成 AB-BA 死锁。排在 FULL 之后：同步会立即回调
        // onSystemPlaybackStateChanged，那条路径下发 STATE。
        if (syncPlayState && playback.listener != null) {
            playback.listener.syncCurrentStateForPackage(packageName);
        }
    }

    @Override
    public void sendStop(SuperLyricData data) throws RemoteException {
        if (!canUseLegacyBinder()) return;
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
        byte[] stopFrame = null;
        synchronized (publishers.lock) {
            PublisherRegistry.Session session = publishers.sessions.get(packageName);
            if (session != null) {
                session.publishingPid = pid;
                session.published = true;
                stopEvidence(packageName).record();
                stopFrame = encode(Frame.stop(new Stop(lyricIdLocked(packageName), StopReason.STOPPED, packageName)));
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
            if (stopFrame != null) mLastStopFrame = stopFrame;
            notifyReceiver(packageName, data,
                (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
            notifyFrames(packageName, stopFrame);
        }
    }

    /**
     * v4 发布：{@code ISuperLyricService.publish(byte[])}
     *
     * @return {@code Publisher.PublishResult} 的线上取值
     */
    int publishFrame(int pid, @NonNull String packageName, @Nullable byte[] raw) {
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

        // 4.0 没有显式注册入口：第一次 publish 即登记。必须在取锁前完成，
        // 因为 syncCurrentStateForPackage 会回调进本类的 onSystemPlaybackStateChanged，
        // 而那条路径要取 publishers.lock
        if (!isPublisherProcess(packageName, pid)) {
            PublisherRegistry.Registration registration =
                PublisherRegistry.registerPublisher(packageName, pid, Binder.getCallingUid());
            if (registration == PublisherRegistry.Registration.QUOTA) {
                XposedLog.logW(TAG, "publish rejected: publisher quota reached for " + packageName);
                return RESULT_REJECTED_QUOTA;
            }
            if (registration != PublisherRegistry.Registration.OK) {
                XposedLog.logW(TAG, "publish rejected: publisher not registered for " + packageName);
                return RESULT_REJECTED_NOT_REGISTERED;
            }
            if (playback.listener != null) {
                playback.listener.syncCurrentStateForPackage(packageName);
            }
        }

        Lyric incoming = frame.getDoc();
        LyricDoc cleanDoc;
        Lyric cleanLyric;
        try {
            LyricDoc doc = LyricDocAdapter.INSTANCE.fromLyric(incoming);
            SuperLyricData cleanData = LyricSanitizer.sanitizeData(
                LyricDocAdapter.INSTANCE.toFullData(doc));
            if (cleanData == null) {
                XposedLog.logW(TAG, "publish rejected: sanitization failed for " + packageName);
                return RESULT_REJECTED_MALFORMED;
            }
            cleanDoc = LyricDocAdapter.INSTANCE.toDoc(cleanData);
            cleanLyric = LyricDocAdapter.INSTANCE.toLyric(cleanDoc);
        } catch (Throwable t) {
            // 外部发布者的字节流不可信：解码后的模型可能触发构造器的空值校验，
            // system_server 里的 Binder 方法不能把这类异常抛回客户端
            XposedLog.logW(TAG, "publish rejected: unreadable document from " + packageName, t);
            return RESULT_REJECTED_MALFORMED;
        }
        if (cleanLyric == null) {
            XposedLog.logW(TAG, "publish rejected: no publishable line from " + packageName);
            return RESULT_REJECTED_MALFORMED;
        }
        // 4.0 的 Publisher 契约把 title 列为发布必填项；AIDL 的 publish 对缺失字段
        // 返回 MALFORMED，不能把它当成"标题未知"放行。
        if (cleanLyric.getTitle().trim().isEmpty()) {
            XposedLog.logW(TAG, "publish rejected: blank title from " + packageName);
            return RESULT_REJECTED_MALFORMED;
        }

        // 新歌词到达即"播放仍在继续"的证据；与 3.x 的 sendLyric 走同一套判定
        cancelPendingStop(packageName);

        boolean shouldBroadcast;
        boolean syncPlayState = false;
        byte[] fullFrame;
        SuperLyricData legacyPayload;

        synchronized (publishers.lock) {
            PublisherRegistry.Session session = getOrCreateSessionLocked(packageName);
            boolean titleUsable = session.mediaFacts.isTitleUsable();
            String sessionTitle = titleUsable ? session.mediaFacts.title() : null;
            long sessionDuration = session.mediaFacts.durationMs();
            if (!PublishGuard.matches(cleanLyric.getTitle(), cleanLyric.getDuration(),
                sessionTitle, sessionDuration)) {
                XposedLog.logW(TAG, "publish rejected: track mismatch for " + packageName
                    + " (frame title=\"" + cleanLyric.getTitle() + "\" duration=" + cleanLyric.getDuration()
                    + "ms, session title=\"" + sessionTitle + "\" duration="
                    + sessionDuration + "ms)");
                return RESULT_REJECTED_TRACK_MISMATCH;
            }
            if (!titleUsable) {
                // 宿主把歌词行写进了 TITLE；此处退化为只比时长，仍是一次成功的校验，
                // 只是少了一个不可信的维度。两侧都留痕，避免"跳过"看起来像"未触发"
                XposedLog.logD(TAG, "publish: title check degraded to duration only for "
                    + packageName + " (MediaSession title is unstable)");
            }

            // duration = 0 表示未知；系统才是时长的权威，补齐后再下发与落库。
            // 校验必须用发布端原值：补齐后再比等于拿系统值和系统值比，会丢掉曲目校验。
            if (cleanLyric.getDuration() <= 0L) {
                long mediaDuration = session.mediaFacts.durationMs();
                if (mediaDuration > 0L) {
                    cleanDoc = LyricDocAdapter.INSTANCE.withDuration(cleanDoc, mediaDuration);
                    cleanLyric = new Lyric(cleanLyric.getId(), cleanLyric.getTitle(),
                        cleanLyric.getArtist(), cleanLyric.getAlbum(), mediaDuration, cleanLyric.getLines());
                }
            }

            long position = Math.max(0L, playback.tracker.positionNow());
            int index = PlaybackClock.Companion.lineIndexAt(cleanDoc.getLines(), position);
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
            stopEvidence(packageName).record();
            session.latestDoc = cleanDoc;
            session.latestV4Lyric = cleanLyric;
            session.latestLyric = LyricDocAdapter.INSTANCE.toFullData(cleanDoc, index, position);
            legacyPayload = session.latestLyric;

            shouldBroadcast = claimActiveLocked(packageName, session);
            if (shouldBroadcast) {
                playback.tracker.setLyrics(cleanDoc);
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

        XposedLog.logD(TAG, "publish [FULL, lines=" + cleanDoc.getLines().size() + "] from "
            + packageName + " (pid " + pid + "): trackId=" + cleanLyric.getId()
            + ", duration=" + cleanLyric.getDuration() + "ms, frame=" + fullFrame.length + " bytes");

        mLastStopFrame = null;
        notifyReceiver(packageName, legacyPayload,
            (receiver, publisher, payload) -> receiver.onLyric(publisher, payload));
        notifyFrames(packageName, fullFrame);

        // 同步必须排在 FULL 之后：它会同步回调 onSystemPlaybackStateChanged，而那条路径
        // 会下发 STATE 帧。先 STATE 后 FULL 会让客户端多走一次 latest() 自愈。
        if (syncPlayState && playback.listener != null) {
            playback.listener.syncCurrentStateForPackage(packageName);
        }
        return RESULT_OK;
    }

    /**
     * v4 清除：{@code ISuperLyricService.clear()}
     */
    void clearFrame(int pid, @NonNull String packageName) {
        if (packageName.isEmpty() || !isPublisherProcess(packageName, pid)) return;

        // 发布者主动清除是权威结论，无需再让一个待确认的停止去清第二次
        cancelPendingStop(packageName);

        boolean wasActive = false;
        byte[] stopFrame = null;
        synchronized (publishers.lock) {
            PublisherRegistry.Session session = publishers.sessions.get(packageName);
            if (session != null) {
                session.publishingPid = pid;
                session.published = true;
                stopEvidence(packageName).record();
                stopFrame = encode(Frame.stop(new Stop(lyricIdLocked(packageName), StopReason.CLEARED, packageName)));
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
            XposedLog.logI(TAG, "Cleared lyric for " + packageName + " (pid " + pid + ")");
            if (stopFrame != null) mLastStopFrame = stopFrame;
            notifyReceiver(packageName, new SuperLyricData(),
                (receiver, publisher, payload) -> receiver.onStop(publisher, payload));
            notifyFrames(packageName, stopFrame);
        }
    }

    /**
     * v4 快照：{@code ISuperLyricService.latest()}
     *
     * <p>在播时返回 FULL；会话已结束时返回最近一次 STOP；从未有会话时为 null。
     *
     * @return FULL/STOP 帧；没有任何会话时为 null
     */
    @Nullable
    byte[] latestFrame() {
        synchronized (publishers.lock) {
            if (publishers.activePublisher != null) {
                return latestFullFrameLocked();
            }
            return mLastStopFrame;
        }
    }

    /**
     * 只有在播会话的 FULL 快照；没有在播会话时为 null
     *
     * <p>注册首帧只允许 FULL，STOP 由客户端调用 {@code latest()} 获取。
     */
    @Nullable
    private byte[] latestFullFrame() {
        synchronized (publishers.lock) {
            return latestFullFrameLocked();
        }
    }

    @Nullable
    private byte[] latestFullFrameLocked() {
        String active = publishers.activePublisher;
        if (active == null) return null;
        PublisherRegistry.Session session = publishers.sessions.get(active);
        if (session == null || session.latestV4Lyric == null || !isPublisher(active)) return null;

        PlaybackState state = session.playbackState;
        long position = Math.max(0L, playback.tracker.positionNow());
        int index = playback.tracker.indexNow();
        State snapshot = new State(lyricIdLocked(active), index, position,
            playback.tracker.currentSpeed(),
            PlaybackStates.toV4(state != null ? state.getState() : PlaybackState.STATE_NONE));
        return encode(Frame.full(session.latestV4Lyric, snapshot, active));
    }

    /**
     * v4 注册：{@code ISuperLyricService.registerListener(ILyricFrameListener)}
     *
     * @return {@code Receiver.RegisterResult} 的线上取值
     */
    int registerFrameListener(@NonNull ILyricFrameListener listener, int uid, int pid,
                              @NonNull String packageName) {
        int result = mFrames.register(listener, uid, pid, packageName);
        if (result != FrameBroadcaster.REGISTER_OK) return result;

        // 首帧异步补发：v4 契约允许在 registerListener 返回前回调，但把它排到广播线程
        // 既保证它排在已提交的帧之后，也避免在注册路径上做一次可能很慢的 Binder 回调。
        // 注册首帧只发在播会话的 FULL；STOP 由客户端调用 latest() 获取。
        mFrameExecutor.execute(() -> {
            byte[] initial = latestFullFrame();
            if (initial != null) {
                mFrames.deliver(listener, initial);
            }
        });
        return FrameBroadcaster.REGISTER_OK;
    }

    void unregisterFrameListener(@NonNull ILyricFrameListener listener, int pid,
                                 @NonNull String packageName) {
        mFrames.unregister(listener, pid, packageName);
    }

    @Override
    public void setSystemPlayStateListenerEnabled(boolean enabled) throws RemoteException {
        if (!canUseLegacyBinder()) return;
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
        if (!canUseLegacyBinder()) return;
        if (receiver == null) return;
        int pid = Binder.getCallingPid();
        mReceivers.register(receiver, Binder.getCallingUid(), pid, getPackageNameWithPid(pid));
    }

    @Override
    public void unregisterReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (!canUseLegacyBinder()) return;
        if (receiver == null) return;
        int pid = Binder.getCallingPid();
        mReceivers.unregister(receiver, pid, getPackageNameWithPid(pid));
    }

    @Override
    public boolean isReceiverRegistered(ISuperLyricReceiver receiver) throws RemoteException {
        if (!canUseLegacyBinder()) return false;
        return receiver != null && mReceivers.isRegistered(receiver);
    }

    @Nullable
    @Override
    public SuperLyricData getLatestLyric() throws RemoteException {
        if (!canUseLegacyBinder()) return null;
        synchronized (publishers.lock) {
            String active = publishers.activePublisher;
            if (active != null) {
                PublisherRegistry.Session session = publishers.sessions.get(active);
                if (session != null) {
                    // 3.x 客户端的自愈依赖这条通道：必须是 3.x 全量格式，不能返回 v4 模型
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
        if (!canUseLegacyBinder()) return 0;
        return BuildConfig.API_VERSION;
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
                ? isPublisher(packageName) : isStillPublishing(packageName);
            if (superseded) return;
            broadcastToReceivers("onProcessDied",
                receiver -> receiver.onStop(packageName, new SuperLyricData()));
            // 发布者已经注销，notifyFrames 的发布者守卫会丢掉这一帧；死亡通知必须直发
            byte[] stopFrame = encode(Frame.stop(new Stop(null, StopReason.PROCESS_DIED, packageName)));
            if (stopFrame != null) {
                mLastStopFrame = stopFrame;
                mFrameExecutor.execute(() -> mFrames.broadcast(stopFrame));
            }
        });
    }

    private boolean isStillPublishing(@NonNull String packageName) {
        return publishers.isStillPublishing(packageName);
    }

    /**
     * 每包的"播放仍在继续"证据计数
     */
    @NonNull
    private StopEvidence stopEvidence(@NonNull String packageName) {
        return playback.evidence(packageName);
    }

    /**
     * 撤销待确认的停止；只在确实撤销了排期时留痕，避免元数据风暴刷屏
     */
    private void cancelPendingStop(@NonNull String packageName) {
        if (playback.cancelPending(packageName)) {
            XposedLog.logD(TAG, "Pending stop cancelled by a newer state or metadata: " + packageName);
        }
    }

    /**
     * 宿主的 MediaSession 被销毁
     *
     * <p>这是"播放源已经不存在"的确定信号，与 {@code STATE_NONE}（不知道）截然不同。
     * 宿主释放会话而进程仍在时，这是服务端能收到的唯一通知；缺少它，
     * {@code publishers.activePublisher} 会一直挂着、时钟继续插值，界面停在最后一行。
     */
    public void onSystemSessionDestroyed(@NonNull String packageName) {
        playback.onSessionDestroyed(packageName);
    }

    public void onSystemPlaybackStateChanged(@NonNull String packageName, @NonNull PlaybackState state) {
        playback.onPlaybackStateChanged(packageName, state);
        // PAUSED 不是停止：下发 State(PAUSED)，让客户端冻结在当前位置而不是清空歌词。
        // 停止仍由 StopGate 的确认窗口决定。
        broadcastStateFrame(packageName);
    }

    public void onSystemMetadataChanged(@NonNull String packageName, @Nullable MediaMetadata metadata) {
        playback.onMetadataChanged(packageName, metadata);
    }

    private void broadcastToReceivers(@NonNull String action,
                                      @NonNull ReceiverBroadcaster.Invocation invocation) {
        mReceivers.broadcast(action, invocation);
    }

    /**
     * 当前曲目文档的 FULL 帧（含当前状态）
     *
     * @return 编码后的帧；没有可下发的文档或超过单帧上限时为 null
     */
    @Nullable
    private byte[] fullFrame(@NonNull String packageName) {
        synchronized (publishers.lock) {
            return fullFrameLocked(publishers.sessions.get(packageName));
        }
    }

    @Nullable
    private byte[] fullFrameLocked(@Nullable PublisherRegistry.Session session) {
        if (session == null || session.latestV4Lyric == null) return null;
        return encode(Frame.full(session.latestV4Lyric, buildStateLocked(session), session.packageName));
    }

    /**
     * 状态变化时的 STATE 帧
     *
     * <p>只对当前活跃发布者下发；没有文档或状态未知时不下发，否则客户端会冻结在一个
     * 并未观察到的结论上。
     */
    private void broadcastStateFrame(@NonNull String packageName) {
        byte[] frame;
        synchronized (publishers.lock) {
            if (!TextUtils.equals(publishers.activePublisher, packageName)) return;
            PublisherRegistry.Session session = publishers.sessions.get(packageName);
            if (session == null || session.latestV4Lyric == null) return;
            PlaybackState state = session.playbackState;
            if (state == null || PlaybackStates.toV4(state.getState()) == null) return;
            frame = encode(Frame.state(buildStateLocked(session), packageName));
        }
        notifyFrames(packageName, frame);
        mLastStopFrame = null;
    }

    @NonNull
    private State buildStateLocked(@NonNull PublisherRegistry.Session session) {
        return buildStateLocked(session, playback.tracker.indexNow(),
            Math.max(0L, playback.tracker.positionNow()));
    }

    @NonNull
    private State buildStateLocked(@NonNull PublisherRegistry.Session session, int index, long position) {
        PlaybackState state = session.playbackState;
        return new State(session.latestV4Lyric != null ? session.latestV4Lyric.getId() : null,
            index, Math.max(0L, position), playback.tracker.currentSpeed(),
            PlaybackStates.toV4(state != null ? state.getState() : PlaybackState.STATE_NONE));
    }

    @Nullable
    private String lyricIdLocked(@NonNull String packageName) {
        PublisherRegistry.Session session = publishers.sessions.get(packageName);
        if (session == null || session.latestV4Lyric == null) return null;
        return session.latestV4Lyric.getId();
    }

    /**
     * 单帧上限与客户端一致（{@link Frame#MAX_SIZE}）；超限的帧在发送前丢弃
     *
     * <p>Binder 事务不会为此报错，客户端只会看到一帧缺失。
     */
    @Nullable
    private static byte[] encode(@Nullable Frame frame) {
        if (frame == null) return null;
        byte[] bytes;
        try {
            bytes = frame.encode();
        } catch (Throwable t) {
            // 编码失败同样不能把异常抛给 Binder 调用方或广播线程
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

    /**
     * 仲裁：当前上报应用是否有权成为全局活跃发布者
     *
     * <p>调用方必须持有 {@link PublisherRegistry#lock}。
     */
    private boolean claimActiveLocked(@NonNull String packageName,
                                      @NonNull PublisherRegistry.Session session) {
        if (publishers.activePublisher == null) {
            publishers.activePublisher = packageName;
            return true;
        }
        if (TextUtils.equals(publishers.activePublisher, packageName)) {
            return true;
        }

        // 另一个应用处于活跃：检查活跃应用是否真实处于播放中
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
            broadcastToReceivers("removePublisherProcess",
                receiver -> receiver.onStop(packageName, new SuperLyricData()));
            byte[] stopFrame = encode(Frame.stop(new Stop(null, StopReason.CLEARED, packageName)));
            if (stopFrame != null) {
                mLastStopFrame = stopFrame;
                mFrameExecutor.execute(() -> mFrames.broadcast(stopFrame));
            }
        });
    }

    private PublisherRegistry.Session getOrCreateSessionLocked(@NonNull String packageName) {
        PublisherRegistry.Session session = publishers.getOrCreateSession(packageName);
        session.touch();
        return session;
    }

    /**
     * 旧版 Binder 允许系统应用、支持应用和 3.x SDK 客户端访问
     *
     * <p>拒绝时只返回空操作，不向调用方抛异常。
     */
    private static boolean canUseLegacyBinder() {
        int uid = Binder.getCallingUid();
        boolean allowed = AccessPolicy.allowsLegacy(uid);
        if (!allowed) {
            XposedLog.logD(TAG, "Denied legacy Binder call from uid " + uid);
        }
        return allowed;
    }

    String getPackageNameWithPid(int pid) {
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
        // 取不到包名会让 sendLyric / sendStop 静默丢弃全部报文，发布链路失效且无痕迹。
        // 该字段是 AMS 私有实现，会随版本移动或改名，失败必须留痕
        XposedLog.logW(TAG, "Cannot resolve package for pid " + pid
            + "; AMS#mPidsSelfLocked may have moved. Dropping this call.");
        return "";
    }

    @FunctionalInterface
    private interface IReceiverCallback {
        void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException;
    }
}
