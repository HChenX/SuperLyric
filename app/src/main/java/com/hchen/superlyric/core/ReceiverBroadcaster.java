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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 接收者注册、配额与带超时的广播。
 */
final class ReceiverBroadcaster {

    private static final String TAG = "SuperLyric-Receiver";
    private static final long CALL_TIMEOUT_MS = 500L;
    private static final int MAX_RECEIVERS = 64;
    private static final int MAX_RECEIVERS_PER_UID = 16;

    private final ConcurrentHashMap<Integer, Integer> uidCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IBinder, Integer> binderUids = new ConcurrentHashMap<>();
    private final java.util.Set<IBinder> binders = ConcurrentHashMap.newKeySet();

    private final RemoteCallbackList<ISuperLyricReceiver> callbacks = new RemoteCallbackList<>() {
        @Override
        public void onCallbackDied(ISuperLyricReceiver callback) {
            super.onCallbackDied(callback);
            IBinder binder = callback.asBinder();
            binders.remove(binder);
            Integer uid = binderUids.remove(binder);
            if (uid != null) decrement(uid);
            XposedLog.logI(TAG, "Receiver died: " + callback + ", binder: " + binder);
        }
    };

    private final ExecutorService callExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "SuperLyric-ReceiverCall");
        thread.setDaemon(true);
        return thread;
    });

    @FunctionalInterface
    interface Invocation {
        void invoke(@NonNull ISuperLyricReceiver receiver) throws Throwable;
    }

    void register(@NonNull ISuperLyricReceiver receiver, int uid, int pid, @NonNull String pkg) {
        if (callbacks.getRegisteredCallbackCount() >= MAX_RECEIVERS) {
            XposedLog.logW(TAG, "Refusing receiver: over limit " + MAX_RECEIVERS);
            return;
        }
        if (uidCounts.getOrDefault(uid, 0) >= MAX_RECEIVERS_PER_UID) {
            XposedLog.logW(TAG, "Refusing receiver: uid " + uid + " over per-uid limit");
            return;
        }
        if (callbacks.register(receiver)) {
            binders.add(receiver.asBinder());
            binderUids.put(receiver.asBinder(), uid);
            uidCounts.merge(uid, 1, Integer::sum);
            XposedLog.logI(TAG, "Register receiver: " + receiver + ", pkg: " + pkg
                + ", pid: " + pid + ", uid: " + uid + ", binder: " + receiver.asBinder());
        }
    }

    void unregister(@NonNull ISuperLyricReceiver receiver, int pid, @NonNull String pkg) {
        IBinder binder = receiver.asBinder();
        if (callbacks.unregister(receiver)) {
            binders.remove(binder);
            Integer uid = binderUids.remove(binder);
            if (uid != null) decrement(uid);
            XposedLog.logI(TAG, "Unregister receiver: " + receiver + ", pkg: " + pkg
                + ", pid: " + pid + ", binder: " + binder);
        }
    }

    boolean isRegistered(@NonNull ISuperLyricReceiver receiver) {
        return binders.contains(receiver.asBinder());
    }

    void broadcast(@NonNull String action, @NonNull Invocation invocation) {
        int itemCount = callbacks.beginBroadcast();
        List<ISuperLyricReceiver> dead = null;
        try {
            for (int i = 0; i < itemCount; i++) {
                ISuperLyricReceiver receiver = callbacks.getBroadcastItem(i);
                if (receiver == null) continue;
                try {
                    Future<?> future = callExecutor.submit(() -> {
                        try {
                            invocation.invoke(receiver);
                        } catch (Throwable t) {
                            throw new RuntimeException(t);
                        }
                        return null;
                    });
                    try {
                        future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        future.cancel(true);
                        XposedLog.logW(TAG, "Receiver call timed out during " + action
                            + ": " + receiver.asBinder());
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
                    callbacks.unregister(receiver);
                    IBinder binder = receiver.asBinder();
                    binders.remove(binder);
                    Integer uid = binderUids.remove(binder);
                    if (uid != null) decrement(uid);
                    XposedLog.logI(TAG, "Unregistered dead receiver: " + binder);
                }
            }
        }
    }

    private void decrement(int uid) {
        uidCounts.computeIfPresent(uid, (key, count) -> count <= 1 ? null : count - 1);
    }
}
