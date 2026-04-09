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

import static com.hchen.superlyric.data.SupportApps.mSupportMediaApp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import com.hchen.hooktool.data.AppData;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.hooktool.utils.BitmapTool;
import com.hchen.hooktool.utils.PackageTool;
import com.hchen.superlyric.data.ApiAppData;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PackageUtils {
    private static final String TAG = "PackageUtils";
    private static final List<AppData> mMediaAppHookList = new ArrayList<>();
    private static final List<ApiAppData> mMediaAppApiList = new ArrayList<>();
    private static final List<Runnable> mAppLoadedListeners = new ArrayList<>();
    private static final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();
    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);
    private static volatile boolean isRunning = false;
    private static volatile boolean isLoaded = false;

    public static void initialPackage(@NonNull Context context) {
        if (!isRunning) {
            isRunning = true;
            mExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        PackageManager pm = context.getPackageManager();
                        List<PackageInfo> infos = pm.getInstalledPackages(PackageManager.GET_META_DATA);
                        for (PackageInfo info : infos) {
                            if (mSupportMediaApp.contains(info.packageName)) {
                                AppData appData = PackageTool.createAppData(pm, info);
                                mMediaAppHookList.add(appData);
                            }

                            if (info.applicationInfo != null && info.applicationInfo.metaData != null) {
                                boolean isApi = info.applicationInfo.metaData.getBoolean("superlyricapi");
                                if (isApi) {
                                    boolean isXposed =
                                        info.applicationInfo.metaData.getBoolean("xposedmodule") || hasXposedModule(info.applicationInfo.sourceDir);
                                    if (!isXposed) {
                                        String apiVersionName = String.valueOf(info.applicationInfo.metaData.getFloat("superlyricapi_version_name"));
                                        String apiVersionCode = String.valueOf(info.applicationInfo.metaData.getInt("superlyricapi_version_code"));

                                        ApiAppData apiAppData = new ApiAppData();
                                        apiAppData.icon = BitmapTool.drawableToBitmap(info.applicationInfo.loadIcon(pm));
                                        apiAppData.label = (String) info.applicationInfo.loadLabel(pm);
                                        apiAppData.packageName = info.applicationInfo.packageName;
                                        apiAppData.versionName = info.versionName;
                                        apiAppData.versionCode = Long.toString(info.getLongVersionCode());
                                        apiAppData.apiVersionName = apiVersionName;
                                        apiAppData.apiVersionCode = apiVersionCode;

                                        mMediaAppApiList.add(apiAppData);
                                    }
                                }
                            }
                        }

                        sortAppDataList(mMediaAppHookList);
                        sortAppDataList(mMediaAppApiList);

                        for (Runnable listener : mAppLoadedListeners) {
                            listener.run();
                        }

                        AndroidLog.logD(TAG, "[initialPackage()] Success loaded app list.");
                    } finally {
                        isLoaded = true;
                        mExecutorService.shutdown();
                    }
                }
            });
        }
    }

    public static List<AppData> getMediaAppHookList() {
        return mMediaAppHookList;
    }

    public static List<ApiAppData> getMediaAppApiList() {
        return mMediaAppApiList;
    }

    public static void addAppLoadedListener(@NonNull Runnable listener) {
        if (isLoaded) {
            listener.run();
            return;
        }
        mAppLoadedListeners.add(listener);
    }

    public static <T> void sortAppDataList(List<T> list) {
        list.sort(new Comparator<T>() {
            @Override
            public int compare(T o1, T o2) {
                String label1 = ((AppData) o1).label.toUpperCase(Locale.ROOT);
                String label2 = ((AppData) o2).label.toUpperCase(Locale.ROOT);
                return COLLATOR.compare(label1, label2);
            }
        });
    }

    private static boolean hasXposedModule(String apkPath) {
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
