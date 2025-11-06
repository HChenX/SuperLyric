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
import com.hchen.hooktool.exception.NonSingletonException;
import com.hchen.hooktool.helper.Any;
import com.hchen.hooktool.helper.RangeHelper;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Poweramp
 */
@Collect(targetPackage = "com.maxmpz.audioplayer")
public class Poweramp extends LyricRelease {
    @Override
    protected void init() {
        findMethodPro("com.maxmpz.widget.player.list.LyricsFastTextView")
            .withParamCount(4, RangeHelper.EQ)
            .withParamClasses(Any.class, boolean.class, int.class, int.class)
            .singleOrThrow(new Supplier<NonSingletonException>() {
                @Override
                public NonSingletonException get() {
                    return new NonSingletonException("Failed to find method, with 4 params:[Any, boolean, int, int].");
                }
            })
            .hook(
                new IHook() {
                    @Override
                    public void before() {
                        Object xc = getArg(0);
                        int c = (int) getArg(2);
                        String lyricData = xc.toString();
                        String lyric = extractValues(lyricData);

                        if (lyric == null || lyric.isEmpty()) return;
                        if (!Objects.equals(lyric, "null")) {
                            if (c != 0) {
                                sendLyric(lyric);
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
