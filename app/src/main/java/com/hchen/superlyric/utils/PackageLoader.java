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
package com.hchen.superlyric.utils;

import static com.hchen.superlyric.data.SupportApps.sMediaAppPackages;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import com.hchen.hooktool.data.AppData;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.hooktool.utils.BitmapTool;
import com.hchen.hooktool.utils.PackageTool;
import com.hchen.superlyric.data.NetworkMode;
import com.hchen.superlyric.data.PrefsKey;
import com.hchen.superlyric.data.SupportApps;
import com.hchen.superlyric.data.apps.ApiAppData;
import com.hchen.superlyric.data.apps.NetworkAppData;
import com.hchen.superlyric.ui.Application;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 包加载器。扫描请求在单线程中执行；扫描期间到达的并发请求会合并为一次后续重扫。
 *
 * @author 焕晨HChen
 */
public final class PackageLoader {
    private static final String TAG = "PackageLoader";
    private static final Object LOAD_LOCK = new Object();
    private static volatile List<AppData> sMediaApps = List.of();
    private static volatile List<ApiAppData> sMediaApiApps = List.of();
    private static volatile List<NetworkAppData> sMediaNetworkApps = List.of();
    private static final List<Runnable> sPackageLoadedListeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);
    private static CompletableFuture<Void> sCurrentLoad;
    private static CompletableFuture<Void> sPendingLoad;
    private static boolean rescanRequested;
    private static long completedLoadCount;

    /**
     * 请求扫描已安装应用。
     *
     * <p>若扫描正在进行，本次请求会与其他并发请求合并为紧随其后的一次重扫，返回值在该重扫完成后结束。</p>
     *
     * @param context 用于访问包管理器的上下文；内部仅保存其 applicationContext
     * @return 表示本次请求所对应扫描已完成的 Future
     */
    @NonNull
    public static CompletableFuture<Void> loadPackages(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        synchronized (LOAD_LOCK) {
            if (sCurrentLoad == null) {
                sCurrentLoad = new CompletableFuture<>();
                scheduleLoad(appContext);
                return sCurrentLoad;
            }

            rescanRequested = true;
            if (sPendingLoad == null) {
                sPendingLoad = new CompletableFuture<>();
            }
            return sPendingLoad;
        }
    }

    private static void scheduleLoad(@NonNull Context context) {
        EXECUTOR_SERVICE.execute(() -> {
            Throwable failure = null;
            try {
                scanPackages(context);
            } catch (Throwable throwable) {
                failure = throwable;
                AndroidLog.logE(TAG, "Failed to load package list", throwable);
            }

            CompletableFuture<Void> completedFuture;
            List<Runnable> listenersSnapshot;
            boolean runAgain;
            synchronized (LOAD_LOCK) {
                completedFuture = sCurrentLoad;
                completedLoadCount++;
                listenersSnapshot = List.copyOf(sPackageLoadedListeners);
                runAgain = rescanRequested;
                if (runAgain) {
                    rescanRequested = false;
                    sCurrentLoad = sPendingLoad;
                    sPendingLoad = null;
                } else {
                    sCurrentLoad = null;
                }
            }

            if (failure == null) {
                completedFuture.complete(null);
            } else {
                completedFuture.completeExceptionally(failure);
            }
            notifyPackageLoadedListeners(listenersSnapshot);

            if (runAgain) {
                scheduleLoad(context);
            }
        });
    }

    private static void scanPackages(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppData> mediaApps = new ArrayList<>();
        List<NetworkAppData> mediaNetworkApps = new ArrayList<>();
        List<ApiAppData> mediaApiApps = new ArrayList<>();
        List<PackageInfo> infos = pm.getInstalledPackages(PackageManager.GET_META_DATA);
        for (PackageInfo info : infos) {
            if (sMediaAppPackages.contains(info.packageName)) {
                if (isMediaNetworkApp(info.packageName)) {
                    if (info.applicationInfo != null) {
                        NetworkAppData networkData = new NetworkAppData();
                        networkData.icon = BitmapTool.drawableToBitmap(info.applicationInfo.loadIcon(pm));
                        networkData.label = (String) info.applicationInfo.loadLabel(pm);
                        networkData.packageName = info.applicationInfo.packageName;
                        networkData.versionName = info.versionName;
                        networkData.versionCode = Long.toString(info.getLongVersionCode());
                        mediaNetworkApps.add(networkData);
                    }
                } else {
                    mediaApps.add(PackageTool.createAppData(pm, info, true));
                }
            }

            if (info.applicationInfo != null && info.applicationInfo.metaData != null) {
                boolean isApi = info.applicationInfo.metaData.getBoolean("superlyricapi");
                if (isApi) {
                    boolean isXposed = info.applicationInfo.metaData.getBoolean("xposedmodule") ||
                        hasXposedModule(info.applicationInfo.sourceDir);
                    if (!isXposed) {
                        ApiAppData apiAppData = new ApiAppData();
                        apiAppData.icon = BitmapTool.drawableToBitmap(info.applicationInfo.loadIcon(pm));
                        apiAppData.label = (String) info.applicationInfo.loadLabel(pm);
                        apiAppData.packageName = info.applicationInfo.packageName;
                        apiAppData.versionName = info.versionName;
                        apiAppData.versionCode = Long.toString(info.getLongVersionCode());
                        apiAppData.apiVersionName = String.valueOf(info.applicationInfo.metaData.getFloat("superlyricapi_version_name"));
                        apiAppData.apiVersionCode = String.valueOf(info.applicationInfo.metaData.getInt("superlyricapi_version_code"));
                        mediaApiApps.add(apiAppData);
                    }
                }
            }
        }

        sortAppData(mediaApps);
        sortAppData(mediaNetworkApps);
        sortAppData(mediaApiApps);
        sMediaApps = List.copyOf(mediaApps);
        sMediaNetworkApps = List.copyOf(mediaNetworkApps);
        sMediaApiApps = List.copyOf(mediaApiApps);
        AndroidLog.logD(TAG, "!!Success loaded package list!!");
    }

    private static void notifyPackageLoadedListeners(@NonNull List<Runnable> listeners) {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable throwable) {
                AndroidLog.logE(TAG, "Package loaded listener failed", throwable);
            }
        }
    }

    public static List<AppData> getMediaApps() {
        return sMediaApps;
    }

    public static List<ApiAppData> getMediaApiApps() {
        return sMediaApiApps;
    }

    public static List<NetworkAppData> getMediaNetworkApps() {
        return sMediaNetworkApps;
    }

    public static void addPackageLoadedListener(@NonNull Runnable listener) {
        boolean notifyImmediately;
        synchronized (LOAD_LOCK) {
            sPackageLoadedListeners.add(listener);
            notifyImmediately = completedLoadCount > 0;
        }
        if (notifyImmediately) {
            try {
                listener.run();
            } catch (Throwable throwable) {
                AndroidLog.logE(TAG, "Package loaded listener failed", throwable);
            }
        }
    }

    public static void removePackageLoadedListener(@NonNull Runnable listener) {
        sPackageLoadedListeners.remove(listener);
    }

    public static <T> void sortAppData(@NonNull List<T> list) {
        list.sort(new Comparator<T>() {
            @Override
            public int compare(T o1, T o2) {
                String label1 = ((AppData) o1).label.toUpperCase(Locale.ROOT);
                String label2 = ((AppData) o2).label.toUpperCase(Locale.ROOT);
                return COLLATOR.compare(label1, label2);
            }
        });
    }

    private static boolean isMediaNetworkApp(String packageName) {
        NetworkMode mode = SupportApps.sSupportNetworkApps.get(packageName);
        if (mode == null) {
            return false;
        }
        if (mode == NetworkMode.ONLY) {
            return true;
        }

        // 远程 prefs 不可用（Xposed 服务未绑定）时按 Hook 模式处理，避免列表加载崩溃。
        SharedPreferences preferences = Application.getRemotePreferences();
        if (preferences == null) {
            return false;
        }

        Set<String> networks = preferences.getStringSet(PrefsKey.NETWORK_LYRICS_MODE, new HashSet<>());
        return networks.contains(packageName);
    }

    private static boolean hasXposedModule(@NonNull String apkPath) {
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkPath)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.startsWith("META-INF/xposed")) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }

        return false;
    }
}
