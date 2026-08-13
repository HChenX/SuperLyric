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

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.text.TextUtils;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.utils.HotfixDisabler;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricHelper;
import com.hchen.superlyricapi.SuperLyricLine;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 歌词发布类
 *
 * @author 焕晨HChen
 */
public abstract class AbsPublisher extends AbsModule {
    private static AudioManager sAudioManager;
    private static String sPackageName;
    private static long sVersionCode = -1L;
    private static String sVersionName = "unknown";

    @CallSuper
    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        HotfixDisabler.disableAllHotfixes();
    }

    @CallSuper
    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        ModuleData.setClassLoader(context.getClassLoader());
        SuperLyricHelper.registerPublisher();
        sPackageName = context.getPackageName();
        sAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(sPackageName, 0);
            sVersionName = packageInfo.versionName;
            sVersionCode = packageInfo.getLongVersionCode();
            logI(TAG, "Package name: " + sPackageName + ", version name: " + sVersionName + ", version code: " + sVersionCode);
        } catch (PackageManager.NameNotFoundException e) {
            logE(TAG, "Failed to retrieve package: '" + sPackageName + "' information!", e);
        }

        logI(TAG, "Success to register super lyric publisher service, caller: " + sPackageName);
    }

    public static AudioManager getAudioManager() {
        return sAudioManager;
    }

    public static String getPackageName() {
        return sPackageName;
    }

    public static long getVersionCode() {
        return sVersionCode;
    }

    public static String getVersionName() {
        return sVersionName;
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
        if (lyric == null) return;

        lyric = lyric.trim();
        if (lyric.isEmpty()) return;
        if (TextUtils.equals(lyric, mLastLyric)) return;
        mLastLyric = lyric;

        sendLyric(
            new SuperLyricData()
                .setLyric(
                    new SuperLyricLine(
                        lyric,
                        delay
                    )
                )
        );
    }

    public static void sendLyric(@NonNull SuperLyricData data) {
        SuperLyricHelper.sendLyric(data);
    }

    public static void sendStop() {
        sendStop(new SuperLyricData());
    }

    public static void sendStop(@NonNull SuperLyricData data) {
        SuperLyricHelper.sendStop(data);
    }
}
