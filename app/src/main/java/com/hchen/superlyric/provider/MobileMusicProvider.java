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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.provider;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.mobilemusic.MobileMusicLyricData;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyricapi.SuperLyricData;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 咪咕音乐统一歌词提供者，已验证版本为 8.9.3。
 * <p>
 * 从 {@code LrcManager} 提取整首歌词、逐字时间和翻译，由统一调度器发布。
 * 宿主使用加固类加载器，因此在 Application 初始化及目标类加载后尝试安装 Hook。
 * 播放进度与歌曲标识以 {@code PlayerController} 为准，避免 MediaSession 更新滞后导致串歌。
 */
@HookThis(targetPackage = "cmccwm.mobilemusic")
public final class MobileMusicProvider extends UnifiedLyricProvider {
    private static final String TAG = "MobileMusicProvider";
    private static final String STATUS_CLASS = "com.migu.music.player.listener.PlayerStatusManager";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshTask = this::refresh;
    private volatile boolean mInstalled;
    private volatile boolean mIncompatible;
    private volatile boolean mInstalling;
    private int mInstallAttempts;
    private boolean mReadFailureLogged;
    private boolean mPlaying;
    private int mPendingAttempts;
    private long mLyricRevision;
    private Object mActiveSong;
    private TrackContext mActiveTrack;
    private OffsetSnapshot mLiveOffset;
    private LyricSnapshot mLastSnapshot;
    private Method mGetManager;
    private Method mGetSong;
    private Method mGetPosition;
    private Method mGetDuration;
    private Method mIsPlaying;
    private Method mGetParserOffset;
    private Method mIsStatic;
    private Method mIsVideo;
    private Method mGetViewOffset;
    private Method mIsPlayerView;
    private Method mIsTreasureView;
    private Method mGetSongId;
    private Method mGetContentId;
    private Method mGetLocalMd5;
    private Method mGetTitle;
    private Method mGetArtist;
    private Method mGetAlbum;
    private Field mLyricParser;
    private Field mParsedSong;
    private Field mLyricLines;
    private Field mTranslationLines;
    private Field mIsMrc;

    @NonNull
    @Override
    public ProviderCapability capability() {
        return ProviderCapability.FULL_HOOK_ONLY;
    }

    @NonNull
    @Override
    protected IHookLyricEngine createHookEngine() {
        return new IHookLyricEngine() {
            @Override
            public void initHooks() {
                // 加固类可能在 Application.onCreate 后才对宿主类加载器可见。
                hookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, new AbsHook() {
                    @Override
                    public void after() {
                        if (!mInstalled && !mIncompatible && !mInstalling && STATUS_CLASS.equals(getArg(0))
                            && getResult() instanceof Class<?> target) {
                            mHandler.post(() -> installHooks(target.getClassLoader()));
                        }
                    }
                });
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                // 歌曲标识在主线程中更新，再通知统一调度器。
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                return null; // 校验解析器对应的歌曲后，异步提交整首歌词。
            }
        };
    }

    @Override
    protected void hookMediaSession() {
        // 统一使用宿主的时钟和歌曲标识，避免 MediaSession 在切歌时更新滞后。
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        mHandler.post(() -> retryInstall(context.getClassLoader()));
    }

    private void retryInstall(ClassLoader loader) {
        if (mInstalled || mIncompatible) return;
        installHooks(loader);
        if (!mInstalled && !mIncompatible && ++mInstallAttempts < 30) {
            mHandler.postDelayed(() -> retryInstall(loader), 1000L);
        }
    }

    private synchronized void installHooks(ClassLoader loader) {
        if (mInstalled || mIncompatible || mInstalling || loader == null) return;
        mInstalling = true;
        try {
            Class<?> status = Class.forName(STATUS_CLASS, false, loader);
            Class<?> player = Class.forName("com.migu.music.player.PlayerController", false, loader);
            Class<?> manager = Class.forName("com.migu.music.lyrics.LrcManager", false, loader);
            Class<?> song = Class.forName("com.migu.music.entity.Song", false, loader);
            Class<?> line = Class.forName("com.migu.music.entity.lyrics.LyricsLineInfo", false, loader);
            Class<?> translation = Class.forName("com.migu.music.entity.lyrics.TranslateLrcLineInfo", false, loader);
            Class<?> parser = Class.forName("com.migu.music.lyrics.utils.LyricsParserUtil", false, loader);
            Class<?> view = Class.forName("com.migu.music.lyrics.view.NormalLyricView", false, loader);
            for (String getter : List.of("getLineLyrics", "getStartTime", "getEndTime", "getLyricsWords", "getWordsDisInterval", "getTrcLrc")) {
                line.getMethod(getter);
            }
            translation.getMethod("getStartTime");
            translation.getMethod("getLineLyrics");
            mGetManager = manager.getMethod("getIntance");
            mGetSong = player.getMethod("getUseSong");
            mGetPosition = player.getMethod("getPlayTime");
            mGetDuration = player.getMethod("getDurTime");
            mIsPlaying = player.getMethod("isPlaying");
            mLyricParser = manager.getField("lyricsParser");
            mParsedSong = manager.getField("mCurrentParseSong");
            mLyricLines = manager.getField("mLrcLineList");
            mTranslationLines = manager.getField("mLrcTrcsLineList");
            mIsMrc = manager.getField("isMrc");
            mIsStatic = manager.getMethod("isStaticLrc");
            mGetParserOffset = parser.getMethod("getPlayOffset");
            mIsVideo = song.getMethod("isVideoResource");
            mGetSongId = song.getMethod("getSongId");
            mGetContentId = song.getMethod("getContentId");
            mGetLocalMd5 = song.getMethod("getLocalPathMd5");
            mGetTitle = song.getMethod("getSongName");
            mGetArtist = song.getMethod("getSingerName");
            mGetAlbum = song.getMethod("getAlbum");
            mGetViewOffset = view.getMethod("getOffset");
            mIsPlayerView = view.getMethod("isPlayerLyricsMode");
            mIsTreasureView = view.getMethod("isTreasureLyricsMode");
            Method positionChanged = status.getDeclaredMethod("onPositionChanged", int.class, int.class, int.class, String.class);
            Method playStatus = status.getDeclaredMethod("onPlayStatus", boolean.class, int.class);
            Method songChanged = status.getDeclaredMethod("onSongChanged", song, song, int.class, boolean.class);
            Method seekComplete = status.getDeclaredMethod("onSeekComplete", boolean.class);
            Method progressOffset = view.getDeclaredMethod("setProgressOffset", int.class);
            Method currentOffset = view.getDeclaredMethod("setCurrentOffset", int.class);
            Method parsed = manager.getDeclaredMethod("parseLyricsFile", String.class, song);
            Method translated = manager.getDeclaredMethod("parseTrc", File.class);
            Method noLyric = manager.getDeclaredMethod("processNoLrc", song, boolean.class, Boolean.class);
            for (Method event : new Method[]{positionChanged, playStatus, songChanged, seekComplete}) {
                hook(event, new AbsHook() {
                    @Override
                    public void after() {
                        scheduleRefresh();
                    }
                });
            }
            for (Method setter : new Method[]{progressOffset, currentOffset}) {
                hook(setter, new AbsHook() {
                    @Override
                    public void after() {
                        captureOffset(getThisObject());
                    }
                });
            }
            for (Method event : new Method[]{parsed, translated, noLyric}) {
                hook(event, new AbsHook() {
                    @Override
                    public void after() {
                        mHandler.post(() -> {
                            // 宿主会复用歌词列表，内容替换后列表长度也可能不变。
                            mLyricRevision++;
                            mPendingAttempts = 20;
                            mHandler.removeCallbacks(mRefreshTask);
                            refresh();
                        });
                    }
                });
            }
            mInstalled = true;
            AndroidLog.logI(TAG, "Migu native full-song lyric hooks installed");
            scheduleRefresh();
        } catch (ClassNotFoundException ignored) {
            // 等待加固应用安装类加载器后重试。
        } catch (Throwable error) {
            mIncompatible = true;
            AndroidLog.logE(TAG, "Cannot install Migu native full-song lyric hooks", error);
        } finally {
            mInstalling = false;
        }
    }

    private void scheduleRefresh() {
        mHandler.post(() -> {
            mPendingAttempts = 20;
            mHandler.removeCallbacks(mRefreshTask);
            refresh();
        });
    }

    private void captureOffset(Object view) {
        try {
            if (!Boolean.TRUE.equals(mIsPlayerView.invoke(view)) && !Boolean.TRUE.equals(mIsTreasureView.invoke(view))) return;
            Object song = mGetSong.invoke(null);
            Object manager = mGetManager.invoke(null);
            if (song == null || manager == null || !MobileMusicLyricData.sameSong(song, mParsedSong.get(manager))) return;
            OffsetSnapshot snapshot = new OffsetSnapshot(song, mLyricParser.get(manager),
                ((Number) mGetViewOffset.invoke(view)).longValue());
            mHandler.post(() -> {
                mLiveOffset = snapshot;
                mHandler.removeCallbacks(mRefreshTask);
                refresh();
            });
        } catch (Throwable error) {
            AndroidLog.logE(TAG, "Cannot read Migu live lyric offset", error);
        }
    }

    /**
     * 检查宿主快照，仅在歌词、翻译或偏移发生变化时重新解析并发布整首歌词。
     */
    private void refresh() {
        if (!mInstalled) return;
        try {
            Object song = mGetSong.invoke(null);
            boolean nowPlaying = Boolean.TRUE.equals(mIsPlaying.invoke(null));
            long position = Math.max(0L, ((Number) mGetPosition.invoke(null)).longValue());
            long duration = Math.max(0L, ((Number) mGetDuration.invoke(null)).longValue());
            if (song == null || Boolean.TRUE.equals(mIsVideo.invoke(song))) {
                clearLyrics();
                mActiveSong = null;
                mActiveTrack = null;
                mLiveOffset = null;
                mPlaying = false;
                return;
            }
            if (mActiveTrack == null || !MobileMusicLyricData.sameSong(song, mActiveSong)) {
                clearLyrics();
                mLiveOffset = null;
                mActiveSong = song;
                mActiveTrack = new TrackContext(mTrackGeneration.incrementAndGet(), trackId(song),
                    (String) mGetTitle.invoke(song), (String) mGetArtist.invoke(song), (String) mGetAlbum.invoke(song), duration);
                mOrchestrator.onTrackChanged(mActiveTrack);
                mPlaying = false;
            }
            if (nowPlaying) {
                if (!mPlaying) mTracker.onPlay(position);
                else mTracker.onPositionSynced(position);
            } else {
                if (mPlaying) mTracker.onPause();
                mTracker.onPositionSynced(position);
                if (mPlaying) publishStop();
            }
            mPlaying = nowPlaying;
            Object manager = mGetManager.invoke(null);
            SuperLyricData data = null;
            LyricSnapshot snapshot = null;
            if (manager != null) {
                synchronized (manager) {
                    if (MobileMusicLyricData.sameSong(song, mParsedSong.get(manager))
                        && !Boolean.TRUE.equals(mIsStatic.invoke(manager))) {
                        Object parser = mLyricParser.get(manager);
                        List<?> lines = (List<?>) mLyricLines.get(manager);
                        List<?> translations = (List<?>) mTranslationLines.get(manager);
                        long offset = 0L;
                        if (Boolean.TRUE.equals(mIsMrc.get(manager))) {
                            if (mLiveOffset != null && mLiveOffset.matches(song, parser)) offset = mLiveOffset.offset;
                            else if (parser != null) offset = ((Number) mGetParserOffset.invoke(parser)).longValue();
                        }
                        if (lines != null && !lines.isEmpty()) {
                            snapshot = new LyricSnapshot(parser, lines, lines.size(), translations,
                                translations == null ? 0 : translations.size(), offset, duration, mLyricRevision);
                            if (!snapshot.sameAs(mLastSnapshot)) {
                                data = MobileMusicLyricData.read(manager, song, duration, offset);
                            }
                        }
                    }
                }
            }
            if (!MobileMusicLyricData.sameSong(song, mGetSong.invoke(null))) {
                clearLyrics();
            } else if (snapshot == null) {
                clearLyrics();
            } else if (!snapshot.sameAs(mLastSnapshot)) {
                if (data == null || !data.hasAllLyrics()) {
                    clearLyrics();
                } else {
                    data.setLyricId(mActiveTrack.getTrackId());
                    MobileMusicLyricData.setPosition(data, position);
                    mOrchestrator.onHookFullLyricCaptured(mActiveTrack, data);
                    mLastSnapshot = snapshot;
                    if (!mPlaying) publishStop();
                }
            }
            mReadFailureLogged = false;
        } catch (Throwable error) {
            clearLyrics();
            if (!mReadFailureLogged) {
                AndroidLog.logE(TAG, "Cannot read Migu native full-song lyrics", error);
                mReadFailureLogged = true;
            }
        }
        // 未打开歌词界面时，也检查异步解析结果和延迟到达的翻译。
        if (mPlaying || (mLastSnapshot == null && mPendingAttempts-- > 0)) {
            mHandler.postDelayed(mRefreshTask, 500L);
        }
    }

    private void clearLyrics() {
        mTracker.setLyrics(null);
        if (mLastSnapshot != null) publishStop();
        mLastSnapshot = null;
    }

    private String trackId(Object song) throws ReflectiveOperationException {
        for (Method getter : new Method[]{mGetSongId, mGetContentId, mGetLocalMd5}) {
            Object value = getter.invoke(song);
            if (value instanceof String id && !id.isBlank()) return getter.getName() + ":" + id;
        }
        return "migu:" + mTrackGeneration.get();
    }

    private static final class OffsetSnapshot {
        final Object song;
        final Object parser;
        final long offset;

        OffsetSnapshot(Object song, Object parser, long offset) {
            this.song = song;
            this.parser = parser;
            this.offset = offset;
        }

        boolean matches(Object currentSong, Object currentParser) throws ReflectiveOperationException {
            return parser == currentParser && MobileMusicLyricData.sameSong(song, currentSong);
        }
    }

    private static final class LyricSnapshot {
        final Object parser;
        final Object lines;
        final int lineCount;
        final Object translations;
        final int translationCount;
        final long offset;
        final long duration;
        final long revision;

        LyricSnapshot(Object parser, Object lines, int lineCount, Object translations,
                      int translationCount, long offset, long duration, long revision) {
            this.parser = parser;
            this.lines = lines;
            this.lineCount = lineCount;
            this.translations = translations;
            this.translationCount = translationCount;
            this.offset = offset;
            this.duration = duration;
            this.revision = revision;
        }

        boolean sameAs(LyricSnapshot other) {
            return other != null && parser == other.parser && lines == other.lines && lineCount == other.lineCount
                && translations == other.translations && translationCount == other.translationCount
                && offset == other.offset && duration == other.duration && revision == other.revision;
        }
    }
}
