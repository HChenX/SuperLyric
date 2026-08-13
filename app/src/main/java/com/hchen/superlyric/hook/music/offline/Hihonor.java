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
package com.hchen.superlyric.hook.music.offline;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyric.patches.netease.NeteaseLogicHijacking;
import com.hchen.superlyric.utils.MeizuFaker;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 荣耀音乐。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.hihonor.cloudmusic")
public final class Hihonor extends AbsPublisher {
    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        NeteaseLogicHijacking.bypassPackProtection();
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);

        MeizuFaker.shallowLayerDeviceMock();
        MeizuFaker.hookNotificationLyric();
        NeteaseLogicHijacking.hookLockScreenPermission();
    }
}
