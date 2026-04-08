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
import static com.hchen.hooktool.ModuleConfig.LOG_I;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.utils.PrefsTool;
import com.hchen.superlyric.BuildConfig;
import com.hchen.superlyricapi.SuperLyricHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class Application extends android.app.Application implements XposedServiceHelper.OnServiceListener {
    private static boolean isXposedActive = false;
    private static SharedPreferences mRemotePreferences;
    private static final List<Consumer<SharedPreferences>> listeners = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();

        ModuleConfig.setLogTag("SuperLyric");
        ModuleConfig.setLogLevel(BuildConfig.DEBUG ? LOG_D : LOG_I);
        ModuleConfig.setPrefsName("super_lyric_prefs");

        PrefsTool.prefs(this, "super_lyric_prefs");
        XposedServiceHelper.registerListener(this);

        SuperLyricHelper.registerPublisher();
    }

    public static boolean isXposedActive() {
        return isXposedActive;
    }

    public static SharedPreferences getRemotePreferences() {
        return mRemotePreferences;
    }

    public static void addPrefsReadyListener(Consumer<SharedPreferences> listener) {
        if (mRemotePreferences != null) {
            listener.accept(mRemotePreferences);
        } else {
            listeners.add(listener);
        }
    }

    @Override
    public void onServiceBind(@NonNull XposedService service) {
        isXposedActive = true;
        mRemotePreferences = service.getRemotePreferences(ModuleConfig.getPrefsName());
        ModuleData.addRemotePreferences(ModuleConfig.getPrefsName(), mRemotePreferences);

        for (Consumer<SharedPreferences> l : listeners) {
            l.accept(mRemotePreferences);
        }
        listeners.clear();
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        isXposedActive = false;
        mRemotePreferences = null;
        listeners.clear();
        ModuleData.clearRemotePreferences();
    }
}
