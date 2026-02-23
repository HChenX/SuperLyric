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
package com.hchen.superlyric.helper;

import static com.hchen.hooktool.core.CoreTool.existsClass;
import static com.hchen.hooktool.core.CoreTool.hookMethod;
import static com.hchen.hooktool.core.CoreTool.returnResult;
import static com.hchen.superlyric.hook.LyricRelease.sendLyric;

import com.hchen.hooktool.hook.IHook;

import java.util.Objects;

/**
 * 通过 QQLite 获取歌词
 *
 * @author 焕晨HChen
 */
public final class QQLiteHelper {
    /**
     * 是否支持 QQLite
     */
    public static boolean isSupportQQLite() {
        return existsClass("com.tencent.qqmusic.core.song.SongInfo");
    }

    public static void hookLyric() {
        if (!isSupportQQLite()) return;

        hookMethod("com.tencent.qqmusiccommon.util.music.RemoteLyricController",
            "BluetoothA2DPConnected",
            returnResult(true)
        );

        hookMethod("com.tencent.qqmusiccommon.util.music.RemoteControlManager",
            "updataMetaData",
            "com.tencent.qqmusic.core.song.SongInfo", String.class,
            new IHook() {
                @Override
                public void before() {
                    String lyric = (String) getArg(1);
                    if (lyric == null || lyric.isEmpty()) return;
                    if (Objects.equals(lyric, "NEED_NOT_UPDATE_TITLE")) return;

                    sendLyric(lyric);
                }
            }
        );
    }
}
