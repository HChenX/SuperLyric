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

import android.os.DeadObjectException;
import android.os.IBinder;

import androidx.annotation.NonNull;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyricapi.v4.ILyricFrameListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v4 帧订阅者的注册、配额与带超时广播
 *
 * <p>与 {@link ReceiverBroadcaster} 分开维护：两代订阅者使用不同的回调接口与线上格式
 * （v4 帧 / 3.x Parcelable），一边的死链或慢链不得影响另一边。全局上限 64 个，
 * 每 uid 上限 16 个，单次回调超时 500ms。
 */
final class FrameBroadcaster {
    private static final String TAG = "SuperLyric-Frames";
    private static final long CALL_TIMEOUT_MS = 500L;
    private static final int MAX_LISTENERS = 64;
    private static final int MAX_LISTENERS_PER_UID = 16;
    /** 连续超时达到该次数后视为慢链并移除，避免每次广播都白付一次超时。 */
    private static final int MAX_CONSECUTIVE_TIMEOUTS = 3;

    /**
     * 与 {@code Receiver.RegisterResult} 的线上取值一致
     */
    static final int REGISTER_OK = 0;
    static final int REGISTER_REJECTED = 2;
    static final int REGISTER_QUOTA = 3;

    /** 配额检查、uidCounts、listeners 与死亡回调共用；broadcast 只负责快照/投递。 */
    private final Object registrationLock = new Object();
    private final ConcurrentHashMap<Integer, Integer> uidCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IBinder, Entry> listeners = new ConcurrentHashMap<>();

    private final ExecutorService callExecutor = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
        Thread thread = new Thread(runnable, "SuperLyric-FrameCall");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.CallerRunsPolicy());

    private static final class Entry {
        final ILyricFrameListener listener;
        final int uid;
        final IBinder.DeathRecipient deathRecipient;
        final AtomicInteger consecutiveTimeouts = new AtomicInteger();

        Entry(ILyricFrameListener listener, int uid, IBinder.DeathRecipient deathRecipient) {
            this.listener = listener;
            this.uid = uid;
            this.deathRecipient = deathRecipient;
        }
    }

    /**
     * 注册监听器
     *
     * @return {@link #REGISTER_OK}、{@link #REGISTER_REJECTED} 或 {@link #REGISTER_QUOTA}
     */
    int register(@NonNull ILyricFrameListener listener, int uid, int pid, @NonNull String pkg) {
        synchronized (registrationLock) {
            if (listeners.size() >= MAX_LISTENERS) {
                XposedLog.logW(TAG, "Refusing listener: over limit " + MAX_LISTENERS);
                return REGISTER_QUOTA;
            }
            if (uidCounts.getOrDefault(uid, 0) >= MAX_LISTENERS_PER_UID) {
                XposedLog.logW(TAG, "Refusing listener: uid " + uid + " over per-uid limit");
                return REGISTER_QUOTA;
            }

            IBinder binder = listener.asBinder();

            // 先记账再挂死亡回调：死亡回调可能在 put 与后续步骤之间抢跑，先记账才能配对
            uidCounts.merge(uid, 1, Integer::sum);

            IBinder.DeathRecipient deathRecipient = () -> {
                synchronized (registrationLock) {
                    Entry removed = listeners.remove(binder);
                    if (removed != null) decrement(removed.uid);
                }
                XposedLog.logI(TAG, "Listener died: " + binder);
            };
            Entry entry = new Entry(listener, uid, deathRecipient);
            if (listeners.putIfAbsent(binder, entry) != null) {
                decrement(uid);
                // 同一 binder 重复注册按成功处理：它已经在收帧
                return REGISTER_OK;
            }
            try {
                binder.linkToDeath(deathRecipient, 0);
            } catch (Throwable t) {
                if (listeners.remove(binder, entry)) {
                    decrement(uid);
                }
                XposedLog.logW(TAG, "Cannot link listener death: " + binder);
                return REGISTER_REJECTED;
            }
            XposedLog.logI(TAG, "Register listener: " + binder
                + ", pkg: " + pkg + ", pid: " + pid + ", uid: " + uid);
            return REGISTER_OK;
        }
    }

    void unregister(@NonNull ILyricFrameListener listener, int pid, @NonNull String pkg) {
        IBinder binder = listener.asBinder();
        synchronized (registrationLock) {
            Entry entry = listeners.remove(binder);
            if (entry == null) return;

            try {
                binder.unlinkToDeath(entry.deathRecipient, 0);
            } catch (Throwable ignored) {
            }
            decrement(entry.uid);
        }
        XposedLog.logI(TAG, "Unregister listener: " + binder
            + ", pkg: " + pkg + ", pid: " + pid);
    }

    void broadcast(@NonNull byte[] frame) {
        if (listeners.isEmpty()) return;

        List<Entry> snapshot = new ArrayList<>(listeners.values());
        List<IBinder> dead = null;
        for (Entry entry : snapshot) {
            IBinder binder = entry.listener.asBinder();
            if (!listeners.containsKey(binder)) continue;
            if (!call(entry, frame)) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(binder);
            }
        }

        if (dead != null) {
            for (IBinder binder : dead) {
                synchronized (registrationLock) {
                    Entry removed = listeners.remove(binder);
                    if (removed != null) {
                        try {
                            binder.unlinkToDeath(removed.deathRecipient, 0);
                        } catch (Throwable ignored) {
                        }
                        decrement(removed.uid);
                    }
                }
                XposedLog.logI(TAG, "Unregistered dead listener: " + binder);
            }
        }
    }

    /**
     * 向单个监听器投递一帧
     *
     * <p>必须在广播线程上调用，否则会与 {@link #broadcast} 的排队顺序交错。
     */
    void deliver(@NonNull ILyricFrameListener listener, @NonNull byte[] frame) {
        Entry entry = listeners.get(listener.asBinder());
        if (entry == null) return;

        if (!call(entry, frame)) {
            // 只在实际移除成功时扣减：死亡回调或 unregister 可能已经把它摘掉了
            synchronized (registrationLock) {
                if (listeners.remove(listener.asBinder(), entry)) {
                    decrement(entry.uid);
                }
            }
        }
    }

    /**
     * @return false 表示 binder 已死，调用方应回收该订阅者
     */
    private boolean call(@NonNull Entry entry, @NonNull byte[] frame) {
        try {
            Future<?> future = callExecutor.submit(() -> {
                try {
                    entry.listener.onFrame(frame);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
                return null;
            });
            try {
                future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                entry.consecutiveTimeouts.set(0);
                return true;
            } catch (TimeoutException e) {
                future.cancel(true);
                int timeouts = entry.consecutiveTimeouts.incrementAndGet();
                XposedLog.logW(TAG, "Listener call timed out (" + timeouts + "/"
                    + MAX_CONSECUTIVE_TIMEOUTS + "): " + entry.listener.asBinder());
                return timeouts < MAX_CONSECUTIVE_TIMEOUTS;
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return true;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                boolean isDead = (cause instanceof DeadObjectException)
                    || !entry.listener.asBinder().isBinderAlive()
                    || (cause.getMessage() != null
                    && cause.getMessage().contains("remote process probably died"));
                if (isDead) {
                    XposedLog.logD(TAG, "Listener is dead: " + entry.listener.asBinder());
                    return false;
                }
                XposedLog.logD(TAG, "Failed to deliver frame: " + cause);
                return true;
            }
        } catch (Throwable t) {
            XposedLog.logD(TAG, "Failed to schedule frame: " + t.getMessage());
            return true;
        }
    }

    private void decrement(int uid) {
        uidCounts.computeIfPresent(uid, (key, count) -> count <= 1 ? null : count - 1);
    }
}
