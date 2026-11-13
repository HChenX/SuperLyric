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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * v4 帧订阅者的注册与带超时广播
 *
 * <p>与 {@link ReceiverBroadcaster} 分开维护：两代订阅者使用不同的回调接口与线上格式
 * （v4 帧 / 3.x Parcelable），一边的死链或慢链不得影响另一边。配额、超时与驱逐规则见
 * {@link SubscriberBudget}。
 */
final class FrameBroadcaster {
    private static final String TAG = "SuperLyric-Frames";

    /**
     * 与 {@code Receiver.RegisterResult} 的线上取值一致
     */
    static final int REGISTER_OK = 0;
    static final int REGISTER_REJECTED = 2;
    static final int REGISTER_QUOTA = 3;

    private static final class Entry {
        final ILyricFrameListener listener;
        final IBinder.DeathRecipient deathRecipient;

        Entry(ILyricFrameListener listener, IBinder.DeathRecipient deathRecipient) {
            this.listener = listener;
            this.deathRecipient = deathRecipient;
        }
    }

    private final SubscriberBudget budget = new SubscriberBudget();
    /** 配额、listeners 与死亡回调共用；broadcast 只负责快照/投递。 */
    private final Object registrationLock = new Object();
    private final ConcurrentHashMap<IBinder, Entry> listeners = new ConcurrentHashMap<>();

    private final ExecutorService callExecutor = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
        Thread thread = new Thread(runnable, "SuperLyric-FrameCall");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 注册监听器
     *
     * @return {@link #REGISTER_OK}、{@link #REGISTER_REJECTED} 或 {@link #REGISTER_QUOTA}
     */
    int register(@NonNull ILyricFrameListener listener, int uid, int pid, @NonNull String pkg) {
        IBinder binder = listener.asBinder();
        Entry entry;
        synchronized (registrationLock) {
            SubscriberBudget.Admission admission = budget.admit(binder, uid);
            if (admission == SubscriberBudget.Admission.AT_GLOBAL_LIMIT) {
                XposedLog.logW(TAG, "Refusing listener: over limit "
                    + SubscriberBudget.MAX_SUBSCRIBERS);
                return REGISTER_QUOTA;
            }
            if (admission == SubscriberBudget.Admission.AT_UID_LIMIT) {
                XposedLog.logW(TAG, "Refusing listener: uid " + uid + " over per-uid limit");
                return REGISTER_QUOTA;
            }
            if (admission == SubscriberBudget.Admission.DUPLICATE) {
                // 同一 binder 重复注册按成功处理：它已经在收帧
                return REGISTER_OK;
            }

            // 先记账再挂死亡回调：死亡回调可能在后续步骤之间抢跑，先记账才能配对
            IBinder.DeathRecipient deathRecipient = () -> {
                synchronized (registrationLock) {
                    if (listeners.remove(binder) != null) budget.release(binder);
                }
                XposedLog.logI(TAG, "Listener died: " + binder);
            };
            entry = new Entry(listener, deathRecipient);
            listeners.put(binder, entry);
        }

        // entry 已在 listeners 中，配额把它算作已登记；linkToDeath 不持锁，避免卡住
        // 其它注册/广播。死亡、注销或广播若抢先移除，最终检查会返回 REJECTED。
        try {
            binder.linkToDeath(entry.deathRecipient, 0);
        } catch (Throwable t) {
            synchronized (registrationLock) {
                if (listeners.remove(binder, entry)) budget.release(binder);
            }
            XposedLog.logW(TAG, "Cannot link listener death: " + binder);
            return REGISTER_REJECTED;
        }

        boolean registered;
        synchronized (registrationLock) {
            registered = listeners.get(binder) == entry;
        }
        if (!registered) {
            // 链接成功但条目已被注销/死亡/广播移除；撤销刚建立的 death 链接。
            try {
                binder.unlinkToDeath(entry.deathRecipient, 0);
            } catch (Throwable ignored) {
            }
            return REGISTER_REJECTED;
        }
        XposedLog.logI(TAG, "Register listener: " + binder
            + ", pkg: " + pkg + ", pid: " + pid + ", uid: " + uid);
        return REGISTER_OK;
    }

    void unregister(@NonNull ILyricFrameListener listener, int pid, @NonNull String pkg) {
        IBinder binder = listener.asBinder();
        Entry entry;
        synchronized (registrationLock) {
            entry = listeners.remove(binder);
            if (entry == null) return;
            budget.release(binder);
        }
        try {
            binder.unlinkToDeath(entry.deathRecipient, 0);
        } catch (Throwable ignored) {
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
                Entry removed;
                synchronized (registrationLock) {
                    removed = listeners.remove(binder);
                    if (removed != null) budget.release(binder);
                }
                if (removed != null) {
                    try {
                        binder.unlinkToDeath(removed.deathRecipient, 0);
                    } catch (Throwable ignored) {
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
        IBinder binder = listener.asBinder();
        Entry entry = listeners.get(binder);
        if (entry == null) return;

        if (!call(entry, frame)) {
            // 只在实际移除成功时回退配额：死亡回调或 unregister 可能已经把它摘掉了
            boolean removed;
            synchronized (registrationLock) {
                removed = listeners.remove(binder, entry);
                if (removed) budget.release(binder);
            }
            if (removed) {
                try {
                    binder.unlinkToDeath(entry.deathRecipient, 0);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * @return false 表示 binder 已死，调用方应回收该订阅者
     */
    private boolean call(@NonNull Entry entry, @NonNull byte[] frame) {
        IBinder binder = entry.listener.asBinder();
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
                future.get(SubscriberBudget.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                budget.recordSuccess(binder);
                return true;
            } catch (TimeoutException e) {
                future.cancel(true);
                int timeouts = budget.recordTimeout(binder);
                XposedLog.logW(TAG, "Listener call timed out (" + timeouts + "/"
                    + SubscriberBudget.MAX_CONSECUTIVE_TIMEOUTS + "): " + binder);
                return timeouts < SubscriberBudget.MAX_CONSECUTIVE_TIMEOUTS;
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
                    || !binder.isBinderAlive()
                    || (cause.getMessage() != null
                    && cause.getMessage().contains("remote process probably died"));
                if (isDead) {
                    XposedLog.logD(TAG, "Listener is dead: " + binder);
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
}
