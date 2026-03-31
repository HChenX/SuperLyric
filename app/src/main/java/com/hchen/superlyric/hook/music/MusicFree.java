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

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.helper.TimeoutHelper;
import com.hchen.superlyric.hook.LyricRelease;
import com.hchen.superlyricapi.AcquisitionMode;

/**
 * MusicFree
 */
@AutoHook(targetPackage = "fun.upup.musicfree")
public final class MusicFree extends LyricRelease {
    @Override 
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        hookMethod("fun.upup.musicfree.lyricUtil.LyricUtilModule",
            "showStatusBarLyric",
            String.class, "com.facebook.react.bridge.ReadableMap", "com.facebook.react.bridge.Promise",
            new AbsHook() {
                @Override
                public void before() {
                    Object promise = getArg(2);
                    callMethod(promise, "resolve", true);
                    setResult(null);
                }
            }
        );

        hookMethod("fun.upup.musicfree.lyricUtil.LyricUtilModule",
            "setStatusBarLyricText",
            String.class, "com.facebook.react.bridge.Promise",
            new AbsHook() {
                @Override
                public void before() {
                    String lyric = (String) getArg(0);
                    if (lyric.isEmpty()) return;

                    TimeoutHelper.start();
                    sendLyric(lyric, 0, AcquisitionMode.HOOK_LYRIC);
                }
            }
        );
    }
}
