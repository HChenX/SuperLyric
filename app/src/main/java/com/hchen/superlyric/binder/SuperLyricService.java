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

 * Copyright (C) 2023-2025 HChenX
 */
package com.hchen.superlyric.binder;

import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyricapi.ISuperLyric;
import com.hchen.superlyricapi.ISuperLyricDistributor;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Super Lyric 服务
 * <p>
 * 向所有接收方发送歌词等数据
 *
 * @author 焕晨HChen
 */
public class SuperLyricService extends ISuperLyricDistributor.Stub {
    private static final String TAG = "SuperLyricService";
    private final static ConcurrentHashMap<IBinder, ISuperLyric> mRegisteredBinderMap = new ConcurrentHashMap<>();
    public static final CopyOnWriteArraySet<String> mExemptSet = new CopyOnWriteArraySet<>();
    public static final CopyOnWriteArraySet<String> mSelfControlSet = new CopyOnWriteArraySet<>();

    public void registerSuperLyricBinder(@NonNull IBinder iBinder, @NonNull ISuperLyric iSuperLyric) {
        try {
            mRegisteredBinderMap.putIfAbsent(iBinder, iSuperLyric);
        } catch (Throwable e) {
            AndroidLog.logE(TAG, "[registerSuperLyricBinder]: Failed to add binder: " + iSuperLyric, e);
        }
    }

    public void unregisterSuperLyricBinder(@NonNull IBinder iBinder) {
        try {
            if (mRegisteredBinderMap.get(iBinder) != null) {
                mRegisteredBinderMap.remove(iBinder);
            }
        } catch (Throwable e) {
            AndroidLog.logE(TAG, "[unregisterSuperLyricBinder]: Failed to remove binder: " + iBinder, e);
        }
    }

    public void addSelfControlPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        mSelfControlSet.add(packageName);
    }

    public void removeSelfControlPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        mSelfControlSet.remove(packageName);
    }

    @Override
    public void onSuperLyric(SuperLyricData data) throws RemoteException {
        mRegisteredBinderMap.entrySet().removeIf(entry -> {
            ISuperLyric iSuperLyric = entry.getValue();
            try {
                iSuperLyric.onSuperLyric(data);
                return false;
            } catch (Throwable e) {
                AndroidLog.logE(TAG, "[onSuperLyric]: Binder died!! remove binder: " + iSuperLyric, e);
                return true;
            }
        });
    }

    @Override
    public void onStop(SuperLyricData data) throws RemoteException {
        mRegisteredBinderMap.entrySet().removeIf(entry -> {
            ISuperLyric iSuperLyric = entry.getValue();
            try {
                iSuperLyric.onStop(data);
                return false;
            } catch (Throwable e) {
                AndroidLog.logE(TAG, "[onStop]: Binder died!! remove binder: " + iSuperLyric, e);
                return true;
            }
        });
    }

    public void addExemptPackage(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty()) return;
            mExemptSet.add(packageName);
        } catch (Throwable e) {
            AndroidLog.logE(TAG, "[onExempt]: Error to add exempt package:", e);
        }
    }

    public void onPackageDied(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;

        try {
            mExemptSet.remove(packageName); // 死后自动移除豁免
            mSelfControlSet.remove(packageName); // 移除自我控制
            onStop(new SuperLyricData().setPackageName(packageName));
        } catch (Throwable e) {
            AndroidLog.logE(TAG, "Package is died: " + packageName, e);
        }
    }
}
