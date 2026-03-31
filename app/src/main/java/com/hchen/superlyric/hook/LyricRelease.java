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
package com.hchen.superlyric.hook;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.data.SuperLyricKey;
import com.hchen.superlyricapi.AcquisitionMode;
import com.hchen.superlyricapi.ISuperLyricDistributor;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.Objects;

/**
 * 歌词发布类
 *
 * @author 焕晨HChen
 */
public abstract class LyricRelease extends AbsModule {
    private static ISuperLyricDistributor iSuperLyricDistributor;
    public static AudioManager audioManager;
    public static String packageName;
    public static long versionCode = -1L;
    public static String versionName = "unknown";

    @CallSuper
    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        ModuleData.setClassLoader(context.getClassLoader());

        packageName = context.getPackageName();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        Intent intent = new Intent(SuperLyricKey.SUPER_LYRIC);
        intent.putExtra(SuperLyricKey.SUPER_LYRIC_EXEMPT_PACKAGE, packageName);
        context.sendBroadcast(intent);

        Intent intentBinder = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        Objects.requireNonNull(intentBinder, "[Intent#ACTION_BATTERY_CHANGED] Return must not be null!!");

        Bundle bundle = intentBinder.getBundleExtra(SuperLyricKey.SUPER_LYRIC_INFO);
        Objects.requireNonNull(bundle, "[SUPER_LYRIC_INFO] Return must not be null!! try reboot system!!");

        iSuperLyricDistributor = ISuperLyricDistributor.Stub.asInterface(bundle.getBinder(SuperLyricKey.SUPER_LYRIC_BINDER));

        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(packageName, 0);
            versionName = packageInfo.versionName;
            versionCode = packageInfo.getLongVersionCode();
            logI(TAG, "App: " + packageName + ", version: " + versionName + ", code: " + versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            logW(TAG, e);
        }

        logD(TAG, "Success to obtain super lyric distributor: " + iSuperLyricDistributor + ", caller: " + packageName);
    }

    /**
     * Hook 热更新服务，用于更改当前 classloader
     */
    public static void hookTencentTinker() {
        try {
            hookMethod("com.tencent.tinker.loader.TinkerLoader",
                "tryLoad",
                "com.tencent.tinker.loader.app.TinkerApplication",
                new AbsHook() {
                    @Override
                    public void after() {
                        Intent intent = (Intent) getResult();
                        Application application = (Application) getArg(0);
                        int code = intent.getIntExtra("intent_return_code", -2);
                        if (code == 0) {
                            ModuleData.setClassLoader(application.getClassLoader());
                        }
                    }
                }
            );
        } catch (Throwable e) {
            logE("LyricRelease", e);
        }
    }

    /**
     * 模拟蓝牙为开启状态
     */
    public static void fakeBluetoothA2dpEnabled() {
        hookMethod("android.media.AudioManager",
            "isBluetoothA2dpOn",
            returnResult(true)
        );

        hookMethod("android.bluetooth.BluetoothAdapter",
            "isEnabled",
            returnResult(true)
        );
    }

    /**
     * 获取 MediaMetadata/Compat 中的歌词数据
     */
    public static void getMediaMetadataLyric() {
        hookMethod("android.media.MediaMetadata$Builder",
            "putString",
            String.class, String.class,
            new AbsHook() {
                @Override
                public void after() {
                    if (TextUtils.equals("android.media.metadata.TITLE", (String) getArg(0))) {
                        String lyric = (String) getArg(1);
                        if (lyric != null) {
                            sendLyric(lyric, 0, AcquisitionMode.BLUETOOTH_LYRIC);
                        }
                    }
                }
            }
        );

        hookMethod("android.support.v4.media.MediaMetadataCompat$Builder",
            "putString",
            String.class, String.class,
            new AbsHook() {
                @Override
                public void after() {
                    if (TextUtils.equals("android.media.metadata.TITLE", (String) getArg(0))) {
                        String lyric = (String) getArg(1);
                        if (lyric != null) {
                            sendLyric(lyric, 0, AcquisitionMode.BLUETOOTH_LYRIC);
                        }
                    }
                }
            }
        );
    }

    private static String lastLyric;

    public static void sendLyric(String lyric, int delay, @NonNull AcquisitionMode mode) {
        sendLyric(lyric, delay, new SuperLyricData().setAcquisitionMode(mode));
    }

    /**
     * 发送歌词数据
     */
    public static void sendLyric(String lyric, int delay, @NonNull SuperLyricData data) {
        if (lyric == null) return;
        if (iSuperLyricDistributor == null) return;

        try {
            lyric = lyric.trim();
            if (lyric.isEmpty()) return;
            if (TextUtils.equals(lyric, lastLyric)) return;
            lastLyric = lyric;

            iSuperLyricDistributor.onSuperLyric(
                data.setPackageName(packageName)
                    .setLyric(lyric)
                    .setDelay(delay)
            );
        } catch (RemoteException e) {
            logE("LyricRelease", "Failed to send lyric!!", e);
            return;
        }

        logD("LyricRelease", "Send lyric: " + lyric + ", delay: " + delay + ", data:" + data);
    }

    /**
     * 发送播放状态暂停
     */
    public static void sendStop() {
        sendStop(packageName);
    }

    /**
     * 发送播放状态暂停
     *
     * @param packageName 暂停播放的音乐软件包名
     */
    public static void sendStop(@NonNull String packageName) {
        sendStop(
            new SuperLyricData()
                .setPackageName(packageName)
        );
    }

    /**
     * 发送播放状态暂停
     *
     * @param data 数据
     */
    public static void sendStop(@NonNull SuperLyricData data) {
        if (iSuperLyricDistributor == null) return;

        try {
            iSuperLyricDistributor.onStop(data);
        } catch (RemoteException e) {
            logE("LyricRelease", "Failed to send stop!!", e);
            return;
        }

        logD("LyricRelease", "Send stop: " + data);
    }

    /**
     * 发送数据包
     *
     * @param data 数据
     */
    public static void sendSuperLyricData(@NonNull SuperLyricData data) {
        if (iSuperLyricDistributor == null) return;

        try {
            iSuperLyricDistributor.onSuperLyric(data);
        } catch (RemoteException e) {
            logE("LyricRelease", "Failed to send data!!", e);
            return;
        }

        logD("LyricRelease", "Send data: " + data);
    }
}
