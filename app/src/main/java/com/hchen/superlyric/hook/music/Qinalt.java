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
package com.hchen.superlyric.hook.music;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.helper.MeizuHelper;
import com.hchen.superlyric.hook.LyricRelease;

/**
 * 青盐音乐
 */
@AutoHook(targetPackage = "com.xuncorp.qinalt.music")
public final class Qinalt extends LyricRelease {
    @Override 
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        findMethod("com.stub.StubApp",
            "attachBaseContext",
            Context.class,
            new AbsHook() {
                @Override
                public void after() {
                    Context context = (Context) getArg(0);
                    ModuleData.setClassLoader(context.getClassLoader());

                    MeizuHelper.depthDeviceMock();
                    MeizuHelper.hookNotificationLyric();
                }
            }
        );
    }
}
