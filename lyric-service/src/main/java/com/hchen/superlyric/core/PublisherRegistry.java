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

import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.utils.MediaFacts;
import com.hchen.superlyricapi.model.Lyric;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发布者进程、世代与按包会话的状态仓库
 *
 * <p>会话表与 {@code activePublisher} 由 {@link #lock} 保护；进程集合与世代使用并发容器，
 * 可在任意线程访问。
 */
final class PublisherRegistry {

    static final int NO_PID = 0;

    static final ConcurrentHashMap<String, Set<Integer>> pids = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Long> generations = new ConcurrentHashMap<>();
    static final AtomicLong generationSequence = new AtomicLong();
    static final Set<String> disabledPackages = ConcurrentHashMap.newKeySet();

    // 会话缓存上限；裁剪时跳过正在播放的会话与 activePublisher
    private static final int MAX_CACHED_SESSIONS = 5;

    /**
     * 发布方配额
     *
     * <p>仅用于兜底异常宿主无限登记进程，不是功能限制：正常音乐应用只有一到两个进程
     * 会发布。超过配额时 v4 publish 返回 REJECTED_QUOTA，不静默丢弃。
     */
    private static final int MAX_PUBLISHER_PACKAGES = 32;
    private static final int MAX_PIDS_PER_PACKAGE = 8;

    /**
     * 发布方登记结果
     *
     * <p>{@link #QUOTA} 与普通拒绝分开，供 v4 的发布结果码使用。
     */
    enum Registration {
        OK,
        REJECTED,
        QUOTA,
    }

    static final class DeathResult {
        final boolean lastPid;
        final boolean wasActive;

        DeathResult(boolean lastPid, boolean wasActive) {
            this.lastPid = lastPid;
            this.wasActive = wasActive;
        }
    }

    final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    final Object lock = new Object();
    volatile String activePublisher;

    static boolean isPublisher(@NonNull String packageName) {
        Set<Integer> registrations = pids.get(packageName);
        return registrations != null && !registrations.isEmpty();
    }

    static boolean isPublisherProcess(@NonNull String packageName, int pid) {
        Set<Integer> registrations = pids.get(packageName);
        return registrations != null && registrations.contains(pid);
    }

    static boolean isDisabled(@NonNull String packageName) {
        return disabledPackages.contains(packageName);
    }

    static Registration registerPublisher(@NonNull String packageName, int pid, int uid) {
        // 服务入口已拒绝未获 Binder 授权的调用方；此处保留第二道检查，
        // 防止未来调用方绕过入口直接登记发布者。
        if (!AccessPolicy.allowsLegacy(uid) && !AccessPolicy.allowsV4(uid)) {
            XposedLog.logW("LyricRuntime", "Registration as publisher rejected! Unauthorized caller: "
                + packageName + ", uid: " + uid + ", pid: " + pid);
            return Registration.REJECTED;
        }

        return registerWithinQuota(packageName, pid);
    }

    /**
     * 配额检查与登记必须原子完成；拆出包内入口供测试覆盖配额边界。
     */
    static synchronized Registration registerWithinQuota(@NonNull String packageName, int pid) {
        Set<Integer> registrations = pids.get(packageName);
        if (registrations != null && registrations.contains(pid)) {
            return Registration.OK;
        }
        if (registrations == null) {
            if (pids.size() >= MAX_PUBLISHER_PACKAGES) {
                XposedLog.logW("LyricRuntime", "Registration as publisher rejected! Quota: "
                    + pids.size() + "/" + MAX_PUBLISHER_PACKAGES + " packages, caller: " + packageName);
                return Registration.QUOTA;
            }
        } else if (registrations.size() >= MAX_PIDS_PER_PACKAGE) {
            XposedLog.logW("LyricRuntime", "Registration as publisher rejected! Quota: "
                + registrations.size() + "/" + MAX_PIDS_PER_PACKAGE + " pids for " + packageName);
            return Registration.QUOTA;
        }

        register(packageName, pid);
        XposedLog.logI("LyricRuntime", "Register publisher: " + packageName + ", pid: " + pid);
        return Registration.OK;
    }

    static boolean register(@NonNull String packageName, int pid) {
        Set<Integer> registrations = pids.computeIfAbsent(
            packageName, ignored -> ConcurrentHashMap.newKeySet());
        if (!registrations.add(pid)) return false;
        generations.put(packageName, generationSequence.incrementAndGet());
        return true;
    }

    static boolean removeProcess(@NonNull String packageName, int pid) {
        Set<Integer> registrations = pids.get(packageName);
        if (registrations == null || !registrations.remove(pid)) return false;
        if (!registrations.isEmpty()) return false;
        pids.remove(packageName, registrations);
        generations.remove(packageName);
        disabledPackages.remove(packageName);
        return true;
    }

    static int processCount(@NonNull String packageName) {
        Set<Integer> registrations = pids.get(packageName);
        return registrations == null ? 0 : registrations.size();
    }

    Session session(@NonNull String packageName) {
        return sessions.get(packageName);
    }

    @Nullable
    DeathResult onProcessDied(@NonNull String packageName, int pid,
                              @NonNull SystemPlaybackCoordinator playback) {
        if (!isPublisherProcess(packageName, pid)) return null;
        boolean lastPid = removeProcess(packageName, pid);
        boolean wasActive = false;
        synchronized (lock) {
            Session session = sessions.get(packageName);
            boolean wasPublishing = session != null
                && (session.publishingPid == pid || !session.published);
            if (!lastPid && !wasPublishing) {
                return new DeathResult(false, false);
            }
            if (session != null) {
                session.publishingPid = NO_PID;
                session.clear();
            }
            if (lastPid) {
                sessions.remove(packageName);
                playback.remove(packageName);
            }
            if (TextUtils.equals(activePublisher, packageName)) {
                activePublisher = null;
                playback.tracker.stop();
                playback.tracker.setLyrics(null);
                wasActive = true;
            }
        }
        return new DeathResult(lastPid, wasActive);
    }

    Session getOrCreateSession(@NonNull String packageName) {
        synchronized (lock) {
            Session session = sessions.get(packageName);
            if (session != null) return session;
            trimSessionsLocked();
            session = new Session(packageName);
            sessions.put(packageName, session);
            return session;
        }
    }

    boolean isStillPublishing(@NonNull String packageName) {
        synchronized (lock) {
            Session session = sessions.get(packageName);
            return session != null && session.publishingPid != NO_PID;
        }
    }

    // 调用方必须持有 lock；至少保留 activePublisher 与正在播放的会话
    private void trimSessionsLocked() {
        while (sessions.size() >= MAX_CACHED_SESSIONS) {
            String oldestPackage = null;
            long oldestTime = Long.MAX_VALUE;
            for (Session session : sessions.values()) {
                if (session.packageName.equals(activePublisher) || session.isPlaying()) continue;
                if (session.lastActiveTime < oldestTime) {
                    oldestTime = session.lastActiveTime;
                    oldestPackage = session.packageName;
                }
            }
            if (oldestPackage == null) return;
            sessions.remove(oldestPackage);
        }
    }

    /**
     * 单个应用的最新歌词与播放状态
     */
    static final class Session {
        @NonNull
        final String packageName;
        /**
         * 唯一的歌词状态；全量来源含全部行，单行来源只含当前句
         */
        @Nullable
        volatile Lyric lyric;
        /**
         * lyric 是否包含全部行；单行来源为 false
         */
        volatile boolean fullLyric;
        /**
         * 单行来源的当前位置；全量来源以 tracker 为准
         */
        volatile long positionMs;
        @Nullable
        volatile PlaybackState playbackState;
        /**
         * 系统 MediaSession 的曲名与时长，仅用于入站校验
         *
         * <p>与歌词文档分开放：它们是"系统所说的曲目"，不是"发布者所说的曲目"。
         */
        final MediaFacts mediaFacts = new MediaFacts();
        volatile long lastActiveTime;
        volatile int publishingPid = NO_PID;
        volatile boolean published;

        Session(@NonNull String packageName) {
            this.packageName = packageName;
            this.lastActiveTime = SystemClock.elapsedRealtime();
        }

        boolean isPlaying() {
            PlaybackState state = playbackState;
            return state != null && state.getState() == PlaybackState.STATE_PLAYING;
        }

        void touch() {
            lastActiveTime = SystemClock.elapsedRealtime();
        }

        void clear() {
            lyric = null;
            fullLyric = false;
            positionMs = 0L;
            playbackState = null;
        }
    }
}
