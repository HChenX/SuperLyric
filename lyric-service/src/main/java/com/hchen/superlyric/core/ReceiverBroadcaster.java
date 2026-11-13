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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 3.x 接收者注册与带超时的广播
 *
 * <p>每个接收者记录其 3.x API 版本能力：低于 3.5 的客户端只接收当前句，较新的客户端
 * 接收全量载荷。配额、超时与驱逐规则见 {@link SubscriberBudget}。
 */
final class ReceiverBroadcaster {

    private static final String TAG = "SuperLyric-Receiver";

    private static final class Entry {
        final ISuperLyricReceiver receiver;
        final boolean supportsFullLyric;

        Entry(@NonNull ISuperLyricReceiver receiver, boolean supportsFullLyric) {
            this.receiver = receiver;
            this.supportsFullLyric = supportsFullLyric;
        }
    }

    private final SubscriberBudget budget = new SubscriberBudget();
    // 能力表、配额与 callbacks 的登记/注销必须在同一临界区；广播只读能力表
    private final Object registrationLock = new Object();
    private final ConcurrentHashMap<IBinder, Entry> entries = new ConcurrentHashMap<>();

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
            entries.remove(binder);
            budget.release(binder);
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
            IBinder binder = receiver.asBinder();
            SubscriberBudget.Admission admission = budget.admit(binder, uid);
            if (admission == SubscriberBudget.Admission.AT_GLOBAL_LIMIT) {
                XposedLog.logW(TAG, "Refusing receiver: over limit "
                    + SubscriberBudget.MAX_SUBSCRIBERS);
                return;
            }
            if (admission == SubscriberBudget.Admission.AT_UID_LIMIT) {
                XposedLog.logW(TAG, "Refusing receiver: uid " + uid + " over per-uid limit");
                return;
            }

            Entry entry = new Entry(receiver, supportsFullLyric);
            if (admission == SubscriberBudget.Admission.DUPLICATE) {
                // RemoteCallbackList 的重复注册会替换条目但不增加计数；保留首次的配额记账。
                entries.put(binder, entry);
                return;
            }
            entries.put(binder, entry);
            if (!callbacks.register(receiver)) {
                entries.remove(binder, entry);
                budget.release(binder);
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
                entries.remove(binder);
                budget.release(binder);
                XposedLog.logI(TAG, "Unregister receiver: " + receiver + ", pkg: " + pkg
                    + ", pid: " + pid + ", binder: " + binder);
            }
        }
    }

    boolean isRegistered(@NonNull ISuperLyricReceiver receiver) {
        return entries.containsKey(receiver.asBinder());
    }

    // beginBroadcast 与 finishBroadcast 必须同线程配对；回调在固定线程池中并发执行
    void broadcast(@NonNull String action, @NonNull Invocation invocation) {
        int itemCount = callbacks.beginBroadcast();
        List<ISuperLyricReceiver> dead = null;
        try {
            for (int i = 0; i < itemCount; i++) {
                ISuperLyricReceiver receiver = callbacks.getBroadcastItem(i);
                if (receiver == null) continue;
                IBinder binder = receiver.asBinder();
                Entry entry = entries.get(binder);
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
                        future.get(SubscriberBudget.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        budget.recordSuccess(binder);
                    } catch (TimeoutException e) {
                        future.cancel(true);
                        int timeouts = budget.recordTimeout(binder);
                        XposedLog.logW(TAG, "Receiver call timed out (" + timeouts + "/"
                            + SubscriberBudget.MAX_CONSECUTIVE_TIMEOUTS + ") during " + action
                            + ": " + binder);
                        if (timeouts >= SubscriberBudget.MAX_CONSECUTIVE_TIMEOUTS) {
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
                        || !binder.isBinderAlive()
                        || (t.getMessage() != null && t.getMessage().contains("remote process probably died"));
                    if (isDead) {
                        if (dead == null) dead = new ArrayList<>();
                        dead.add(receiver);
                        XposedLog.logD(TAG, "Receiver is dead during " + action + ": " + binder);
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
                        entries.remove(binder);
                        budget.release(binder);
                    }
                    XposedLog.logI(TAG, "Unregistered dead receiver: " + binder);
                }
            }
        }
    }
}
