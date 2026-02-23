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

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.hchen.collect.Collect;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.helper.ScreenHelper;
import com.hchen.superlyric.hook.LyricRelease;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * LX Music
 */
@Collect(targetPackage = "cn.toside.music.mobile")
public class LXMusic extends LyricRelease {
    @Override
    protected void init() {
        // 所需 setCurrentLyric 类是混淆的，从 LyricModule 类逆向获取
        Class<?> lyricModuleClass = findClass("cn.toside.music.mobile.lyric.LyricModule");
        if (lyricModuleClass == null) return;

        ScreenHelper.screenOffNotStopLyric("MusicModule");
        hookMethod(lyricModuleClass,
            "pause",
            "com.facebook.react.bridge.Promise",
            new IHook() {
                @Override
                public void after() {
                    sendStop();
                }
            }
        );

        Field lyricField = null;
        for (Field field : lyricModuleClass.getDeclaredFields()) {
            if (field.getName().equals("lyric")) {
                lyricField = field;
                break;
            }
        }

        if (lyricField != null) {
            Class<?> lyricType = lyricField.getType();
            Field lyricViewField = null;
            for (Field field : lyricType.getDeclaredFields()) {
                if (field.getType().getSuperclass() == Activity.class) {
                    lyricViewField = field;
                    break;
                }
            }

            if (lyricViewField != null) {
                Class<?> lyricViewType = lyricViewField.getType();
                Method lyricMethod = null;
                for (Method method : lyricViewType.getDeclaredMethods()) {
                    if (method.getParameterCount() == 2 &&
                        method.getParameterTypes()[0] == String.class &&
                        method.getParameterTypes()[1] == ArrayList.class) {
                        lyricMethod = method;
                        break;
                    }
                }

                if (lyricMethod != null) {
                    hook(lyricMethod, new IHook() {
                        @Override
                        public void before() {
                            String lyric = (String) getArg(0);
                            if (lyric.isEmpty()) return;

                            sendLyric(lyric);
                        }
                    });
                }
            }
        }

        // 移除歌词悬浮窗
        hookMethod("android.view.WindowManagerImpl",
            "addView",
            View.class, ViewGroup.LayoutParams.class,
            new IHook() {
                @Override
                public void after() {
                    Object view = getArg(0);
                    if (view == null) return;
                    if (view.getClass().getName().contains("cn.toside.music.mobile.lyric")) {
                        callMethod(view, "setVisibility", View.GONE);
                    }
                }
            }
        );
    }
}