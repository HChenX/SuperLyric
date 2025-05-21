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
package com.hchen.superlyric.hook.music;

import android.content.Context;

import com.hchen.collect.Collect;
import com.hchen.hooktool.HCInit;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.BaseLyric;

/**
 * 青盐音乐
 */
@Collect(targetPackage = "com.xuncorp.qinalt.music")
public class Qinalt extends BaseLyric {
    @Override
    protected void init() {
        findMethod("com.stub.StubApp",
            "attachBaseContext",
            Context.class,
            new IHook() {
                @Override
                public void after() {
                    Context context = (Context) getArg(0);
                    HCInit.setClassLoader(context.getClassLoader());
                    MeizuHelper.mockDevice();
                    MeizuHelper.getMeizuNotificationLyric();
                }
            }
        );
    }
}
