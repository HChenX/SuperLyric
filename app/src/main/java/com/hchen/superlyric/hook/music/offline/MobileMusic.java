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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyric.hook.music.offline.mobilemusic.MobileMusicLyricData;
import com.hchen.superlyric.utils.MeizuFaker;
import com.hchen.superlyricapi.SuperLyricData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 咪咕音乐
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "cmccwm.mobilemusic")
public final class MobileMusic extends AbsPublisher {
    private static final String STATUS_CLASS = "com.migu.music.player.listener.PlayerStatusManager";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean installed;
    private volatile boolean incompatible;
    private volatile boolean installing;
    private boolean running;
    private boolean published;
    private boolean readFailureLogged;
    private int installAttempts;
    private Method getManager;
    private Method getSong;
    private Method getPosition;
    private Method getDuration;
    private Method isPlaying;
    private Field lyricParser;
    private Field parsedSong;
    private Method getViewOffset;
    private Method isPlayerLyricView;
    private Method isTreasureLyricView;
    private OffsetSnapshot liveOffset;
    private SuperLyricData lastData;
    private final Runnable lyricLoop = this::updateLyric;

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        MeizuFaker.depthDeviceMock();
        MeizuFaker.hookNotificationLyric();
        hookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, new AbsHook() {
            @Override
            public void after() {
                if (!installed && !incompatible && !installing && STATUS_CLASS.equals(getArg(0))
                    && getResult() instanceof Class<?> target) {
                    handler.post(() -> installHooks(target.getClassLoader()));
                }
            }
        });
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        retryInstall(context);
    }

    private void retryInstall(Context context) {
        if (installed || incompatible) return;
        installHooks(context.getClassLoader());
        if (!installed && !incompatible && ++installAttempts < 30) {
            handler.postDelayed(() -> retryInstall(context), 1000L);
        }
    }

    private synchronized void installHooks(ClassLoader loader) {
        if (installed || incompatible || installing || loader == null) return;
        installing = true;
        try {
            Class<?> statusClass = Class.forName(STATUS_CLASS, false, loader);
            Class<?> playerClass = Class.forName("com.migu.music.player.PlayerController", false, loader);
            Class<?> managerClass = Class.forName("com.migu.music.lyrics.LrcManager", false, loader);
            Class<?> songClass = Class.forName("com.migu.music.entity.Song", false, loader);
            Class<?> lineClass = Class.forName("com.migu.music.entity.lyrics.LyricsLineInfo", false, loader);
            Class<?> translationClass = Class.forName("com.migu.music.entity.lyrics.TranslateLrcLineInfo", false, loader);
            Class<?> parserClass = Class.forName("com.migu.music.lyrics.utils.LyricsParserUtil", false, loader);
            Class<?> viewClass = Class.forName("com.migu.music.lyrics.view.NormalLyricView", false, loader);
            for (String field : new String[]{"mCurrentParseSong", "mLrcLineList", "mLrcTrcsLineList", "isMrc", "lyricsParser"}) {
                managerClass.getField(field);
            }
            managerClass.getMethod("isStaticLrc");
            parserClass.getMethod("getPlayOffset");
            for (String getter : new String[]{"getLineLyrics", "getStartTime", "getEndTime", "getLyricsWords", "getWordsDisInterval", "getTrcLrc"}) {
                lineClass.getMethod(getter);
            }
            translationClass.getMethod("getStartTime");
            translationClass.getMethod("getLineLyrics");
            for (String getter : new String[]{"isVideoResource", "getSongId", "getContentId", "getLocalPathMd5", "getSongName", "getSingerName", "getAlbum"}) {
                songClass.getMethod(getter);
            }
            Method positionChanged = statusClass.getDeclaredMethod("onPositionChanged",
                int.class, int.class, int.class, String.class);
            Method playStatus = statusClass.getDeclaredMethod("onPlayStatus", boolean.class, int.class);
            Method songChanged = statusClass.getDeclaredMethod("onSongChanged",
                songClass, songClass, int.class, boolean.class);
            Method seekComplete = statusClass.getDeclaredMethod("onSeekComplete", boolean.class);
            getManager = managerClass.getMethod("getIntance");
            getSong = playerClass.getMethod("getUseSong");
            getPosition = playerClass.getMethod("getPlayTime");
            getDuration = playerClass.getMethod("getDurTime");
            isPlaying = playerClass.getMethod("isPlaying");
            lyricParser = managerClass.getField("lyricsParser");
            parsedSong = managerClass.getField("mCurrentParseSong");
            getViewOffset = viewClass.getMethod("getOffset");
            isPlayerLyricView = viewClass.getMethod("isPlayerLyricsMode");
            isTreasureLyricView = viewClass.getMethod("isTreasureLyricsMode");
            Method progressOffset = viewClass.getDeclaredMethod("setProgressOffset", int.class);
            Method currentOffset = viewClass.getDeclaredMethod("setCurrentOffset", int.class);

            hook(positionChanged, new AbsHook() {
                @Override
                public void after() {
                    handler.post(MobileMusic.this::startLoop);
                }
            });
            hook(playStatus, new AbsHook() {
                @Override
                public void after() {
                    boolean playing = (boolean) getArg(0);
                    handler.post(() -> {
                        if (playing) startLoop();
                        else stopLoop();
                    });
                }
            });
            hook(songChanged, new AbsHook() {
                @Override
                public void after() {
                    Object previousSong = getArg(0);
                    Object currentSong = getArg(1);
                    handler.post(() -> {
                        try {
                            if (!MobileMusicLyricData.sameSong(previousSong, currentSong)) liveOffset = null;
                        } catch (Throwable error) {
                            liveOffset = null;
                            logE(tag, "Cannot match Migu changed song", error);
                        }
                        clearPublishedLyric();
                        startLoop();
                    });
                }
            });
            hook(seekComplete, new AbsHook() {
                @Override
                public void after() {
                    handler.post(() -> {
                        lastData = null;
                        startLoop();
                    });
                }
            });
            for (Method offsetSetter : new Method[]{progressOffset, currentOffset}) {
                hook(offsetSetter, new AbsHook() {
                    @Override
                    public void after() {
                        captureOffset(getThisObject());
                    }
                });
            }
            installed = true;
            MeizuFaker.setNotificationLyricEnabled(false);
            logI(tag, "Migu native word-timed lyrics and translation hooks installed");
            handler.post(this::startLoop);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable error) {
            incompatible = true;
            logE(tag, "Cannot install Migu native lyric hooks; keep notification fallback", error);
        } finally {
            installing = false;
        }
    }

    private void startLoop() {
        if (!installed || running) return;
        running = true;
        handler.post(lyricLoop);
    }

    private void captureOffset(Object view) {
        try {
            if (!Boolean.TRUE.equals(isPlayerLyricView.invoke(view))
                && !Boolean.TRUE.equals(isTreasureLyricView.invoke(view))) return;
            Object song = getSong.invoke(null);
            Object manager = getManager.invoke(null);
            if (song == null || manager == null) return;
            if (!MobileMusicLyricData.sameSong(song, parsedSong.get(manager))) return;
            OffsetSnapshot snapshot = new OffsetSnapshot(song, lyricParser.get(manager),
                ((Number) getViewOffset.invoke(view)).longValue());
            handler.post(() -> {
                try {
                    if (!snapshot.sameOffset(liveOffset)) {
                        liveOffset = snapshot;
                        lastData = null;
                    }
                } catch (Throwable error) {
                    logE(tag, "Cannot match Migu live lyric offset", error);
                }
            });
        } catch (Throwable error) {
            logE(tag, "Cannot read Migu live lyric offset", error);
        }
    }

    private void stopLoop() {
        running = false;
        handler.removeCallbacks(lyricLoop);
        clearPublishedLyric();
    }

    private void clearPublishedLyric() {
        lastData = null;
        if (published) {
            sendStop();
            published = false;
        }
    }

    private void updateLyric() {
        if (!running) return;
        try {
            if (!Boolean.TRUE.equals(isPlaying.invoke(null))) {
                stopLoop();
                return;
            }
            Object manager = getManager.invoke(null);
            Object song = getSong.invoke(null);
            SuperLyricData data;
            synchronized (manager) {
                Long offset = liveOffset != null && liveOffset.matches(song, lyricParser.get(manager))
                    ? liveOffset.offset() : null;
                data = MobileMusicLyricData.read(manager, song,
                    ((Number) getPosition.invoke(null)).longValue(),
                    ((Number) getDuration.invoke(null)).longValue(), offset);
            }
            if (!MobileMusicLyricData.sameSong(song, getSong.invoke(null))) {
                clearPublishedLyric();
            } else if (data == null) {
                clearPublishedLyric();
            } else if (!data.equals(lastData)) {
                sendLyric(data);
                lastData = data;
                published = true;
            }
            readFailureLogged = false;
        } catch (Throwable error) {
            clearPublishedLyric();
            if (!readFailureLogged) {
                logE(tag, "Cannot read Migu native lyrics", error);
                readFailureLogged = true;
            }
        }
        if (running) handler.postDelayed(lyricLoop, 50L);
    }

    private record OffsetSnapshot(Object song, Object parser, long offset) {
        private boolean matches(Object currentSong, Object currentParser) throws ReflectiveOperationException {
            return parser == currentParser && MobileMusicLyricData.sameSong(song, currentSong);
        }

        private boolean sameOffset(OffsetSnapshot other) throws ReflectiveOperationException {
            return other != null && offset == other.offset && matches(other.song, other.parser);
        }
    }
}
