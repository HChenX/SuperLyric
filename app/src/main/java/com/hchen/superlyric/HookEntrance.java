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

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.ModuleEntrance;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.hooktool.utils.PrefsTool;
import com.hchen.processor.HookMaps;
import com.hchen.superlyric.data.LocalConfig;
import com.hchen.superlyric.utils.LyricCacheStore;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SuperLyric 核心 Hook 入口。
 * <p>
 * 按目标包名加载 {@code HookMaps} 中登记的模块：宿主音乐应用对应各自的
 * {@link com.hchen.superlyric.provider.base.MusicProvider} 子类，
 * {@code system} 对应 {@link com.hchen.superlyric.core.SuperLyricProxy}。
 * <p>
 * 注册表由 {@code @HookThis} 注解在编译期生成，因此新增一个应用支持
 * 只需新增一个带注解的 Provider 类，不需要改动本类。
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
        ModuleConfig.setShowHookSuccessLog(BuildConfig.DEBUG || LocalConfig.getLogLevelForXposed() == LOG_D);
        ModuleConfig.setLogExpandPaths(
            "com.hchen.superlyric.publisher",
            "com.hchen.superlyric.provider",
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

    private final HashMap<String, List<AbsModule>> modules = new HashMap<>();
    private final HashMap<String, ClassLoader> moduleClassLoaders = new HashMap<>();
    private final ExecutorService cacheMigrationExecutor = Executors.newSingleThreadExecutor();
    @Nullable
    private Context lastApplicationContext;

    @Override
    public void handlePackageReady(@NonNull PackageReadyParam param) {
        AndroidLog.logD(TAG, "handlePackageReady: " + param.getPackageName() + " with loader: " + param.getClassLoader());
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

                ClassLoader previousLoader = moduleClassLoaders.get(param.getPackageName());
                if (modules.containsKey(param.getPackageName()) && previousLoader == param.getClassLoader()) {
                    for (AbsModule module : Objects.requireNonNull(modules.get(param.getPackageName()))) {
                        try {
                            module.handlePackageReady(param);
                        } catch (Throwable t) {
                            // 同上：单个模块重新注册失败不得中断其余模块，也不得抛回框架
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
                    AbsModule module;
                    try {
                        module = (AbsModule) HookEntrance.class.getClassLoader()
                            .loadClass(path)
                            .getDeclaredConstructor()
                            .newInstance();
                    } catch (IllegalAccessException | InstantiationException |
                             InvocationTargetException | NoSuchMethodException |
                             ClassNotFoundException e) {
                        // 常见成因：增量构建残留的 HookMaps 引用了已删除的类
                        AndroidLog.logE(TAG, "Failed to load module " + path + "; skipping it", e);
                        continue;
                    }

                    try {
                        module.handlePackageReady(param);
                        packageModules.add(module);
                    } catch (Throwable t) {
                        // 模块自己的代码（装钩子、建编排器、DexKit 查找）抛什么都不能中断
                        // 其余模块的注册，更不能把异常抛回 Xposed 的加载路径——宿主稳定性优先。
                        // 必须用 Throwable 而不是 Exception：残留的 HookMaps 会抛
                        // NoClassDefFoundError（Error 而非 Exception），而它正是最常见的成因
                        AndroidLog.logE(TAG, "Module " + path + " failed during package ready; skipping it", t);
                    }
                }
                modules.put(param.getPackageName(), packageModules);
                moduleClassLoaders.put(param.getPackageName(), param.getClassLoader());
            } finally {
                DexkitCache.close();
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
                module.handleApplicationCreated(context);
            }
        }
        lastApplicationContext = context;
    }

    private void clearLyricCacheOnFormatUpgrade(@NonNull Context context) {
        try {
            int lastCleared = PrefsTool.prefs(context).getInt("super_lyric_cache_format", 0);
            int current = LyricCacheStore.cacheVersion();
            if (lastCleared == current) return;
            if (!LyricCacheStore.clearOnlineProviderCaches(context)) {
                AndroidLog.logE(TAG, "Lyric cache format migration incomplete; retry on next startup");
                return;
            }
            boolean committed = PrefsTool.prefs(context)
                .edit()
                .putInt("super_lyric_cache_format", current)
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
        for (List<String> value : HookMaps.ON_SYSTEM_STARTING.values()) {
            for (String path : value) {
                AbsModule module;
                try {
                    module = (AbsModule) HookEntrance.class.getClassLoader()
                        .loadClass(path)
                        .getDeclaredConstructor()
                        .newInstance();
                } catch (IllegalAccessException | InstantiationException |
                         InvocationTargetException | NoSuchMethodException |
                         ClassNotFoundException e) {
                    AndroidLog.logE(TAG, "Failed to load module " + path + "; skipping it", e);
                    continue;
                }

                try {
                    module.handleSystemServerStarting(param);
                } catch (Throwable t) {
                    // 同 packageReady：模块自己的代码抛什么都不能把异常抛回框架
                    AndroidLog.logE(TAG, "Module " + path + " failed during system start; skipping it", t);
                }
            }
        }
    }

    @Override
    public boolean isHotReloadingAllowed(@NonNull String packageName) {
        return false;
    }
}
