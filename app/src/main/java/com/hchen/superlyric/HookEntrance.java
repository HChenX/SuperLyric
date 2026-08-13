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
package com.hchen.superlyric;

import static com.hchen.hooktool.ModuleConfig.LOG_D;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.processor.HookMaps;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.ModuleEntrance;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.hooktool.utils.PrefsTool;
import com.hchen.superlyric.data.LocalConfig;
import com.hchen.superlyric.utils.LyricCacheStore;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hook 入口
 *
 * @author 焕晨HChen
 */
public final class HookEntrance extends ModuleEntrance {
    private static final String TAG = "SuperLyric";

    @Override
    public void initModuleConfig() {
        ModuleConfig.setLogTag(TAG);
        ModuleConfig.setPrefsName("super_lyric_prefs");
        ModuleConfig.setLogLevel(BuildConfig.DEBUG ? LOG_D : LocalConfig.getLogLevelForXposed());
        ModuleConfig.setShowHookSuccessLog(LocalConfig.getLogLevelForXposed() == LOG_D);
        ModuleConfig.setLogExpandPaths(
            "com.hchen.superlyric.hook"
        );
        ModuleConfig.setLogExpandIgnoreClassNames(
            "com.hchen.superlyric.hook.AbsPublisher"
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

    private final HashMap<String, AbsModule> modules = new HashMap<>();

    @Override
    public void handlePackageReady(@NonNull PackageReadyParam param) {
        AndroidLog.logD(TAG, "handlePackageReady: " + param.getClassLoader() + ", " + param.getAppComponentFactory() + ", " + param);
        super.handlePackageReady(param);

        if (HookMaps.ON_PACKAGE_LOADED.containsKey(param.getPackageName())) {
            try {
                int version = PrefsTool.prefs().getInt("super_lyric_dexkit_cache_version", 0);

                ModuleData.setClassLoader(param.getClassLoader());
                DexkitCache.init(
                    "superlyric",
                    param.getClassLoader(),
                    param.getApplicationInfo().sourceDir,
                    param.getApplicationInfo().dataDir,
                    version
                );


                if (modules.containsKey(param.getPackageName())) {
                    AbsModule module = Objects.requireNonNull(modules.get(param.getPackageName()));
                    module.handlePackageReady(param);
                    return;
                }

                for (String path : Objects.requireNonNull(HookMaps.ON_PACKAGE_LOADED.get(param.getPackageName()))) {
                    try {
                        AbsModule module = (AbsModule) HookEntrance.class.getClassLoader()
                            .loadClass(path)
                            .getDeclaredConstructor()
                            .newInstance();

                        module.handlePackageReady(param);
                        modules.put(param.getPackageName(), module);
                    } catch (IllegalAccessException | InstantiationException |
                             InvocationTargetException | NoSuchMethodException |
                             ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
            } finally {
                DexkitCache.close();
            }
        }
    }

    @Override
    public void handleApplicationCreated(@NonNull Context context) {
        AndroidLog.logD(TAG, "handleApplicationCreated: " + context);
        super.handleApplicationCreated(context);

        clearLyricCacheOnFormatUpgrade(context);
        for (AbsModule module : modules.values()) {
            if (module != null) {
                module.handleApplicationCreated(context);
            }
        }
    }

    /**
     * 缓存格式版本升级时清空在线歌词缓存：旧格式缓存与新版本不兼容，整体失效避免脏命中。
     * 通过 SharedPreferences 记录已清理过的格式版本，同版本内不重复清空。
     */
    private void clearLyricCacheOnFormatUpgrade(@NonNull Context context) {
        try {
            int lastCleared = PrefsTool.prefs(context).getInt("super_lyric_cache_format", 0);
            int current = LyricCacheStore.cacheVersion();
            if (lastCleared == current) return;
            PrefsTool.prefs(context).edit().putInt("super_lyric_cache_format", current).apply();
            for (String provider : new String[]{"Netease", "Hihonor", "Spotify"}) {
                LyricCacheStore.clearProviderCache(context, provider);
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
        for (List<String> value : HookMaps.ON_SYSTEM_STARTING.values()) {
            for (String path : value) {
                try {
                    AbsModule module = (AbsModule) HookEntrance.class.getClassLoader()
                        .loadClass(path)
                        .getDeclaredConstructor()
                        .newInstance();
                    module.handleSystemServerStarting(param);
                } catch (IllegalAccessException | InstantiationException |
                         InvocationTargetException | NoSuchMethodException |
                         ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public boolean isHotReloadingAllowed(@NonNull String packageName) {
        return false;
    }

    @NonNull
    @Override
    public Map<String, Object> handleHotReloading(@Nullable Bundle extras) {
        return super.handleHotReloading(extras);
    }

    @Override
    public void handleHotReloaded(@NonNull HotReloadedParam param, @NonNull ClassLoader classLoader) {
        super.handleHotReloaded(param, classLoader);
    }
}
