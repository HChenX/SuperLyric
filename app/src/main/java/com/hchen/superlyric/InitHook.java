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

 * Copyright (C) 2023-2025 HChenX
 */
package com.hchen.superlyric;

import static com.hchen.hooktool.HCInit.LOG_D;
import static com.hchen.hooktool.HCInit.LOG_I;

import androidx.annotation.NonNull;

import com.hchen.collect.CollectMap;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.hooktool.HCBase;
import com.hchen.hooktool.HCEntrance;
import com.hchen.hooktool.HCInit;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.hook.music.Api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 入口
 *
 * @author 焕晨HChen
 */
public class InitHook extends HCEntrance {
    private static final String TAG = "SuperLyric";

    @NonNull
    @Override
    public HCInit.BasicData initHC(@NonNull HCInit.BasicData basicData) {
        return basicData
            .setTag(TAG)
            .setPrefsName("super_lyric_prefs")
            .setLogLevel(BuildConfig.DEBUG ? LOG_D : LOG_I)
            .setModulePackageName(BuildConfig.APPLICATION_ID)
            .setLogExpandPath("com.hchen.superlyric.hook")
            .setLogExpandIgnoreClassNames("LyricRelease");
    }

    @NonNull
    @Override
    public String[] ignorePackageNameList() {
        return new String[]{
            "com.miui.contentcatcher",
            "com.android.providers.settings",
            "com.android.server.telecom",
            "com.google.android.webview"
        };
    }

    @Override
    public void onLoadPackage(@NonNull XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!CollectMap.ON_LOAD_PACKAGE_MAP.containsKey(loadPackageParam.packageName) && !CollectMap.ON_APPLICATION_MAP.containsKey(loadPackageParam.packageName)) {
            HCInit.initLoadPackageParam(loadPackageParam);
            new Api().onApplication().onLoadPackage();
        } else {
            try {
                HCInit.initLoadPackageParam(loadPackageParam);
                if (loadPackageParam.appInfo != null) {
                    DexkitCache.init(
                        "superlyric",
                        loadPackageParam.classLoader,
                        loadPackageParam.appInfo.sourceDir,
                        loadPackageParam.appInfo.dataDir
                    );
                }

                Set<String> processed = new HashSet<>();
                List<String> onApplicationList = CollectMap.ON_APPLICATION_MAP.get(loadPackageParam.packageName);
                List<String> onLoadPackageList = CollectMap.ON_LOAD_PACKAGE_MAP.get(loadPackageParam.packageName);
                if (onApplicationList != null) {
                    for (String path : onApplicationList) {
                        try {
                            HCBase hcBase = (HCBase) Objects.requireNonNull(InitHook.class.getClassLoader()).loadClass(path).getDeclaredConstructor().newInstance();
                            hcBase.onApplication();
                            if (onLoadPackageList != null) {
                                if (onLoadPackageList.contains(path)) {
                                    hcBase.onLoadPackage();
                                }
                            }
                        } catch (Throwable e) {
                            XposedLog.logE(TAG, e);
                        } finally {
                            processed.add(path);
                        }
                    }
                }
                if (onLoadPackageList != null) {
                    for (String path : onLoadPackageList) {
                        if (!processed.contains(path)) {
                            try {
                                HCBase hcBase = (HCBase) Objects.requireNonNull(InitHook.class.getClassLoader()).loadClass(path).getDeclaredConstructor().newInstance();
                                hcBase.onLoadPackage();
                            } catch (Throwable e) {
                                XposedLog.logE(TAG, e);
                            }
                        }
                    }
                }
            } finally {
                DexkitCache.close();
            }
        }
    }
}
