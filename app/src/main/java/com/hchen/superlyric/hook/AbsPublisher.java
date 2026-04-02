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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.text.TextUtils;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricHelper;

/**
 * 歌词发布类
 *
 * @author 焕晨HChen
 */
public abstract class AbsPublisher extends AbsModule {
    public static AudioManager mAudioManager;
    public static String mPackageName;
    public static long mVersionCode = -1L;
    public static String mVersionName = "unknown";

    @CallSuper
    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        ModuleData.setClassLoader(context.getClassLoader());

        SuperLyricHelper.registerPublisher(context);

        mPackageName = context.getPackageName();
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(mPackageName, 0);
            mVersionName = packageInfo.versionName;
            mVersionCode = packageInfo.getLongVersionCode();
            logI(TAG, "App name: " + mPackageName + ", version name: " + mVersionName + ", version code: " + mVersionCode);
        } catch (PackageManager.NameNotFoundException e) {
            logW(TAG, e);
        }

        logD(TAG, "Success to register super lyric publisher service, caller: " + mPackageName);
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
        } catch (Throwable ignore) {
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
    public static void hookMediaMetadataLyric() {
        hookMethod("android.media.MediaMetadata$Builder",
            "putString",
            String.class, String.class,
            new AbsHook() {
                @Override
                public void after() {
                    if (TextUtils.equals("android.media.metadata.TITLE", (String) getArg(0))) {
                        String lyric = (String) getArg(1);
                        if (lyric != null) {
                            sendLyric(lyric);
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
                            sendLyric(lyric);
                        }
                    }
                }
            }
        );
    }

    private static String mLastLyric;

    public static void sendLyric(String lyric) {
        sendLyric(lyric, 0);
    }

    public static void sendLyric(String lyric, int delay) {
        sendLyric(lyric, delay, new SuperLyricData());
    }

    public static void sendLyric(String lyric, int delay, @NonNull SuperLyricData data) {
        if (lyric == null) return;

        lyric = lyric.trim();
        if (lyric.isEmpty()) return;
        if (TextUtils.equals(lyric, mLastLyric)) return;
        mLastLyric = lyric;

        data.setPackageName(mPackageName)
            .setLyric(lyric)
            .setDelay(delay);

        sendSuperLyricData(data);
    }

    public static void sendSuperLyricData(@NonNull SuperLyricData data) {
        SuperLyricHelper.sendLyric(data);
        logD("LyricRelease", "Send super lyric data: " + data);
    }

    public static void sendStop() {
        sendStop(mPackageName);
    }

    public static void sendStop(@NonNull String packageName) {
        sendStop(
            new SuperLyricData()
                .setPackageName(packageName)
        );
    }

    public static void sendStop(@NonNull SuperLyricData data) {
        SuperLyricHelper.sendStop(data);
        logD("LyricRelease", "Send stop: " + data);
    }
}
