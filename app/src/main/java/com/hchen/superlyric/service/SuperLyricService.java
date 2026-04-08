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

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyricapi.ISuperLyricManager;
import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
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
            AndroidLog.logW(TAG, "Receiver died: " + callbackInterface + ", binder: " + callbackInterface.asBinder());
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
    public static final CopyOnWriteArraySet<String> mPublishers = new CopyOnWriteArraySet<>();
    public static final CopyOnWriteArraySet<String> mNonSystemPlayStateListeners = new CopyOnWriteArraySet<>();

    public SuperLyricService(@NonNull Object ams) {
        this.mAms = ams;
    }

    @Override
    public void registerPublisher() throws RemoteException {
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        if (!packageName.isEmpty()) {
            mPublishers.add(packageName);
        }
        AndroidLog.logI(TAG, "Register publisher: " + packageName + ", pid: " + pid);
    }

    @Override
    public void unregisterPublisher() throws RemoteException {
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        mPublishers.remove(packageName);
        AndroidLog.logI(TAG, "Unregister publisher: " + packageName + ", pid: " + pid);
    }

    @Override
    public boolean isPublisherRegistered() throws RemoteException {
        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        return mPublishers.contains(packageName);
    }

    @Override
    public void sendLyric(SuperLyricData data) throws RemoteException {
        if (data == null) {
            return;
        }

        int pid = Binder.getCallingPid();
        String packageName = getPackageNameWithPid(pid);
        notifyReceiver(packageName, data, new IReceiverCallBack() {
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
        notifyReceiver(packageName, data, new IReceiverCallBack() {
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
        if (mPublishers.contains(packageName)) {
            if (enabled) {
                mNonSystemPlayStateListeners.add(packageName);
            } else {
                mNonSystemPlayStateListeners.remove(packageName);
            }

            AndroidLog.logI(TAG, "System play state listener enabled: " + enabled + ", caller: " + packageName);
        }
    }

    @Override
    public void registerReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver != null) {
            mCallbacks.register(receiver);
            mReceiverBinders.add(receiver.asBinder());

            AndroidLog.logI(TAG, "Register receiver: " + receiver + ", binder: " + receiver.asBinder());
        }
    }

    @Override
    public void unregisterReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver != null) {
            mCallbacks.unregister(receiver);
            mReceiverBinders.remove(receiver.asBinder());

            AndroidLog.logI(TAG, "Unregister receiver: " + receiver + ", binder: " + receiver.asBinder());
        }
    }

    @Override
    public boolean isReceiverRegistered(ISuperLyricReceiver receiver) throws RemoteException {
        return receiver != null && mReceiverBinders.contains(receiver.asBinder());
    }

    public void onPackageDied(@NonNull String packageName) {
        notifyReceiver(packageName, new SuperLyricData(), new IReceiverCallBack() {
            @Override
            public void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException {
                receiver.onStop(publisher, data);
            }
        });

        mPublishers.remove(packageName);
        mNonSystemPlayStateListeners.remove(packageName);
    }

    public void sendSystemEvent(String packageName, SuperLyricData data) {
        notifyReceiver(packageName, data, new IReceiverCallBack() {
            @Override
            public void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException {
                receiver.onStop(publisher, data);
            }
        });
    }

    private void notifyReceiver(String publisher, SuperLyricData data, IReceiverCallBack callBack) {
        if (mPublishers.contains(publisher)) {
            mBroadcastExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    int itemCount = mCallbacks.beginBroadcast();
                    try {
                        for (int i = 0; i < itemCount; i++) {
                            try {
                                callBack.call(mCallbacks.getBroadcastItem(i), publisher, data);
                            } catch (RemoteException e) {
                                AndroidLog.logW(TAG, "[sendStop()] RemoteException!!", e);
                            }
                        }
                    } finally {
                        mCallbacks.finishBroadcast();
                    }
                }
            });
        }
    }

    private String getPackageNameWithPid(int pid) {
        Object pidMap = CoreTool.getField(mAms, "mPidsSelfLocked");
        if (pidMap != null) {
            Object record = null;
            synchronized (pidMap) {
                record = CoreTool.callMethod(pidMap, "get", pid);
            }
            if (record != null) {
                ApplicationInfo info = (ApplicationInfo) CoreTool.getField(record, "info");
                if (info != null) {
                    return info.packageName;
                }
            }
        }
        return "";
    }

    private interface IReceiverCallBack {
        void call(ISuperLyricReceiver receiver, String publisher, SuperLyricData data) throws RemoteException;
    }
}
