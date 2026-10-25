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
 * 宿主进程中的底层发布基类
 * <p>
 * 统一管理与系统 SuperLyricService 的发布者注册、死亡处理。
 *
 * <h3>只发布两种包</h3>
 * 按项目核心理念「音乐软件仅负责发布完整歌词，系统框架负责分发」，宿主侧只发送：
 * <ul>
 *   <li><b>全量歌词</b>——{@link #publishFullLyric}；</li>
 *   <li><b>停止</b>——{@link #publishStop}。</li>
 * </ul>
 * 进度推进由系统服务端的唯一时钟负责。这是<b>约定</b>而非类型保证：
 * {@link com.hchen.superlyricapi.SuperLyricData} 仍是公开可变 Parcelable，
 * 带 position 的包也能从这两个入口发出；服务端不校验，发布者必须遵守本约定。
 *
 * @author 焕晨HChen
 */
public abstract class AbsPublisher extends AbsModule {
    private static final Object sPublishLock = new Object();

    private static volatile Context sAppContext;
    private static volatile String sPackageName;
    private static volatile long sVersionCode = -1L;
    private static volatile String sVersionName = "unknown";
    private static volatile boolean sContextFallbackLogged;
    private static volatile boolean sNeedsReRegister;

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
        } catch (Throwable t) {
            if (!sContextFallbackLogged) {
                sContextFallbackLogged = true;
                com.hchen.hooktool.log.AndroidLog.logW("AbsPublisher",
                    "Cannot resolve Application context via ActivityThread", t);
            }
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
     * 发送全量歌词数据包（切歌或歌词初次解析完毕时调用）
     */
    public static void publishFullLyric(@NonNull SuperLyricData fullData) {
        SuperLyricData clean = LyricSanitizer.sanitizeData(fullData);
        if (clean == null) return;
        synchronized (sPublishLock) {
            ensureRegistered();
            try {
                SuperLyricHelper.sendLyric(clean);
            } catch (Throwable t) {
                // API 在未注册时抛 IllegalStateException；不能让异常穿出编排器回调线程。
                sNeedsReRegister = true;
                com.hchen.hooktool.log.AndroidLog.logE("AbsPublisher", "Failed to publish full lyric", t);
            }
        }
    }

    /**
     * 发送停止/暂停事件
     */
    public static void publishStop() {
        publishStop(new SuperLyricData());
    }

    /**
     * 发送停止/暂停事件（带上下文）
     */
    public static void publishStop(@NonNull SuperLyricData stopData) {
        synchronized (sPublishLock) {
            ensureRegistered();
            try {
                SuperLyricHelper.sendStop(stopData);
            } catch (Throwable t) {
                sNeedsReRegister = true;
                com.hchen.hooktool.log.AndroidLog.logE("AbsPublisher", "Failed to publish stop", t);
            }
        }
    }

    /**
     * 发布失败后置位；下一次发布前重试注册，避免整轮歌词永久丢失。
     */
    private static void ensureRegistered() {
        if (!sNeedsReRegister) return;
        try {
            SuperLyricHelper.registerPublisher();
            sNeedsReRegister = false;
            com.hchen.hooktool.log.AndroidLog.logI("AbsPublisher", "Re-registered publisher after failure");
        } catch (Throwable t) {
            com.hchen.hooktool.log.AndroidLog.logW("AbsPublisher", "Re-register failed; will retry", t);
        }
    }
}
