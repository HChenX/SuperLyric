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

import android.os.Parcel;

import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;
import com.hchen.superlyricapi.SuperLyricData;

/**
 * API 代理
 *
 * @author 焕晨HChen
 */
public class Api extends LyricRelease {
    @Override
    protected boolean isEnabled() {
        return existsClass("com.hchen.superlyricapi.SuperLyricTool");
    }

    @Override
    protected void init() {
        setStaticField("com.hchen.superlyricapi.SuperLyricTool", "isEnabled", true);

        hookMethod("com.hchen.superlyricapi.SuperLyricPush",
            "onSuperLyric",
            "com.hchen.superlyricapi.SuperLyricData",
            new IHook() {
                @Override
                public void after() {
                    Parcel parcel = (Parcel) callMethod(getArg(0), "marshall");
                    if (parcel != null) {
                        sendSuperLyricData(SuperLyricData.unmarshall(parcel));
                    }
                }
            }
        );

        hookMethod("com.hchen.superlyricapi.SuperLyricPush",
            "onStop",
            "com.hchen.superlyricapi.SuperLyricData",
            new IHook() {
                @Override
                public void after() {
                    Parcel parcel = (Parcel) callMethod(getArg(0), "marshall");
                    if (parcel != null) {
                        sendStop(SuperLyricData.unmarshall(parcel));
                    }
                }
            }
        );
    }
}
