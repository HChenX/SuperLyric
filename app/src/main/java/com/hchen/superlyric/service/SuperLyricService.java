/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.service;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.hooktool.utils.InvokeTool;
import com.hchen.superlyricapi.ISuperLyricManager;
import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Super Lyric 服务
 *
 * @author 焕晨HChen
 */
public final class SuperLyricService extends ISuperLyricManager.Stub {
    private static final String TAG = "SuperLyricService";
    private final Object mAms;
    private final Set<IBinder> mReceiverBinders = ConcurrentHashMap.newKeySet();
    private final RemoteCallbackList<ISuperLyricReceiver> mCallbacks = new RemoteCallbackList<>() {
        @Override
        public void onCallbackDied(ISuperLyricReceiver callbackInterface) {
            super.onCallbackDied(callbackInterface);
            mReceiverBinders.remove(callbackInterface.asBinder());
            XposedLog.logW(TAG, "Receiver died: " + callbackInterface + ", binder: " + callbackInterface.asBinder());
        }
    };
    private final ExecutorService mBroadcastExecutor = Executors.newSingleThreadExecutor(
        new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "SuperLyric-Broadcaster");
                t.setDaemon(true);
                return t;
            }
        }
    );
    private static final ConcurrentHashMap<String, Set<Integer>> sPublisherPids = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> sPublisherGenerations = new ConcurrentHashMap<>();
    private static final Set<String> sSystemPlayStateListenerDisabledPackages = ConcurrentHashMap.newKeySet();

    public SuperLyricService(@NonNull Object ams) {
        this.mAms = ams;
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
        // 仅允许第三方应用注册 publisher，拒绝系统/共享 uid 进程，防止伪造发布者广播垃圾歌词
        if (uid < android.os.Process.FIRST_APPLICATION_UID) {
            XposedLog.logW(TAG, "Registration as publisher rejected! Non-app caller, uid: " + uid + ", pid: " + pid);
            return;
        }
        String packageName = getPackageNameWithPid(pid);
        if (!packageName.isEmpty()) {
            Set<Integer> pids = sPublisherPids.computeIfAbsent(packageName, ignored -> ConcurrentHashMap.newKeySet());
            if (pids.add(pid)) {
                sPublisherGenerations.merge(packageName, 1L, Long::sum);
            }
            XposedLog.logI(TAG, "Register publisher: " + packageName + ", pid: " + pid);
        } else {
            XposedLog.logW(TAG, "Registration as publisher failed! Unable to obtain the package name corresponding to pid: '" + pid + "'!");
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
            XposedLog.logW(TAG, "Failed to unregister as publisher! Unable to obtain the package name corresponding to pid: '" + pid + "'!");
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
        if (data == null) {
            return;
        }

        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (!isPublisherProcess(packageName, pid)) {
            return;
        }
        notifyReceiver(packageName, data, new IReceiverCallback() {
            @Override
            public void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException {
                receiver.onLyric(publisher, data);
            }
        });
    }

    @Override
    public void sendStop(SuperLyricData data) throws RemoteException {
        if (data == null) {
            return;
        }

        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (!isPublisherProcess(packageName, pid)) {
            return;
        }
        notifyReceiver(packageName, data, new IReceiverCallback() {
            @Override
            public void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException {
                receiver.onStop(publisher, data);
            }
        });
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

            XposedLog.logI(TAG, "System play state listener: isEnabled?" + enabled + ", caller: " + packageName);
        }
    }

    @Override
    public void registerReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver != null) {
            mCallbacks.register(receiver);
            mReceiverBinders.add(receiver.asBinder());

            XposedLog.logI(TAG, "Register receiver: " + receiver + ", binder: " + receiver.asBinder());
        }
    }

    @Override
    public void unregisterReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver != null) {
            mCallbacks.unregister(receiver);
            mReceiverBinders.remove(receiver.asBinder());

            XposedLog.logI(TAG, "Unregister receiver: " + receiver + ", binder: " + receiver.asBinder());
        }
    }

    @Override
    public boolean isReceiverRegistered(ISuperLyricReceiver receiver) throws RemoteException {
        return receiver != null && mReceiverBinders.contains(receiver.asBinder());
    }

    public void onProcessDied(@NonNull String packageName, int pid) {
        Set<Integer> pids = sPublisherPids.get(packageName);
        if (pids == null || !pids.remove(pid)) {
            return;
        }
        if (!pids.isEmpty()) {
            return;
        }
        sPublisherPids.remove(packageName, pids);
        long generation = sPublisherGenerations.getOrDefault(packageName, 0L);

        mBroadcastExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (isPublisher(packageName)
                    || sPublisherGenerations.getOrDefault(packageName, 0L) != generation) {
                    return;
                }

                int itemCount = mCallbacks.beginBroadcast();
                try {
                    for (int i = 0; i < itemCount; i++) {
                        try {
                            ISuperLyricReceiver receiver = mCallbacks.getBroadcastItem(i);
                            receiver.onStop(packageName, new SuperLyricData());
                        } catch (RemoteException e) {
                            XposedLog.logW(TAG, e);
                        }
                    }
                } finally {
                    mCallbacks.finishBroadcast();
                }

                sSystemPlayStateListenerDisabledPackages.remove(packageName);
            }
        });
    }

    public void sendSystemEvent(String packageName, SuperLyricData data) {
        notifyReceiver(packageName, data, new IReceiverCallback() {
            @Override
            public void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException {
                receiver.onStop(publisher, data);
            }
        });
    }

    private void notifyReceiver(String publisher, SuperLyricData data, IReceiverCallback callback) {
        if (isPublisher(publisher)) {
            long generation = sPublisherGenerations.getOrDefault(publisher, 0L);
            mBroadcastExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (!isPublisher(publisher)
                        || sPublisherGenerations.getOrDefault(publisher, 0L) != generation) {
                        return;
                    }

                    int itemCount = mCallbacks.beginBroadcast();
                    try {
                        for (int i = 0; i < itemCount; i++) {
                            try {
                                ISuperLyricReceiver receiver = mCallbacks.getBroadcastItem(i);
                                callback.call(receiver, publisher, data);
                            } catch (RemoteException e) {
                                XposedLog.logW(TAG, e);
                            }
                        }
                    } finally {
                        mCallbacks.finishBroadcast();
                    }
                }
            });
        }
    }

    private static void removePublisherProcess(@NonNull String packageName, int pid) {
        Set<Integer> pids = sPublisherPids.get(packageName);
        if (pids == null) {
            return;
        }
        pids.remove(pid);
        if (pids.isEmpty()) {
            sPublisherPids.remove(packageName, pids);
            sSystemPlayStateListenerDisabledPackages.remove(packageName);
        }
    }

    private String getPackageNameWithPid(int pid) {
        Object pidMap = InvokeTool.getField(mAms, "mPidsSelfLocked");
        if (pidMap != null) {
            Object record = null;
            synchronized (pidMap) {
                record = InvokeTool.callMethod(pidMap, "get", new Class[]{int.class}, pid);
            }
            if (record != null) {
                ApplicationInfo info = (ApplicationInfo) InvokeTool.getField(record, "info");
                if (info != null) {
                    return info.packageName;
                }
            }
        }
        return "";
    }

    private interface IReceiverCallback {
        void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException;
    }
}
