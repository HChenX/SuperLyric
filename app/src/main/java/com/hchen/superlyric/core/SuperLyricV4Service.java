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
 * {@code super_lyric.v4} 的 Binder 门面
 *
 * <p>一个 IBinder 只能实现一套 stub 与事务号，4.0 接口与 3.x 的
 * {@link com.hchen.superlyricapi.ISuperLyricManager} 因此必须是两个对象。本类只解析
 * 调用方身份，会话、时钟与广播逻辑集中在 {@link SuperLyricService}，两代接口共享同一份状态。
 *
 * @author 焕晨HChen
 */
public final class SuperLyricV4Service extends ISuperLyricService.Stub {
    private static final String TAG = "SuperLyricV4Service";

    private final SuperLyricService mCore;

    public SuperLyricV4Service(@NonNull SuperLyricService core) {
        mCore = core;
    }

    @Override
    public int publish(byte[] frame) throws RemoteException {
        if (!canUseV4Binder()) return SuperLyricService.RESULT_REJECTED_NOT_REGISTERED;
        try {
            int pid = Binder.getCallingPid();
            return mCore.publishFrame(pid, mCore.getPackageNameWithPid(pid), frame);
        } catch (Throwable t) {
            // Binder 方法不应把异常抛回发布方；无法归类的失败按 REJECTED(3) 返回
            XposedLog.logE(TAG, "publish failed", t);
            return SuperLyricService.RESULT_REJECTED;
        }
    }

    @Override
    public void clear() throws RemoteException {
        if (!canUseV4Binder()) return;
        int pid = Binder.getCallingPid();
        mCore.clearFrame(pid, mCore.getPackageNameWithPid(pid));
    }

    @Override
    public int registerListener(ILyricFrameListener listener) throws RemoteException {
        if (!canUseV4Binder()) return FrameBroadcaster.REGISTER_REJECTED;
        if (listener == null) return FrameBroadcaster.REGISTER_REJECTED;
        try {
            int pid = Binder.getCallingPid();
            return mCore.registerFrameListener(listener, Binder.getCallingUid(), pid,
                mCore.getPackageNameWithPid(pid));
        } catch (Throwable t) {
            XposedLog.logE(TAG, "registerListener failed", t);
            return FrameBroadcaster.REGISTER_REJECTED;
        }
    }

    @Override
    public void unregisterListener(ILyricFrameListener listener) throws RemoteException {
        if (!canUseV4Binder()) return;
        if (listener == null) return;
        int pid = Binder.getCallingPid();
        mCore.unregisterFrameListener(listener, pid, mCore.getPackageNameWithPid(pid));
    }

    @Override
    public byte[] latest() throws RemoteException {
        if (!canUseV4Binder()) return null;
        return mCore.latestFrame();
    }

    /**
     * v4 Binder access is granted to system/support apps and 4.0 SDK clients.
     * A denied call returns a protocol rejection, never an exception.
     */
    private static boolean canUseV4Binder() {
        int uid = Binder.getCallingUid();
        boolean allowed = AccessPolicy.allowsV4(uid);
        if (!allowed) {
            XposedLog.logD(TAG, "Denied v4 Binder call from uid " + uid);
        }
        return allowed;
    }

    @Override
    public int getApiVersion() throws RemoteException {
        if (!canUseV4Binder()) return 0;
        return BuildConfig.API_VERSION;
    }
}
