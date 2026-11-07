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
package com.hchen.superlyric;

import static com.hchen.hooktool.ModuleConfig.LOG_D;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.ModuleEntrance;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.hooktool.utils.PrefsTool;
import com.hchen.processor.HookMaps;
import com.hchen.superlyric.anchors.DexKitRuntime;
import com.hchen.superlyric.data.LocalConfig;
import com.hchen.superlyric.data.PrefsKey;
import com.hchen.superlyric.utils.LyricCacheStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 按目标包名加载 @HookThis 登记的模块。
 */
public final class HookEntrance extends ModuleEntrance {
    private static final String TAG = "SuperLyric";

    @Override
    public void initModuleConfig() {
        ModuleConfig.setLogTag(TAG);
        ModuleConfig.setPrefsName(PrefsKey.PREFS_NAME);
        ModuleConfig.setLogLevel(BuildConfig.DEBUG ? LOG_D : LocalConfig.getLogLevelForXposed());
        ModuleConfig.setShowHookSuccessLog(BuildConfig.DEBUG || LocalConfig.getLogLevelForXposed() == LOG_D);
        ModuleConfig.setLogExpandPaths(
            "com.hchen.superlyric.publisher",
            "com.hchen.superlyric.provider",
            "com.hchen.superlyric.anchors",
            "com.hchen.superlyric.core"
        );
        ModuleConfig.setLogExpandIgnoreClassNames(
            "com.hchen.superlyric.publisher.AbsPublisher"
        );
    }

    @NonNull
    @Override
    public String[] ignorePackages() {
        return new String[]{
            "com.miui.contentcatcher",
            "com.android.providers.settings",
            "com.android.server.telecom",
            "com.google.android.webview"
        };
    }

    private final ConcurrentHashMap<String, List<AbsModule>> modules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClassLoader> moduleClassLoaders = new ConcurrentHashMap<>();
    private final ExecutorService cacheMigrationExecutor = Executors.newSingleThreadExecutor();
    @Nullable
    private volatile Context lastApplicationContext;

    @Override
    public void handlePackageReady(@NonNull PackageReadyParam param) {
        AndroidLog.logD(TAG, "handlePackageReady: " + param.getPackageName() + " with loader: " + param.getClassLoader());
        super.handlePackageReady(param);

        if (HookMaps.ON_PACKAGE_LOADED.containsKey(param.getPackageName())) {
            try {
                int version = PrefsTool.prefs().getInt(PrefsKey.DEXKIT_CACHE_VERSION, 0);

                ModuleData.setClassLoader(param.getClassLoader());
                DexKitRuntime.initialize(param, version);

                ClassLoader previousLoader = moduleClassLoaders.get(param.getPackageName());
                if (modules.containsKey(param.getPackageName()) && previousLoader == param.getClassLoader()) {
                    for (AbsModule module : Objects.requireNonNull(modules.get(param.getPackageName()))) {
                        try {
                            module.handlePackageReady(param);
                        } catch (Throwable t) {
                            // 单个模块失败不能中断其余模块。
                            AndroidLog.logE(TAG, "Module " + module.getClass().getName()
                                + " failed during re-registration; skipping it", t);
                        }
                    }
                    return;
                }
                if (previousLoader != null && previousLoader != param.getClassLoader()) {
                    AndroidLog.logE(TAG, "Ignore package reload with different ClassLoader: " + param.getPackageName());
                    return;
                }

                List<AbsModule> packageModules = new ArrayList<>();
                for (String path : Objects.requireNonNull(HookMaps.ON_PACKAGE_LOADED.get(param.getPackageName()))) {
                    AbsModule module = instantiateModule(path);
                    if (module == null) continue;
                    try {
                        module.handlePackageReady(param);
                        packageModules.add(module);
                    } catch (Throwable t) {
                        AndroidLog.logE(TAG, "Module " + path + " failed during package ready; skipping it", t);
                    }
                }
                modules.put(param.getPackageName(), packageModules);
                moduleClassLoaders.put(param.getPackageName(), param.getClassLoader());
            } finally {
                DexKitRuntime.close();
            }
        }
    }

    @Override
    public void handleApplicationCreated(@NonNull Context context) {
        AndroidLog.logD(TAG, "handleApplicationCreated: " + context.getPackageName());
        super.handleApplicationCreated(context);

        if (lastApplicationContext == context) return;
        cacheMigrationExecutor.execute(() -> clearLyricCacheOnFormatUpgrade(context));
        List<AbsModule> packageModules = modules.get(context.getPackageName());
        if (packageModules != null) {
            for (AbsModule module : packageModules) {
                try {
                    module.handleApplicationCreated(context);
                } catch (Throwable t) {
                    AndroidLog.logE(TAG, "Module " + module.getClass().getName()
                        + " failed during application created; skipping it", t);
                }
            }
        }
        lastApplicationContext = context;
    }

    private void clearLyricCacheOnFormatUpgrade(@NonNull Context context) {
        try {
            int lastCleared = PrefsTool.prefs(context).getInt(PrefsKey.CACHE_FORMAT, 0);
            int current = LyricCacheStore.cacheVersion();
            if (lastCleared == current) return;
            if (!LyricCacheStore.clearOnlineProviderCaches(context)) {
                AndroidLog.logE(TAG, "Lyric cache format migration incomplete; retry on next startup");
                return;
            }
            boolean committed = PrefsTool.prefs(context)
                .edit()
                .putInt(PrefsKey.CACHE_FORMAT, current)
                .commit();
            if (!committed) {
                AndroidLog.logE(TAG, "Failed to persist lyric cache format migration; retry on next startup");
                return;
            }
            AndroidLog.logD(TAG, "Lyric cache cleared on format upgrade: " + lastCleared + " -> " + current);
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to clear lyric cache on format upgrade", t);
        }
    }

    @Override
    public void handleSystemServerStarting(@NonNull SystemServerStartingParam param) {
        super.handleSystemServerStarting(param);

        ModuleData.setClassLoader(param.getClassLoader());
        List<String> systemModules = HookMaps.ON_SYSTEM_STARTING.get("system");
        if (systemModules == null) return;
        for (String path : systemModules) {
            AbsModule module = instantiateModule(path);
            if (module == null) continue;
            try {
                module.handleSystemServerStarting(param);
            } catch (Throwable t) {
                AndroidLog.logE(TAG, "Module " + path + " failed during system start; skipping it", t);
            }
        }
    }

    @Nullable
    private static AbsModule instantiateModule(String path) {
        try {
            return (AbsModule) HookEntrance.class.getClassLoader()
                .loadClass(path)
                .getDeclaredConstructor()
                .newInstance();
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to load module " + path + "; skipping it", t);
            return null;
        }
    }

    @Override
    public boolean isHotReloadingAllowed(@NonNull String packageName) {
        return false;
    }
}
