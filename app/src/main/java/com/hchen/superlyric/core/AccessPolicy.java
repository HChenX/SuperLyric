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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.data.SupportApps;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按调用方 UID 解析可见的 SuperLyric Binder 服务
 *
 * <p>系统应用与支持的音乐应用获得两代接口；第三方应用只获得 SDK marker
 * 版本对应的接口。PackageManager 查询失败按拒绝处理，且不缓存失败结果。
 */
public final class AccessPolicy {
    private static final String TAG = "SuperLyric-Access";

    public static final String LEGACY_SERVICE = "super_lyric";
    public static final String V4_SERVICE = "super_lyric.v4";

    private static final String META_API = "superlyricapi";
    private static final String META_API_VERSION = "superlyricapi_version_code";
    private static final int API_LEGACY = 31;
    private static final int API_V4 = 40;
    private static final long ERROR_LOG_INTERVAL_MS = 10_000L;

    private static final ConcurrentHashMap<Integer, Level> sLevels = new ConcurrentHashMap<>();

    private static volatile Context sContext;
    private static volatile boolean sReceiverRegistered;
    private static volatile long sLastErrorLogMs;

    private static final BroadcastReceiver sPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            invalidateAll();
            XposedLog.logD(TAG, "Package change invalidated access cache: "
                + intent.getAction() + ", package=" + packageOf(intent));
        }
    };

    private AccessPolicy() {
    }

    /**
     * 一次访问判定允许的 Binder 接口集合
     */
    public enum Level {
        DENY(false, false),
        LEGACY(true, false),
        V4(false, true),
        BOTH(true, true);

        private final boolean legacy;
        private final boolean v4;

        Level(boolean legacy, boolean v4) {
            this.legacy = legacy;
            this.v4 = v4;
        }

        public boolean allowsLegacy() {
            return legacy;
        }

        public boolean allowsV4() {
            return v4;
        }
    }

    /**
     * 初始化系统 Context，并注册包变更监听
     *
     * <p>重复调用是安全的；包安装、更新、卸载后清空 UID 缓存。
     */
    static void initialize(@Nullable Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        Context target = appContext != null ? appContext : context;

        synchronized (AccessPolicy.class) {
            sContext = target;
            if (sReceiverRegistered) return;

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    target.registerReceiver(sPackageReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    target.registerReceiver(sPackageReceiver, filter);
                }
                sReceiverRegistered = true;
                XposedLog.logI(TAG, "Access policy initialized; package observer registered.");
            } catch (Throwable t) {
                logError("Failed to register package observer", t);
            }
        }
    }

    /**
     * 返回 UID 对应的访问级别，失败时按拒绝处理
     */
    static Level levelForUid(int uid) {
        if (uid <= 0) return Level.DENY;

        Level cached = sLevels.get(uid);
        if (cached != null) return cached;

        Context context = sContext;
        if (context == null) return Level.DENY;

        Level resolved;
        try {
            resolved = resolveUid(context, uid);
        } catch (Throwable t) {
            logError("Failed to resolve access for uid " + uid, t);
            return Level.DENY;
        }
        if (resolved == null) return Level.DENY;

        Level previous = sLevels.putIfAbsent(uid, resolved);
        return previous != null ? previous : resolved;
    }

    /**
     * 是否允许旧版 Binder
     */
    static boolean allowsLegacy(int uid) {
        Level level = levelForUid(uid);
        return level.allowsLegacy();
    }

    /**
     * 是否允许 v4 Binder
     */
    static boolean allowsV4(int uid) {
        Level level = levelForUid(uid);
        return level.allowsV4();
    }

    /**
     * 清空全部 UID 缓存
     */
    static void invalidateAll() {
        sLevels.clear();
    }

    /**
     * 清空指定 UID 的缓存
     */
    static void invalidateUid(int uid) {
        if (uid > 0) sLevels.remove(uid);
    }

    /**
     * 纯策略判定，供 UID 解析与单元测试使用
     */
    public static Level resolve(boolean system, boolean supported, boolean marked, int versionCode) {
        if (system || supported) return Level.BOTH;
        if (!marked) return Level.DENY;
        if (versionCode >= API_V4) return Level.V4;
        if (versionCode >= API_LEGACY) return Level.LEGACY;
        return Level.DENY;
    }

    /**
     * 解析 UID 的实际权限；PackageManager 异常返回 null，调用方不缓存
     */
    @Nullable
    private static Level resolveUid(@NonNull Context context, int uid) {
        if (uid < Process.FIRST_APPLICATION_UID) return Level.BOTH;

        PackageManager packageManager = context.getPackageManager();
        String[] packages;
        try {
            packages = packageManager.getPackagesForUid(uid);
        } catch (Throwable t) {
            logError("PackageManager failed for uid " + uid, t);
            return null;
        }
        if (packages == null || packages.length == 0) return Level.DENY;

        boolean sawPackage = false;
        boolean sawLegacy = false;
        boolean sawV4 = false;

        for (String packageName : packages) {
            if (packageName == null || packageName.isEmpty()) continue;

            ApplicationInfo info;
            try {
                info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            } catch (Throwable t) {
                logError("PackageManager failed for package " + packageName, t);
                return null;
            }

            sawPackage = true;
            Level level = resolvePackage(info, packageName);
            if (level == Level.BOTH) return Level.BOTH;
            if (level == Level.V4) sawV4 = true;
            if (level == Level.LEGACY) sawLegacy = true;
        }

        if (!sawPackage) return null;
        if (sawLegacy && sawV4) return Level.BOTH;
        if (sawV4) return Level.V4;
        if (sawLegacy) return Level.LEGACY;
        return Level.DENY;
    }

    @NonNull
    private static Level resolvePackage(@NonNull ApplicationInfo info, @NonNull String packageName) {
        boolean system = (info.flags
            & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        if (system) return Level.BOTH;

        if (SupportApps.sMediaAppPackages.contains(packageName)) return Level.BOTH;

        Bundle metadata = info.metaData;
        if (metadata == null) return Level.DENY;

        boolean marked = readBoolean(metadata.get(META_API));
        int versionCode = readInt(metadata.get(META_API_VERSION));
        return resolve(false, false, marked, versionCode);
    }

    private static boolean readBoolean(@Nullable Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return false;
    }

    private static int readInt(@Nullable Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @NonNull
    private static String packageOf(@Nullable Intent intent) {
        if (intent == null || intent.getData() == null) return "unknown";
        String value = intent.getData().getSchemeSpecificPart();
        return value != null ? value : "unknown";
    }

    private static void logError(@NonNull String message, @Nullable Throwable throwable) {
        long now = System.currentTimeMillis();
        if (now - sLastErrorLogMs < ERROR_LOG_INTERVAL_MS) return;
        sLastErrorLogMs = now;
        XposedLog.logE(TAG, message, throwable);
    }
}
