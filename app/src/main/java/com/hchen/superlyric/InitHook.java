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
import static com.hchen.hooktool.ModuleConfig.LOG_I;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hchen.auto.HookData;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.ModuleEntrance;
import com.hchen.hooktool.utils.PrefsTool;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Hook 入口
 *
 * @author 焕晨HChen
 */
public final class InitHook extends ModuleEntrance {
    private static final String TAG = "SuperLyric";

    @Override
    public void initModuleConfig() {
        ModuleConfig.setLogTag(TAG);
        ModuleConfig.setLogLevel(BuildConfig.DEBUG ? LOG_D : LOG_I);
        ModuleConfig.setPrefsName("super_lyric_prefs");
        ModuleConfig.setShowHookSuccessLog(BuildConfig.DEBUG);
        ModuleConfig.setLogExpandPaths("com.hchen.superlyric.hook");
        ModuleConfig.setLogExpandIgnoreClassNames("LyricRelease");
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
        super.handlePackageReady(param);

        if (HookData.ON_PACKAGE_LOADED.containsKey(param.getPackageName())) {
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

                for (String path : Objects.requireNonNull(HookData.ON_PACKAGE_LOADED.get(param.getPackageName()))) {
                    try {
                        AbsModule module = (AbsModule) InitHook.class.getClassLoader()
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
        super.handleApplicationCreated(context);

        for (AbsModule module : modules.values()) {
            if (module != null) {
                module.handleApplicationCreated(context);
            }
        }
    }

    @Override
    public void handleSystemServerStarting(@NonNull SystemServerStartingParam param) {
        super.handleSystemServerStarting(param);

        ModuleData.setClassLoader(param.getClassLoader());
        for (List<String> value : HookData.ON_SYSTEM_STARTING.values()) {
            for (String path : value) {
                try {
                    AbsModule module = (AbsModule) InitHook.class.getClassLoader()
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
}
