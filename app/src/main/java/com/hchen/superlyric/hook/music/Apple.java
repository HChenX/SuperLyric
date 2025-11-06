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

import android.app.Application;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.hchen.collect.Collect;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;

/**
 * Apple Music
 */
@Collect(targetPackage = "com.apple.android.music")
public class Apple extends LyricRelease {
    private Object currentSongInfo;
    private LyricsLinePtrHelper lyricsLinePtrHelper;
    private PlaybackState playbackState;
    private Object playbackItem;
    private Object lyricViewModel;
    private final LinkedList<LyricsLine> lyricList = new LinkedList<>();
    private String currentTitle = "";
    private String currentTrackId;
    private boolean isRunning = false;
    private Handler mainHandler;
    private Handler lyricHandler;
    private LyricsLine lastShownLyric;

    static class LyricsLine {
        int start;
        int end;
        String lyric;

        LyricsLine(int start, int end, String lyric) {
            this.start = start;
            this.end = end;
            this.lyric = lyric;
        }
    }

    @Override
    protected void init() {
        // 初始化 Handler
        mainHandler = new Handler(Looper.getMainLooper());
        HandlerThread lyricThread = new HandlerThread("AppleMusicLyricThread");
        lyricThread.start();
        lyricHandler = new Handler(lyricThread.getLooper());

        // Hook 初始化 LyricViewModel
        hookMethod("com.apple.android.music.AppleMusicApplication",
            "onCreate",
            new IHook() {
                @Override
                public void after() {
                    try {
                        Application application = (Application) thisObject();
                        Class<?> playerLyricsViewModelClass = findClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
                        if (playerLyricsViewModelClass != null) {
                            lyricViewModel = newInstance(playerLyricsViewModelClass, application);
                        }
                    } catch (Exception e) {
                        logE(TAG, "Failed to initialize LyricViewModel", e);
                    }
                }
            }
        );

        // Hook PlaybackState 构造
        hookAllConstructor("android.media.session.PlaybackState",
            new IHook() {
                @Override
                public void after() {
                    playbackState = (PlaybackState) thisObject();
                }
            }
        );

        // Hook 播放状态变化
        Class<?> mediaControllerCompatHandlerClass = DexkitCache.findMember("apple$1", new IDexkit<ClassData>() {
            @NonNull
            @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .searchPackages("android.support.v4.media.session")
                    .matcher(ClassMatcher.create()
                        .className("android.support.v4.media.session.MediaControllerCompat$", StringMatchType.Contains)
                        .superClass("android.os.Handler")
                    )
                ).single();
            }
        });
        Field playbackStateField = findFieldPro("android.support.v4.media.session.PlaybackStateCompat")
            .withFieldClass(PlaybackState.class)
            .single();

        // android.support.v4.media.session.MediaControllerCompat$a$b
        hookMethod(mediaControllerCompatHandlerClass,
            "handleMessage",
            Message.class,
            new IHook() {
                @Override
                public void before() {
                    Message m = (Message) getArg(0);
                    if (m.what == 2) {
                        // 获取 PlaybackStateCompat 对象
                        Object playbackStateCompat = m.obj;
                        if (playbackStateCompat == null) return;

                        playbackState = (PlaybackState) getField(playbackStateCompat, playbackStateField);
                        updateLyricPosition();
                    }
                }
            }
        );

        // Hook MediaMetadata 变化
        Class<?> mediaControllerCompatClass = DexkitCache.findMember("apple$2", new IDexkit<ClassData>() {
            @NonNull
            @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .searchPackages("android.support.v4.media.session")
                    .matcher(ClassMatcher.create()
                        .className("android.support.v4.media.session.MediaControllerCompat$", StringMatchType.Contains)
                        .superClass("android.media.session.MediaController$Callback")
                    )
                ).single();
            }
        });
        Method mediaMetadataCompatStaticMethod = findMethodPro("android.support.v4.media.MediaMetadataCompat")
            .withStatic()
            .single()
            .obtain();

        Field mediaMetadataField = findFieldPro("android.support.v4.media.MediaMetadataCompat")
            .withFieldClass(MediaMetadata.class)
            .single();

        // android.support.v4.media.session.MediaControllerCompat$a$a
        hookMethod(mediaControllerCompatClass,
            "onMetadataChanged",
            MediaMetadata.class,
            new IHook() {
                @Override
                public void before() {
                    try {
                        // 获取MediaMetadata实例
                        Object metadataCompat = callStaticMethod(
                            mediaMetadataCompatStaticMethod,
                            getArg(0)
                        );

                        MediaMetadata metadata = (MediaMetadata) getField(metadataCompat, mediaMetadataField);
                        String newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);

                        // 检测歌曲变化
                        if (newTitle != null && !currentTitle.equals(newTitle)) {
                            // 停止现有歌词
                            sendLyric("");
                            sendStop();

                            // 重置现有状态
                            lyricList.clear();
                            isRunning = false;
                            lastShownLyric = null;

                            // 更新当前歌曲名
                            currentTitle = newTitle;
                            logD(TAG, "Current song title: " + currentTitle);

                            // 请求当前歌词
                            mainHandler.postDelayed(() -> requestLyrics(), 400);
                        }
                    } catch (Exception e) {
                        logE(TAG, "Error getting MediaMetadata", e);
                    }
                }
            }
        );

        // Hook 歌词构建方法
        hookMethod("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            "buildTimeRangeToLyricsMap",
            "com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoPtr",
            new IHook() {
                @Override
                public void after() {
                    Object songInfoPtr = getArg(0);
                    if (songInfoPtr == null) return;

                    currentSongInfo = callMethod(songInfoPtr, "get");
                    if (currentSongInfo == null) return;

                    Object lyricsSectionVector = callMethod(currentSongInfo, "getSections");
                    if (lyricsSectionVector == null) return;

                    lyricsLinePtrHelper = new LyricsLinePtrHelper(lyricsSectionVector);
                    updateLyricList();
                }
            }
        );

        hookPlaybackItemSetId();
        logI(
            TAG,
            "mediaMetadataCompatStaticMethod: " + mediaMetadataCompatStaticMethod +
                ", mediaMetadataField: " + mediaMetadataField +
                ", playbackStateField: " + playbackStateField
        );
    }

    private void hookPlaybackItemSetId() {
        try {
            Class<?> playbackItemClass = findClass("com.apple.android.music.model.PlaybackItem");

            if (existsClass("com.apple.android.music.model.BaseContentItem")) {
                hookMethod("com.apple.android.music.model.BaseContentItem",
                    "setId",
                    String.class,
                    new IHook() {
                        @Override
                        public void before() {
                            if (playbackItemClass.isInstance(thisObject())) {
                                String trackId = (String) getArg(0);
                                if (trackId == null) return;

                                int[] flag = new int[]{-1, -1};
                                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                                for (StackTraceElement element : stackTrace) {
                                    if (flag[0] == -1 && element.toString().contains("getItemAtIndex"))
                                        flag[0] = 1;
                                    if (flag[1] == -1 && element.toString().contains(".accept"))
                                        flag[1] = 1;
                                    if (flag[0] == 1 && flag[1] == 1) break;
                                }
                                if (flag[0] == 1 && flag[1] == 1) {
                                    currentTrackId = trackId;
                                    playbackItem = thisObject();
                                    logD(TAG, "Current music ID: " + currentTrackId);
                                }
                            }
                        }
                    }
                );
            }
        } catch (Exception e) {
            logE(TAG, "Failed to hook PlaybackItem.setId", e);
        }
    }

    private void requestLyrics() {
        try {
            if (lyricViewModel != null && playbackItem != null) {
                logD(TAG, "Requesting lyrics via ViewModel");
                callMethod(lyricViewModel, "loadLyrics", playbackItem);
            } else {
                logD(TAG, "Unable to request lyrics - missing ViewModel or PlaybackItem");
            }
        } catch (Exception e) {
            logE(TAG, "Error requesting lyrics", e);
        }
    }

    private void updateLyricList() {
        if (lyricsLinePtrHelper == null) return;
        LinkedList<LyricsLine> newLyricList = new LinkedList<>();

        try {
            int i = 0;
            while (true) {
                Object lyricsLinePtr;
                try {
                    lyricsLinePtr = lyricsLinePtrHelper.getLyricsLinePtr(i);
                    if (lyricsLinePtr == null) break;
                } catch (Exception e) {
                    break;
                }

                Object lyricsLine = callMethod(lyricsLinePtr, "get");
                if (lyricsLine == null) break;

                String lyric = (String) callMethod(lyricsLine, "getHtmlLineText");
                Integer start = (Integer) callMethod(lyricsLine, "getBegin");
                Integer end = (Integer) callMethod(lyricsLine, "getEnd");

                if (lyric != null && start != null && end != null) {
                    newLyricList.add(new LyricsLine(start, end, lyric));
                }
                i++;
            }
            if (!newLyricList.isEmpty()) {
                if (lyricList.isEmpty() || newLyricList.getFirst().start != lyricList.getFirst().start) {
                    lyricList.clear();
                    lyricList.addAll(newLyricList);
                }
            }

            logD(TAG, "Loaded " + lyricList.size() + " lyrics lines");
        } catch (Exception e) {
            logE(TAG, "Error processing lyrics", e);
        }
    }

    private void updateLyricPosition() {
        if (isRunning) return;

        isRunning = true;
        lyricHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isRunning || playbackState == null) {
                    isRunning = false;
                    return;
                }

                // 暂停状态处理
                if (playbackState.getState() == PlaybackState.STATE_PAUSED) {
                    isRunning = false;
                    return;
                }

                // 计算当前播放位置
                long currentPosition = (long) (((SystemClock.elapsedRealtime() -
                    playbackState.getLastPositionUpdateTime()) *
                    playbackState.getPlaybackSpeed()) +
                    playbackState.getPosition());

                // 查找并显示当前歌词
                LyricsLine currentLine = null;
                for (LyricsLine line : lyricList) {
                    if (currentPosition >= line.start && currentPosition < line.end) {
                        currentLine = line;
                        break;
                    }
                }

                if (currentLine != null && (lastShownLyric == null || lastShownLyric != currentLine)) {
                    sendLyric(currentLine.lyric);
                    lastShownLyric = currentLine;
                }

                // 循环获取
                lyricHandler.postDelayed(this, 400);
            }
        });
    }

    private record LyricsLinePtrHelper(Object lyricsSectionVector) {
        public Object getLyricsLinePtr(int i10) {
            int i11 = 0;
            int i12 = 0;

            while (true) {
                long j10 = i11;
                if (j10 < (long) callMethod(lyricsSectionVector, "size")) {
                    Object lyricsLinePtr = callMethod(lyricsSectionVector, "get", j10);
                    long size = i12 + (long) callMethod(
                        callMethod(
                            callMethod(lyricsLinePtr,
                                "get"
                            ),
                            "getLines"
                        ),
                        "size"
                    );
                    if ((long) i10 >= size) {
                        i12 = Math.toIntExact(size);
                        i11++;
                    } else
                        return callMethod(
                            callMethod(
                                callMethod(
                                    lyricsLinePtr,
                                    "get"
                                ),
                                "getLines"
                            ),
                            "get",
                            i10 - i12
                        );
                } else
                    return null;
            }
        }
    }
}