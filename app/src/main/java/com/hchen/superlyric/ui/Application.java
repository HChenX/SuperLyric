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
package com.hchen.superlyric.ui;

import static com.hchen.hooktool.ModuleConfig.LOG_D;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.utils.PrefsTool;
import com.hchen.superlyric.BuildConfig;
import com.hchen.superlyric.data.LocalConfig;
import com.hchen.superlyric.data.PrefsKey;
import com.hchen.superlyricapi.SuperLyricHelper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块宿主 Application：初始化配置与 Xposed 服务绑定。
 *
 * @author 焕晨HChen
 */
public class Application extends android.app.Application implements XposedServiceHelper.OnServiceListener {
    private static final Object PREFS_LOCK = new Object();
    private static volatile boolean isXposedActive = false;
    private static volatile XposedService mXposedService;
    private static volatile SharedPreferences mRemotePreferences;
    private static final List<Consumer<SharedPreferences>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        ModuleConfig.setLogTag("SuperLyric");
        ModuleConfig.setPrefsName("super_lyric_prefs");
        ModuleConfig.setLogLevel(BuildConfig.DEBUG ? LOG_D : LocalConfig.getLogLevelForModule(this));

        PrefsTool.prefs(this, "super_lyric_prefs");
        XposedServiceHelper.registerListener(this);

        if (SuperLyricHelper.isAvailable()) {
            SuperLyricHelper.registerPublisher();
        }
    }

    public static boolean isXposedActive() {
        return isXposedActive;
    }

    public static SharedPreferences getRemotePreferences() {
        return mRemotePreferences;
    }

    public static void addPrefsReadyListener(@NonNull Consumer<SharedPreferences> listener) {
        SharedPreferences remotePreferences;
        synchronized (PREFS_LOCK) {
            listeners.add(listener);
            remotePreferences = mRemotePreferences;
        }
        if (remotePreferences != null) {
            listener.accept(remotePreferences);
        }
    }

    public static void removePrefsReadyListener(@NonNull Consumer<SharedPreferences> listener) {
        listeners.remove(listener);
    }

    @Override
    public void onServiceBind(@NonNull XposedService service) {
        isXposedActive = true;
        SharedPreferences remotePreferences = service.getRemotePreferences(ModuleConfig.getPrefsName());
        List<Consumer<SharedPreferences>> listenersSnapshot;
        synchronized (PREFS_LOCK) {
            mXposedService = service;
            mRemotePreferences = remotePreferences;
            listenersSnapshot = List.copyOf(listeners);
        }

        PrefsTool.prefs(this)
            .edit()
            .putInt(PrefsKey.LOG_LEVEL, remotePreferences.getInt(PrefsKey.LOG_LEVEL, 0))
            .apply();

        for (Consumer<SharedPreferences> listener : listenersSnapshot) {
            listener.accept(remotePreferences);
        }
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        synchronized (PREFS_LOCK) {
            if (mXposedService != service) {
                return;
            }
            isXposedActive = false;
            mXposedService = null;
            mRemotePreferences = null;
        }
    }
}
