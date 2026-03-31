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
import com.hchen.superlyric.hook.LyricRelease;
import com.hchen.superlyricapi.AcquisitionMode;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Poweramp
 */
@AutoHook(targetPackage = "com.maxmpz.audioplayer")
public final class Poweramp extends LyricRelease {
    @Override
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        hook(Arrays.stream(findClass("com.maxmpz.widget.player.list.LyricsFastTextView").getDeclaredMethods())
                .filter(new Predicate<Method>() {
                    @Override
                    public boolean test(Method method) {
                        return method.getParameterCount() == 4 &&
                            Objects.equals(method.getParameterTypes()[1], boolean.class) &&
                            Objects.equals(method.getParameterTypes()[2], int.class) &&
                            Objects.equals(method.getParameterTypes()[3], int.class);
                    }
                }).findFirst().orElseThrow(),
            new AbsHook() {
                @Override
                public void before() {
                    Object xc = getArg(0);
                    int c = (int) getArg(2);
                    String lyricData = xc.toString();
                    String lyric = extractValues(lyricData);

                    if (lyric == null || lyric.isEmpty()) return;
                    if (!Objects.equals(lyric, "null")) {
                        if (c != 0) {
                            sendLyric(lyric, 0, AcquisitionMode.HOOK_LYRIC);
                        }
                    } else {
                        sendStop();
                    }
                }
            }
        );
    }

    private static final Pattern pattern = Pattern.compile("text=(.*?)\\s+scenes=");

    private String extractValues(String text) {
        text = text.replace("\n", " ");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String value = matcher.group(1);
            if (value != null) return value.trim();
            return null;
        }
        return null;
    }
}
