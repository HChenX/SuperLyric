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

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyricapi.BuildConfig;
import com.hchen.superlyricapi.v4.ILyricFrameListener;
import com.hchen.superlyricapi.v4.ISuperLyricService;

/**
 * 4.0 Binder 门面：只解析调用方身份、判定访问权限并转发到 {@link LyricRuntime}。
 */
public final class SuperLyricV4Service extends ISuperLyricService.Stub {
    private static final String TAG = "SuperLyricV4Service";

    @NonNull
    private final LyricRuntime mCore;

    public SuperLyricV4Service(@NonNull LyricRuntime core) {
        mCore = core;
    }

    @Override
    public int publish(byte[] frame) throws RemoteException {
        if (!canUseV4Binder()) return LyricRuntime.RESULT_REJECTED_NOT_REGISTERED;
        try {
            Caller caller = caller();
            return mCore.publishFrame(caller.pid, caller.packageName, caller.uid, frame);
        } catch (Throwable t) {
            XposedLog.logE(TAG, "publish failed", t);
            return LyricRuntime.RESULT_REJECTED;
        }
    }

    @Override
    public void clear() throws RemoteException {
        if (!canUseV4Binder()) return;
        try {
            Caller caller = caller();
            mCore.clearFrame(caller.pid, caller.packageName);
        } catch (Throwable t) {
            XposedLog.logE(TAG, "clear failed", t);
        }
    }

    @Override
    public int registerListener(ILyricFrameListener listener) throws RemoteException {
        if (!canUseV4Binder() || listener == null) return FrameBroadcaster.REGISTER_REJECTED;
        try {
            Caller caller = caller();
            return mCore.registerFrameListener(listener, caller.uid, caller.pid, caller.packageName);
        } catch (Throwable t) {
            XposedLog.logE(TAG, "registerListener failed", t);
            return FrameBroadcaster.REGISTER_REJECTED;
        }
    }

    @Override
    public void unregisterListener(ILyricFrameListener listener) throws RemoteException {
        if (!canUseV4Binder() || listener == null) return;
        try {
            Caller caller = caller();
            mCore.unregisterFrameListener(listener, caller.pid, caller.packageName);
        } catch (Throwable t) {
            XposedLog.logE(TAG, "unregisterListener failed", t);
        }
    }

    @Override
    public byte[] latest() throws RemoteException {
        if (!canUseV4Binder()) return null;
        try {
            return mCore.latestFrame();
        } catch (Throwable t) {
            XposedLog.logE(TAG, "latest failed", t);
            return null;
        }
    }

    @Override
    public int getApiVersion() throws RemoteException {
        return canUseV4Binder() ? BuildConfig.API_VERSION : 0;
    }

    private Caller caller() {
        int pid = Binder.getCallingPid();
        return new Caller(pid, Binder.getCallingUid(), mCore.packageNameForPid(pid));
    }

    private static boolean canUseV4Binder() {
        int uid = Binder.getCallingUid();
        boolean allowed = AccessPolicy.allowsV4(uid);
        if (!allowed) XposedLog.logD(TAG, "Denied v4 Binder call from uid " + uid);
        return allowed;
    }

    private record Caller(int pid, int uid, @NonNull String packageName) {
    }
}
