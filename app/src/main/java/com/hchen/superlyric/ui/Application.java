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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.hooktool.utils.PrefsTool;
import com.hchen.superlyric.BuildConfig;
import com.hchen.superlyric.data.LocalConfig;
import com.hchen.superlyric.data.PrefsKey;
import com.hchen.superlyricapi.SuperLyric;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块宿主 Application，初始化配置并绑定 Xposed 服务
 *
 */
// 与 android.app.Application 同名，父类保留全限定名。
public class Application extends android.app.Application implements XposedServiceHelper.OnServiceListener {
    private static final Object PREFS_LOCK = new Object();
    private static volatile Context sAppContext;
    private static volatile XposedService mXposedService;
    private static volatile SharedPreferences mRemotePreferences;
    private static final List<Consumer<SharedPreferences>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = this;

        ModuleConfig.setLogTag("SuperLyric");
        ModuleConfig.setPrefsName(PrefsKey.PREFS_NAME);
        ModuleConfig.setLogLevel(BuildConfig.DEBUG ? LOG_D : LocalConfig.getLogLevelForModule(this));

        PrefsTool.prefs(this, PrefsKey.PREFS_NAME);
        XposedServiceHelper.registerListener(this);

        if (!SuperLyric.isAvailable()) {
            AndroidLog.logW("Application",
                "SuperLyric service is not available; install/enable the module and reboot");
        }
    }

    /**
     * 进程级 Application context
     *
     * <p>供生命周期长于 Activity 的对象（例如 ViewModel 的 lambda）使用，避免把 Activity
     * 闭包进 ViewModel。不用于短生命周期的 UI 操作。
     *
     * @throws IllegalStateException {@code onCreate} 尚未执行
     */
    @NonNull
    public static Context getAppContext() {
        Context context = sAppContext;
        if (context == null) {
            throw new IllegalStateException("Application.onCreate has not run yet");
        }
        return context;
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
        try {
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
                try {
                    listener.accept(remotePreferences);
                } catch (Throwable t) {
                    // 一个监听器失败不影响后续监听器，否则界面会停在旧配置上
                    Log.w("SuperLyric", "Prefs-ready listener failed", t);
                }
            }
        } catch (Throwable t) {
            // 远程 prefs 读取失败不得抛回 XposedServiceHelper 的回调线程；
            // 界面保持可用，仅缺少远程配置
            Log.w("SuperLyric", "Failed to bind Xposed service", t);
        }
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        synchronized (PREFS_LOCK) {
            if (mXposedService != service) {
                return;
            }
            mXposedService = null;
            mRemotePreferences = null;
        }
        Log.i("SuperLyric", "Xposed service died");
    }
}
