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
import android.os.RemoteCallbackList;

import androidx.annotation.NonNull;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyricapi.ISuperLyricReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * 接收者注册、配额与带超时的广播
 *
 * <p>每个接收者记录其 3.x API 版本能力：低于 3.5 的客户端只接收当前句，较新的客户端
 * 接收全量载荷。全局上限 64 个、每 uid 上限 16 个，单次回调超时 500ms。
 * 与 FrameBroadcaster 的配额/驱逐逻辑平行维护；模型与线上格式不同，暂不抽公共类。
 */
final class ReceiverBroadcaster {

    private static final String TAG = "SuperLyric-Receiver";
    private static final long CALL_TIMEOUT_MS = 500L;
    private static final int MAX_RECEIVERS = 64;
    private static final int MAX_RECEIVERS_PER_UID = 16;
    /** 连续超时达到该次数后视为慢链并移除，避免每次广播都白付一次超时。 */
    private static final int MAX_CONSECUTIVE_TIMEOUTS = 3;

    private static final class Entry {
        final ISuperLyricReceiver receiver;
        final int uid;
        final boolean supportsFullLyric;
        final AtomicInteger consecutiveTimeouts = new AtomicInteger();

        Entry(@NonNull ISuperLyricReceiver receiver, int uid, boolean supportsFullLyric) {
            this.receiver = receiver;
            this.uid = uid;
            this.supportsFullLyric = supportsFullLyric;
        }
    }

    // 三个集合必须同步维护：注销与 binder 死亡都要回退 uid 配额
    private final Object registrationLock = new Object();
    private final ConcurrentHashMap<Integer, Integer> uidCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IBinder, Entry> entries = new ConcurrentHashMap<>();
    private final Set<IBinder> binders = ConcurrentHashMap.newKeySet();

    private final RemoteCallbackList<ISuperLyricReceiver> callbacks;

    ReceiverBroadcaster() {
        this.callbacks = new RemoteCallbackList<>() {
            @Override
            public void onCallbackDied(ISuperLyricReceiver callback) {
                super.onCallbackDied(callback);
                handleCallbackDied(callback);
            }
        };
    }

    /** 测试可注入 callback 列表；生产默认构造器保留真实的死亡回调。 */
    ReceiverBroadcaster(@NonNull RemoteCallbackList<ISuperLyricReceiver> callbacks) {
        this.callbacks = callbacks;
    }

    private void handleCallbackDied(@NonNull ISuperLyricReceiver callback) {
        IBinder binder = callback.asBinder();
        synchronized (registrationLock) {
            binders.remove(binder);
            Entry removed = entries.remove(binder);
            if (removed != null) decrement(removed.uid);
        }
        XposedLog.logI(TAG, "Receiver died: " + callback + ", binder: " + binder);
    }

    private final ExecutorService callExecutor = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
        Thread thread = new Thread(runnable, "SuperLyric-ReceiverCall");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.CallerRunsPolicy());

    @FunctionalInterface
    interface Invocation {
        void invoke(@NonNull ISuperLyricReceiver receiver, boolean supportsFullLyric) throws Throwable;
    }

    void register(@NonNull ISuperLyricReceiver receiver, int uid, int pid, @NonNull String pkg,
                  boolean supportsFullLyric) {
        synchronized (registrationLock) {
            if (callbacks.getRegisteredCallbackCount() >= MAX_RECEIVERS) {
                XposedLog.logW(TAG, "Refusing receiver: over limit " + MAX_RECEIVERS);
                return;
            }
            if (uidCounts.getOrDefault(uid, 0) >= MAX_RECEIVERS_PER_UID) {
                XposedLog.logW(TAG, "Refusing receiver: uid " + uid + " over per-uid limit");
                return;
            }

            IBinder binder = receiver.asBinder();
            Entry entry = new Entry(receiver, uid, supportsFullLyric);
            if (binders.contains(binder)) {
                // RemoteCallbackList 的重复注册会替换条目但不增加计数；保留首次的配额记账。
                entries.put(binder, entry);
                return;
            }
            // 先写能力表与配额，再注册；unregister 在同一临界区，不会出现
            // “注销先扣减、注册后补计”的窄窗口。
            entries.put(binder, entry);
            binders.add(binder);
            uidCounts.merge(uid, 1, Integer::sum);
            if (!callbacks.register(receiver)) {
                entries.remove(binder, entry);
                binders.remove(binder);
                decrement(uid);
                XposedLog.logW(TAG, "Failed to register receiver: " + receiver + ", pkg: " + pkg
                    + ", pid: " + pid + ", uid: " + uid);
                return;
            }
            XposedLog.logI(TAG, "Register receiver: " + receiver + ", pkg: " + pkg
                + ", pid: " + pid + ", uid: " + uid + ", fullLyric: " + supportsFullLyric
                + ", binder: " + binder);
        }
    }

    void unregister(@NonNull ISuperLyricReceiver receiver, int pid, @NonNull String pkg) {
        synchronized (registrationLock) {
            IBinder binder = receiver.asBinder();
            if (callbacks.unregister(receiver)) {
                binders.remove(binder);
                Entry removed = entries.remove(binder);
                if (removed != null) decrement(removed.uid);
                XposedLog.logI(TAG, "Unregister receiver: " + receiver + ", pkg: " + pkg
                    + ", pid: " + pid + ", binder: " + binder);
            }
        }
    }

    boolean isRegistered(@NonNull ISuperLyricReceiver receiver) {
        return binders.contains(receiver.asBinder());
    }

    // beginBroadcast 与 finishBroadcast 必须同线程配对；回调在固定线程池中并发执行
    void broadcast(@NonNull String action, @NonNull Invocation invocation) {
        int itemCount = callbacks.beginBroadcast();
        List<ISuperLyricReceiver> dead = null;
        try {
            for (int i = 0; i < itemCount; i++) {
                ISuperLyricReceiver receiver = callbacks.getBroadcastItem(i);
                if (receiver == null) continue;
                Entry entry = entries.get(receiver.asBinder());
                boolean supportsFullLyric = entry != null && entry.supportsFullLyric;
                try {
                    Future<?> future = callExecutor.submit(() -> {
                        try {
                            invocation.invoke(receiver, supportsFullLyric);
                        } catch (Throwable t) {
                            throw new RuntimeException(t);
                        }
                        return null;
                    });
                    try {
                        future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        if (entry != null) entry.consecutiveTimeouts.set(0);
                    } catch (TimeoutException e) {
                        future.cancel(true);
                        int timeouts = entry != null
                            ? entry.consecutiveTimeouts.incrementAndGet() : 1;
                        XposedLog.logW(TAG, "Receiver call timed out (" + timeouts + "/"
                            + MAX_CONSECUTIVE_TIMEOUTS + ") during " + action
                            + ": " + receiver.asBinder());
                        if (timeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                            if (dead == null) dead = new ArrayList<>();
                            dead.add(receiver);
                        }
                    } catch (InterruptedException e) {
                        future.cancel(true);
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        throw cause;
                    }
                } catch (Throwable t) {
                    boolean isDead = (t instanceof DeadObjectException)
                        || !receiver.asBinder().isBinderAlive()
                        || (t.getMessage() != null && t.getMessage().contains("remote process probably died"));
                    if (isDead) {
                        if (dead == null) dead = new ArrayList<>();
                        dead.add(receiver);
                        XposedLog.logD(TAG, "Receiver is dead during " + action + ": " + receiver.asBinder());
                    } else {
                        XposedLog.logD(TAG, "Failed to deliver " + action + ": " + t.getMessage());
                    }
                }
            }
        } finally {
            try {
                callbacks.finishBroadcast();
            } catch (Throwable t) {
                XposedLog.logE(TAG, "finishBroadcast failed during " + action, t);
            }
            if (dead != null) {
                for (ISuperLyricReceiver receiver : dead) {
                    IBinder binder = receiver.asBinder();
                    // 与 register/unregister/onCallbackDied 共用 registrationLock：
                    // unregister 与状态删除必须原子，否则并发 register 可能被误删并重复扣配额。
                    synchronized (registrationLock) {
                        callbacks.unregister(receiver);
                        binders.remove(binder);
                        Entry removed = entries.remove(binder);
                        if (removed != null) decrement(removed.uid);
                    }
                    XposedLog.logI(TAG, "Unregistered dead receiver: " + binder);
                }
            }
        }
    }

    private void decrement(int uid) {
        uidCounts.computeIfPresent(uid, (key, count) -> count <= 1 ? null : count - 1);
    }
}
