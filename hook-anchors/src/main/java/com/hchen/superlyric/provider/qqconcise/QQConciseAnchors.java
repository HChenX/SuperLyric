/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.qqconcise;

import android.app.Application;

import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.anchors.AnchorBus;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.Consumer;

public final class QQConciseAnchors {
    private static final String TAG = "QQConciseAnchors";
    private static final String SONG_INFO = "com.tencent.qqmusic.openapisdk.model.SongInfo";
    private static final String PLAYER_SDK = "com.tencent.qqmusic.openapisdk.core.OpenApiSDK";
    private static final AnchorBus<LyricEvent> BUS = new AnchorBus<>();
    private static boolean installed;

    private QQConciseAnchors() {
    }

    public record Song(long id, String mid, String title, String artist, long durationMs) {
    }

    public record LyricEvent(Song song, boolean qrc, String main, String translation, String phonetic) {
    }

    public static synchronized void install(Consumer<LyricEvent> listener) {
        if (!"com.tencent.freemusic".equals(Application.getProcessName())) return;
        BUS.subscribe(listener);
        if (installed) return;
        installed = true;

        Class<?> manager = DexkitCache.findMember("qq_concise_lyric_manager_v1", new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) {
                return bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingEqStrings("loadOver lyric suc")))
                    .singleOrNull();
            }
        });
        Class<?> songInfo = CoreTool.findClassIfExists(SONG_INFO);
        if (manager == null || songInfo == null) {
            AndroidLog.logW(TAG, "QQ Music Concise lyric manager or SongInfo unavailable");
            return;
        }
        Class<?>[] signature = {
            boolean.class, String.class, boolean.class, String.class, String.class, long.class, songInfo
        };
        Method target = Arrays.stream(manager.getDeclaredMethods())
            .filter(method -> method.getReturnType() == void.class
                && Arrays.equals(method.getParameterTypes(), signature))
            .findFirst()
            .orElse(null);
        if (target == null) {
            AndroidLog.logW(TAG, "QQ Music Concise lyric callback unavailable");
            return;
        }
        target.setAccessible(true);
        CoreTool.hook(target, new AbsHook() {
            @Override
            public void before() {
                if (!Boolean.TRUE.equals(getArg(0)) || !(getArg(1) instanceof String main) || main.isBlank()) {
                    return;
                }
                Song song = readSong(getArg(6));
                if (song != null) {
                    BUS.emit(new LyricEvent(song, Boolean.TRUE.equals(getArg(2)), main,
                        (String) getArg(3), (String) getArg(4)));
                }
            }
        });
        AndroidLog.logI(TAG, "Hooked QQ Music Concise lyric callback");
    }

    @Nullable
    public static Song currentSong() {
        try {
            Object player = CoreTool.callStaticMethodIfExists(PLAYER_SDK, "getPlayerApi");
            return player == null ? null : readSong(CoreTool.callMethodIfExists(player, "getCurrentSongInfo"));
        } catch (RuntimeException | LinkageError unavailable) {
            // The SDK can be inaccessible while the host is starting.
            return null;
        }
    }

    @Nullable
    private static Song readSong(@Nullable Object song) {
        if (song == null) return null;
        Object id = CoreTool.callMethodIfExists(song, "getSongId");
        Object mid = CoreTool.callMethodIfExists(song, "getSongMid");
        Object title = CoreTool.callMethodIfExists(song, "getSongName");
        Object artist = CoreTool.callMethodIfExists(song, "getSingerName");
        Object seconds = CoreTool.callMethodIfExists(song, "getSongPlayTime");
        return new Song(
            id instanceof Number number ? number.longValue() : 0L,
            mid instanceof String text ? text : "",
            title instanceof String text ? text : "",
            artist instanceof String text ? text : "",
            seconds instanceof Number number ? number.longValue() * 1_000L : 0L
        );
    }
}
