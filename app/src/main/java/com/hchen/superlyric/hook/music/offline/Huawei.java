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

import androidx.annotation.NonNull;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;

import java.util.Arrays;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 华为音乐
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.huawei.music")
public final class Huawei extends AbsPublisher {
    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);

        hookAllMethod("com.android.mediacenter.localmusic.VehicleLyricControl",
            "isEnableRefreshShowLyric",
            new AbsHook() {
                @Override
                public void before() {
                    setField(getThisObject(), "mIsBluetoothA2dpConnect", true);
                }
            }
        );
        hookAllMethod("com.android.mediacenter.localmusic.MediaSessionController",
            "updateLyric",
            new AbsHook() {
                @Override
                public void before() {
                    Object[] lyric = getArgs();
                    String lyricWithoutBrackets = Arrays.toString(lyric).substring(1, Arrays.toString(lyric).length() - 1);
                    sendLyric(lyricWithoutBrackets);
                }
            }
        );
    }
}
