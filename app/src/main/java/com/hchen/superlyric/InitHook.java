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
import static com.hchen.hooktool.log.XposedLog.logE;

import androidx.annotation.NonNull;

import com.hchen.collect.CollectMap;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.hooktool.HCBase;
import com.hchen.hooktool.HCEntrance;
import com.hchen.hooktool.HCInit;
import com.hchen.superlyric.hook.music.Api;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook 入口
 *
 * @author 焕晨HChen
 */
public class InitHook extends HCEntrance {
    private static final String TAG = "SuperLyric";
    private static final HashMap<String, HashMap<String, HookInstanceData>> mHookInstanceDataMap = new HashMap<>();

    static {
        mHookInstanceDataMap.clear();
        BiFunction<String, String, HookInstanceData> biFunction = (packageName, fullClassPath) -> {
            Map<String, HookInstanceData> map = mHookInstanceDataMap.computeIfAbsent(packageName, k -> new HashMap<>());

            return map.computeIfAbsent(fullClassPath, k -> {
                try {
                    Class<?> clazz = Objects.requireNonNull(InitHook.class.getClassLoader()).loadClass(fullClassPath);
                    HCBase hcBase = (HCBase) clazz.getDeclaredConstructor().newInstance();
                    return new HookInstanceData(hcBase, packageName, fullClassPath, false, false, false);
                } catch (Throwable throwable) {
                    logE(TAG, "Failed load class!!", throwable);
                    return null;
                }
            });
        };

        CollectMap.getOnLoadPackageMap().forEach((packageName, fullClassPaths) -> {
            for (String fullClassPath : fullClassPaths) {
                HookInstanceData data = biFunction.apply(packageName, fullClassPath);
                if (data != null) data.isOnLoadPackage = true;
            }
        });
        CollectMap.getOnApplicationMap().forEach((packageName, fullClassPaths) -> {
            for (String fullClassPath : fullClassPaths) {
                HookInstanceData data = biFunction.apply(packageName, fullClassPath);
                if (data != null) data.isOnApplication = true;
            }
        });
        CollectMap.getOnZygoteList().forEach((packageName, fullClassPaths) -> {
            for (String fullClassPath : fullClassPaths) {
                HookInstanceData data = biFunction.apply(packageName, fullClassPath);
                if (data != null) data.isLoadOnZygote = true;
            }
        });
    }

    private static class HookInstanceData {
        @NonNull
        HCBase hcBase;
        @NonNull
        String packageName;
        @NonNull
        String fullClassPath;
        boolean isOnLoadPackage;
        boolean isOnApplication;
        boolean isLoadOnZygote;

        public HookInstanceData(@NonNull HCBase hcBase, @NonNull String packageName, @NonNull String fullClassPath,
                                boolean isOnLoadPackage, boolean isOnApplication, boolean isLoadOnZygote) {
            this.hcBase = hcBase;
            this.packageName = packageName;
            this.fullClassPath = fullClassPath;
            this.isOnLoadPackage = isOnLoadPackage;
            this.isOnApplication = isOnApplication;
            this.isLoadOnZygote = isLoadOnZygote;
        }

        @NonNull
        @Override
        public String toString() {
            return "HookInstanceData{" +
                "hcBase=" + hcBase +
                ", packageName='" + packageName + '\'' +
                ", fullClassPath='" + fullClassPath + '\'' +
                ", isOnLoadPackage=" + isOnLoadPackage +
                ", isLoadOnZygote=" + isLoadOnZygote +
                ", isOnApplication=" + isOnApplication +
                '}';
        }
    }

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
        if (!CollectMap.getTargetPackages().contains(loadPackageParam.packageName)) {
            HCInit.initLoadPackageParam(loadPackageParam);
            new Api().onApplication().onLoadPackage();
            return;
        }

        try {
            if (loadPackageParam.appInfo != null) {
                DexkitCache.init(
                    "superlyric",
                    loadPackageParam.classLoader,
                    loadPackageParam.appInfo.sourceDir,
                    loadPackageParam.appInfo.dataDir
                );
            }
            if (mHookInstanceDataMap.containsKey(loadPackageParam.packageName)) {
                HCInit.initLoadPackageParam(loadPackageParam);
                for (HookInstanceData data : Objects.requireNonNull(mHookInstanceDataMap.get(loadPackageParam.packageName)).values()) {
                    if (data.isOnApplication) data.hcBase.onApplication();
                    if (data.isOnLoadPackage) data.hcBase.onLoadPackage();
                }
            }
        } catch (Throwable e) {
            logE(TAG, "InitHook error: ", e);
        } finally {
            DexkitCache.close();
        }
    }

    @Override
    public void onInitZygote(@NonNull StartupParam startupParam) throws Throwable {
        for (HashMap<String, HookInstanceData> map : mHookInstanceDataMap.values()) {
            for (HookInstanceData data : map.values()) {
                if (data.isLoadOnZygote) data.hcBase.onZygote();
            }
        }
    }
}
