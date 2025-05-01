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

import com.hchen.collect.Collect;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.BaseLyric;

import java.util.Arrays;

/**
 * 华为音乐
 */
@Collect(targetPackage = "com.huawei.music")
public class Huawei extends BaseLyric {
    @Override
    protected void init() {
        hookAllMethod("com.android.mediacenter.localmusic.VehicleLyricControl",
            "isEnableRefreshShowLyric",
            new IHook() {
                @Override
                public void before() {
                    setThisField("mIsBluetoothA2dpConnect", true);
                }
            }
        );

        hookAllMethod("com.android.mediacenter.localmusic.MediaSessionController",
            "updateLyric",
            new IHook() {
                @Override
                public void before() {
                    Object[] lyric = param.args;
                    String lyricWithoutBrackets = Arrays.toString(lyric).substring(1, Arrays.toString(lyric).length() - 1);
                    sendLyric(lyricWithoutBrackets);
                }
            }
        );
    }
}
