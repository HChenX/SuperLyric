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
package com.hchen.superlyric.utils;

import static com.hchen.superlyric.data.SupportApps.sMediaAppPackages;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

import com.hchen.hooktool.data.AppData;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.hooktool.utils.BitmapTool;
import com.hchen.hooktool.utils.PackageTool;
import com.hchen.superlyric.data.apps.ApiAppData;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫描并加载已安装的支持音乐应用与被支持的 API 应用
 *
 * <p>扫描在单线程执行器上串行执行，结果以不可变列表整体替换，任意线程均可读取；
 * 加载期间的重复请求合并为一次重新扫描。加载失败时完成 future 的异常状态，
 * 不通知监听器。
 *
 */
public final class PackageLoader {
    private static final String TAG = "PackageLoader";
    private static final Object LOAD_LOCK = new Object();
    private static volatile List<AppData> sMediaApps = List.of();
    private static volatile List<ApiAppData> sMediaApiApps = List.of();
    private static final List<Runnable> sPackageLoadedListeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);
    private static CompletableFuture<Void> sCurrentLoad;
    private static CompletableFuture<Void> sPendingLoad;
    private static boolean rescanRequested;
    private static long completedLoadCount;

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
                synchronized (LOAD_LOCK) {
                    completedLoadCount++;
                }
                completedFuture.complete(null);
                notifyPackageLoadedListeners(listenersSnapshot);
            } else {
                // 失败不能通知监听器：监听器只拿到上一次的列表，会把加载失败当成刷新完成。
                completedFuture.completeExceptionally(failure);
            }

            if (runAgain) {
                scheduleLoad(context);
            }
        });
    }

    private static void scanPackages(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppData> mediaApps = new ArrayList<>();
        List<ApiAppData> mediaApiApps = new ArrayList<>();
        List<PackageInfo> infos;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            infos = pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA));
        } else {
            infos = pm.getInstalledPackages(PackageManager.GET_META_DATA);
        }

        for (PackageInfo info : infos) {
            try {
                if (sMediaAppPackages.contains(info.packageName)) {
                    mediaApps.add(PackageTool.createAppData(pm, info, true));
                }

                if (info.applicationInfo != null && info.applicationInfo.metaData != null) {
                    boolean isApi = info.applicationInfo.metaData.getBoolean("superlyricapi");
                    if (isApi) {
                        boolean isXposed = info.applicationInfo.metaData.getBoolean("xposedmodule") ||
                            hasXposedModule(info.applicationInfo.sourceDir);
                        if (!isXposed) {
                            ApiAppData apiAppData = new ApiAppData();
                            apiAppData.icon = BitmapTool.drawableToBitmap(info.applicationInfo.loadIcon(pm));
                            apiAppData.label = String.valueOf(info.applicationInfo.loadLabel(pm));
                            apiAppData.packageName = info.applicationInfo.packageName;
                            apiAppData.versionName = info.versionName;
                            apiAppData.versionCode = Long.toString(info.getLongVersionCode());
                            mediaApiApps.add(apiAppData);
                        }
                    }
                }
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Skipping package " + info.packageName, t);
            }
        }

        sortAppData(mediaApps);
        sortAppData(mediaApiApps);
        sMediaApps = List.copyOf(mediaApps);
        sMediaApiApps = List.copyOf(mediaApiApps);
        AndroidLog.logD(TAG, "Successfully loaded package list: media=" + sMediaApps.size() + ", api=" + sMediaApiApps.size());
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

    private static <T extends AppData> void sortAppData(@NonNull List<T> list) {
        list.sort((o1, o2) -> COLLATOR.compare(
            o1.label.toUpperCase(Locale.ROOT),
            o2.label.toUpperCase(Locale.ROOT)));
    }

    private static boolean hasXposedModule(@NonNull String apkPath) {
        try (ZipFile zipFile = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/xposed")) {
                    return true;
                }
            }
        } catch (Exception e) {
            AndroidLog.logW(TAG, "Cannot inspect " + apkPath + "; treating it as a non-Xposed app", e);
        }
        return false;
    }
}
