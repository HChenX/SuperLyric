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

import android.os.Binder;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyricapi.BuildConfig;
import com.hchen.superlyricapi.ISuperLyricManager;
import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;

/**
 * 3.x Binder 门面：只解析调用方身份、判定访问权限并转发到 {@link LyricRuntime}。
 */
public final class SuperLyricService extends ISuperLyricManager.Stub {
    private static final String TAG = "SuperLyricService";

    @NonNull
    private final LyricRuntime mCore;

    public SuperLyricService(@NonNull LyricRuntime core) {
        mCore = core;
    }

    @Override
    public void registerPublisher() throws RemoteException {
        if (!canUseLegacyBinder()) return;
        Caller caller = caller();
        mCore.registerPublisher(caller.pid, caller.packageName, caller.uid);
    }

    @Override
    public void unregisterPublisher() throws RemoteException {
        if (!canUseLegacyBinder()) return;
        Caller caller = caller();
        mCore.unregisterPublisher(caller.pid, caller.packageName);
    }

    @Override
    public boolean isPublisherRegistered() throws RemoteException {
        if (!canUseLegacyBinder()) return false;
        Caller caller = caller();
        return mCore.isPublisherRegistered(caller.pid, caller.packageName);
    }

    @Override
    public void sendLyric(SuperLyricData data) throws RemoteException {
        if (!canUseLegacyBinder()) return;
        Caller caller = caller();
        mCore.sendLyric(caller.pid, caller.packageName, caller.uid, data);
    }

    @Override
    public void sendStop(SuperLyricData data) throws RemoteException {
        if (!canUseLegacyBinder()) return;
        Caller caller = caller();
        mCore.sendStop(caller.pid, caller.packageName, data);
    }

    @Override
    public void setSystemPlayStateListenerEnabled(boolean enabled) throws RemoteException {
        if (!canUseLegacyBinder()) return;
        Caller caller = caller();
        mCore.setSystemPlayStateListenerEnabled(caller.pid, caller.packageName, enabled);
    }

    @Override
    public void registerReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (!canUseLegacyBinder() || receiver == null) return;
        Caller caller = caller();
        mCore.registerReceiver(receiver, caller.uid, caller.pid, caller.packageName);
    }

    @Override
    public void unregisterReceiver(ISuperLyricReceiver receiver) throws RemoteException {
        if (!canUseLegacyBinder() || receiver == null) return;
        Caller caller = caller();
        mCore.unregisterReceiver(receiver, caller.pid, caller.packageName);
    }

    @Override
    public boolean isReceiverRegistered(ISuperLyricReceiver receiver) throws RemoteException {
        return canUseLegacyBinder() && mCore.isReceiverRegistered(receiver);
    }

    @Nullable
    @Override
    public SuperLyricData getLatestLyric() throws RemoteException {
        if (!canUseLegacyBinder()) return null;
        Caller caller = caller();
        return mCore.latestLegacy(caller.pid, caller.packageName, caller.uid);
    }

    @Override
    public int getServerApiVersion() throws RemoteException {
        return canUseLegacyBinder() ? BuildConfig.API_VERSION : 0;
    }

    private Caller caller() {
        int pid = Binder.getCallingPid();
        return new Caller(pid, Binder.getCallingUid(), mCore.packageNameForPid(pid));
    }

    private static boolean canUseLegacyBinder() {
        int uid = Binder.getCallingUid();
        boolean allowed = AccessPolicy.allowsLegacy(uid);
        if (!allowed) XposedLog.logD(TAG, "Denied legacy Binder call from uid " + uid);
        return allowed;
    }

    private record Caller(int pid, int uid, @NonNull String packageName) {
    }
}
