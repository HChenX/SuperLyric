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
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.data.SupportApps;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发布者进程、世代与按包会话的状态仓库。
 */
final class PublisherRegistry {

    static final int NO_PID = 0;

    static final ConcurrentHashMap<String, Set<Integer>> pids = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Long> generations = new ConcurrentHashMap<>();
    static final AtomicLong generationSequence = new AtomicLong();
    static final Set<String> disabledPackages = ConcurrentHashMap.newKeySet();

    private static final int MAX_CACHED_SESSIONS = 5;

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

    static boolean registerPublisher(@NonNull String packageName, int pid, int uid) {
        if (uid < Process.FIRST_APPLICATION_UID) {
            XposedLog.logW("SuperLyricService", "Registration as publisher rejected! Non-app caller, uid: "
                + uid + ", pid: " + pid);
            return false;
        }
        if (!SupportApps.sMediaAppPackages.contains(packageName)) {
            XposedLog.logW("SuperLyricService",
                "Registration as publisher rejected! Unsupported package: " + packageName);
            return false;
        }
        register(packageName, pid);
        XposedLog.logI("SuperLyricService", "Register publisher: " + packageName + ", pid: " + pid);
        return true;
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
     * 单个应用的最新歌词与播放状态。
     */
    static final class Session {
        @NonNull
        final String packageName;
        @Nullable
        volatile SuperLyricData latestLyric;
        @Nullable
        volatile PlaybackState playbackState;
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
            latestLyric = null;
            playbackState = null;
        }
    }
}
