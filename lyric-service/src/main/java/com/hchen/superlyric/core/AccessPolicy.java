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
import com.hchen.superlyric.data.SupportedApps;

import java.io.Serializable;
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
    /**
     * 3.x 3.5 起支持全量歌词；低于该版本只支持当前句。
     */
    static final int LEGACY_FULL_API = 35;
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

    static boolean allowsLegacy(int uid) {
        Level level = levelForUid(uid);
        return level.allowsLegacy();
    }

    static boolean allowsV4(int uid) {
        Level level = levelForUid(uid);
        return level.allowsV4();
    }

    static boolean supportsFullLegacy(int versionCode) {
        return versionCode >= LEGACY_FULL_API;
    }

    static int apiVersionOf(@Nullable String packageName) {
        Context context = sContext;
        if (context == null || packageName == null || packageName.isEmpty()) return 0;

        try {
            ApplicationInfo info = context.getPackageManager()
                .getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            return versionOf(info);
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        } catch (Throwable t) {
            logError("Failed to resolve API version for " + packageName, t);
            return 0;
        }
    }

    static int apiVersionOfUid(int uid) {
        Context context = sContext;
        if (context == null || uid <= 0) return 0;

        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return 0;

            int version = 0;
            for (String packageName : packages) {
                version = Math.max(version, apiVersionOf(packageName));
            }
            return version;
        } catch (Throwable t) {
            logError("Failed to resolve API version for uid " + uid, t);
            return 0;
        }
    }

    static boolean supportsFullLegacyLyric(@Nullable String packageName, int uid) {
        int version = apiVersionOf(packageName);
        if (version <= 0) version = apiVersionOfUid(uid);
        return supportsFullLegacy(version);
    }

    static void invalidateAll() {
        sLevels.clear();
    }

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

        if (SupportedApps.PACKAGES.contains(packageName)) return Level.BOTH;

        Bundle metadata = info.metaData;
        if (metadata == null) return Level.DENY;

        // SuperLyricApi 3.6 AAR 声明 superlyricapi="true"、version_code="36"；平台也可能
        // 交回 typed Boolean/Integer。按原始值分派，避免 getString 的类型 W，也避免只认
        // getInt 而读错字符串版本号。
        boolean marked = readBoolean(metadata, META_API);
        int versionCode = readInt(metadata, META_API_VERSION);
        return resolve(false, false, marked, versionCode);
    }

    private static int versionOf(@Nullable ApplicationInfo info) {
        if (info == null || info.metaData == null) return 0;
        return readInt(info.metaData, META_API_VERSION);
    }

    private static boolean readBoolean(@NonNull Bundle metadata, @NonNull String key) {
        return toBooleanValue(rawMetadata(metadata, key));
    }

    private static int readInt(@NonNull Bundle metadata, @NonNull String key) {
        return toIntValue(rawMetadata(metadata, key));
    }

    @Nullable
    private static Object rawMetadata(@NonNull Bundle metadata, @NonNull String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return metadata.getSerializable(key, Serializable.class);
        }
        return legacyRawMetadata(metadata, key);
    }

    /**
     * API 33 以下没有非 deprecated 的原始值读取口；仅此兼容分支局部抑制。
     */
    @SuppressWarnings("deprecation")
    @Nullable
    private static Object legacyRawMetadata(@NonNull Bundle metadata, @NonNull String key) {
        return metadata.get(key);
    }

    static boolean toBooleanValue(@Nullable Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean(((String) value).trim());
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return false;
    }

    static int toIntValue(@Nullable Object value) {
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
