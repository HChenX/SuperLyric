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
package com.hchen.superlyric.publisher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleData;
import com.hchen.superlyric.utils.HotfixDisabler;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricHelper;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 宿主进程中的底层发布基类。
 * <p>
 * 统一管理与系统 SuperLyricService 的发布者注册、死亡处理，
 * 并提供面向 SuperLyricApi 3.5 的全量包与增量进度包发送通道。
 *
 * @author 焕晨HChen
 */
public abstract class AbsPublisher extends AbsModule {
    private static final Object sPublishLock = new Object();

    private static volatile Context sAppContext;
    private static String sPackageName;
    private static long sVersionCode = -1L;
    private static String sVersionName = "unknown";

    @CallSuper
    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        SuperLyricHelper.registerPublisher();
        HotfixDisabler.disableAllHotfixes();
    }

    @CallSuper
    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        sAppContext = context.getApplicationContext();
        ModuleData.setClassLoader(context.getClassLoader());
        sPackageName = context.getPackageName();
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(sPackageName, 0);
            sVersionName = packageInfo.versionName;
            sVersionCode = packageInfo.getLongVersionCode();
            logI(tag, "Loaded publisher for: " + sPackageName + " (v" + sVersionName + ", " + sVersionCode + ")");
        } catch (PackageManager.NameNotFoundException e) {
            logE(tag, "Failed to retrieve package info: " + sPackageName, e);
        }
    }

    @Nullable
    public static Context getAppContext() {
        if (sAppContext != null) {
            return sAppContext;
        }
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object app = activityThreadClass.getMethod("currentApplication").invoke(null);
            if (app instanceof Context ctx) {
                sAppContext = ctx.getApplicationContext();
                return sAppContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
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
     * 发送全量歌词数据包（切歌或歌词初次解析完毕时调用）。
     */
    public static void publishFullLyric(@NonNull SuperLyricData fullData) {
        SuperLyricData clean = LyricSanitizer.sanitizeData(fullData);
        if (clean == null) return;
        synchronized (sPublishLock) {
            SuperLyricHelper.sendFullLyric(clean);
        }
    }

    /**
     * 发送播放中的行索引与进度更新（极轻量增量包）。
     */
    public static void publishLyricProgress(@NonNull SuperLyricData progressData) {
        SuperLyricData clean = LyricSanitizer.sanitizeData(progressData);
        if (clean == null) return;
        synchronized (sPublishLock) {
            SuperLyricHelper.sendLyricProgress(clean);
        }
    }

    /**
     * 发送停止/暂停事件。
     */
    public static void publishStop() {
        publishStop(new SuperLyricData());
    }

    /**
     * 发送停止/暂停事件（带上下文）。
     */
    public static void publishStop(@NonNull SuperLyricData stopData) {
        synchronized (sPublishLock) {
            SuperLyricHelper.sendStop(stopData);
        }
    }

    /**
     * 向下兼容通道：发送单行歌词数据包。
     */
    public static void publishLegacySingleLine(@NonNull SuperLyricData singleLineData) {
        SuperLyricData clean = LyricSanitizer.sanitizeData(singleLineData);
        if (clean == null) return;
        synchronized (sPublishLock) {
            SuperLyricHelper.sendLyric(clean);
        }
    }
}
