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
package com.hchen.superlyric.binder;

import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.NonNull;

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

    @Override
    public void registerPublisher(String packageName) throws RemoteException {
        if (packageName != null) {
            mPublishers.add(packageName);
        }
    }

    @Override
    public void unregisterPublisher(String packageName) throws RemoteException {
        if (packageName != null) {
            mPublishers.remove(packageName);
        }
    }

    @Override
    public boolean isPublisherRegistered(String packageName) throws RemoteException {
        return packageName != null && mPublishers.contains(packageName);
    }

    @Override
    public void sendLyric(SuperLyricData data) throws RemoteException {
        if (data == null) {
            return;
        }

        mBroadcastExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int itemCount = mCallbacks.beginBroadcast();
                try {
                    for (int i = 0; i < itemCount; i++) {
                        try {
                            mCallbacks.getBroadcastItem(i).onLyric(data);
                        } catch (RemoteException e) {
                            AndroidLog.logW(TAG, "[sendLyric()] RemoteException!!", e);
                        }
                    }
                } finally {
                    mCallbacks.finishBroadcast();
                }
            }
        });
    }

    @Override
    public void sendStop(SuperLyricData data) throws RemoteException {
        if (data == null) {
            return;
        }

        mBroadcastExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int itemCount = mCallbacks.beginBroadcast();
                try {
                    for (int i = 0; i < itemCount; i++) {
                        try {
                            mCallbacks.getBroadcastItem(i).onStop(data);
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

    @Override
    public void registerReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver != null) {
            mCallbacks.register(receiver);
            mReceiverBinders.add(receiver.asBinder());
        }
    }

    @Override
    public void unregisterReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (receiver != null) {
            mCallbacks.unregister(receiver);
            mReceiverBinders.remove(receiver.asBinder());
        }
    }

    @Override
    public boolean isReceiverRegistered(ISuperLyricReceiver receiver) throws RemoteException {
        return receiver != null && mReceiverBinders.contains(receiver.asBinder());
    }

    @Override
    public void setSystemPlayStateListenerEnabled(String packageName, boolean enabled) throws RemoteException {
        if (packageName == null) {
            return;
        }

        if (enabled) {
            mNonSystemPlayStateListeners.add(packageName);
        } else {
            mNonSystemPlayStateListeners.remove(packageName);
        }
    }

    public void onPackageDied(@NonNull String packageName) {
        try {
            mPublishers.remove(packageName);
            mNonSystemPlayStateListeners.remove(packageName);
            sendStop(new SuperLyricData().setPackageName(packageName));
        } catch (RemoteException e) {
            AndroidLog.logW(TAG, "[onPackageDied()] RemoteException!!", e);
        }
    }
}
