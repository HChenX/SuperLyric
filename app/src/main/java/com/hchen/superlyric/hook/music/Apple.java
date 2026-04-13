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

import android.app.Application;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Apple Music
 */
@AutoHook(targetPackage = "com.apple.android.music")
public final class Apple extends AbsPublisher {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Handler lyricHandler;

    private Object lyricViewModel;
    private PlaybackState playbackState;
    private Object currentSongInfo;
    private LyricsLinePtrHelper lyricsLinePtrHelper;
    private final LinkedList<LyricsLine> lyricList = new LinkedList<>();
    private Object playbackItem;
    private String currentTitle = "";
    private String currentTrackId;
    private LyricsLine lastShownLyric;
    private boolean isRunning = false;

    private static class LyricsLine {
        int start;
        int end;
        String lyric;
        String translation;
        SuperLyricWord[] words;

        LyricsLine(int start, int end, String lyric, SuperLyricWord[] words, String translation) {
            this.start = start;
            this.end = end;
            this.lyric = lyric;
            this.words = words;
            this.translation = translation;
        }

        @Override
        @NonNull
        public String toString() {
            return "LyricsLine{" +
                "start=" + start +
                ", end=" + end +
                ", lyric='" + lyric + '\'' +
                ", translation='" + translation + '\'' +
                ", words=" + Arrays.toString(words) +
                '}';
        }

        @Override
        public final boolean equals(Object o) {
            if (!(o instanceof LyricsLine line)) return false;
            return start == line.start &&
                end == line.end &&
                Objects.equals(lyric, line.lyric) &&
                Objects.equals(translation, line.translation) &&
                Arrays.equals(words, line.words);
        }

        @Override
        public int hashCode() {
            int result = start;
            result = 31 * result + end;
            result = 31 * result + Objects.hashCode(lyric);
            result = 31 * result + Objects.hashCode(translation);
            result = 31 * result + Arrays.hashCode(words);
            return result;
        }
    }

    @Override
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        // 初始化 Handler
        HandlerThread lyricThread = new HandlerThread("AppleMusicLyricThread");
        lyricThread.start();
        lyricHandler = new Handler(lyricThread.getLooper());

        // Hook 初始化 LyricViewModel
        hookMethod("com.apple.android.music.AppleMusicApplication",
            "onCreate",
            new AbsHook() {
                @Override
                public void after() {
                    try {
                        Application application = (Application) getThisObject();
                        Class<?> playerLyricsViewModelClass = findClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
                        lyricViewModel = newInstance(playerLyricsViewModelClass, application);
                    } catch (Exception e) {
                        logE(TAG, "Failed to initialize LyricViewModel!!", e);
                    }
                }
            }
        );

        // Hook PlaybackState 构造
        hookAllConstructor("android.media.session.PlaybackState",
            new AbsHook() {
                @Override
                public void after() {
                    playbackState = (PlaybackState) getThisObject();
                }
            }
        );

        // Hook 播放状态变化
        // android.support.v4.media.session.MediaControllerCompat$a$b
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

        Field playbackStateField =
            Arrays.stream(findClass("android.support.v4.media.session.PlaybackStateCompat").getDeclaredFields())
                .filter(new Predicate<Field>() {
                    @Override
                    public boolean test(Field field) {
                        return Objects.equals(field.getType(), PlaybackState.class);
                    }
                })
                .findFirst()
                .orElseThrow();

        // android.support.v4.media.session.MediaControllerCompat$a$b
        hookMethod(mediaControllerCompatHandlerClass,
            "handleMessage",
            Message.class,
            new AbsHook() {
                @Override
                public void before() {
                    Message m = (Message) getArg(0);
                    if (m.what == 2) {
                        // 获取 PlaybackStateCompat 对象
                        Object playbackStateCompat = m.obj;
                        if (playbackStateCompat != null) {
                            playbackState = (PlaybackState) getField(playbackStateField, playbackStateCompat);
                            updateLyricPosition();
                        }
                    }
                }
            }
        );

        // Hook MediaMetadata 变化
        // android.support.v4.media.session.MediaControllerCompat$a$a
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
        Method mediaMetadataCompatStaticMethod =
            Arrays.stream(findClass("android.support.v4.media.MediaMetadataCompat").getDeclaredMethods())
                .filter(new Predicate<Method>() {
                    @Override
                    public boolean test(Method method) {
                        return Modifier.isStatic(method.getModifiers());
                    }
                }).findFirst().orElseThrow();

        Field mediaMetadataField =
            Arrays.stream(findClass("android.support.v4.media.MediaMetadataCompat").getDeclaredFields())
                .filter(new Predicate<Field>() {
                    @Override
                    public boolean test(Field field) {
                        return Objects.equals(field.getType(), MediaMetadata.class);
                    }
                }).findFirst().orElseThrow();

        // android.support.v4.media.session.MediaControllerCompat$a$a
        hookMethod(mediaControllerCompatClass,
            "onMetadataChanged",
            MediaMetadata.class,
            new AbsHook() {
                @Override
                public void before() {
                    try {
                        // 获取MediaMetadata实例
                        Object metadataCompat = callStaticMethod(
                            mediaMetadataCompatStaticMethod,
                            getArg(0)
                        );

                        MediaMetadata metadata = (MediaMetadata) getField(mediaMetadataField, metadataCompat);
                        String newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);

                        // 检测歌曲变化
                        if (newTitle != null && !currentTitle.equals(newTitle)) {
                            // 停止现有歌词
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
                        logE(TAG, "Error getting MediaMetadata!!", e);
                    }
                }
            }
        );

        // Hook 歌词构建方法
        hookMethod("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            "buildTimeRangeToLyricsMap",
            "com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoPtr",
            new AbsHook() {
                @Override
                public void after() {
                    Object songInfoPtr = getArg(0);
                    if (songInfoPtr == null) return;

                    currentSongInfo = callMethod(songInfoPtr, "get");
                    if (currentSongInfo == null) return;

                    String currentSystemLyricsLanguage = (String) callStaticMethod("com.apple.android.music.playback.util.LocaleUtil", "getSystemLyricsLanguage");
                    callMethod(currentSongInfo, "setTranslation", currentSystemLyricsLanguage);
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

            if (hasClass("com.apple.android.music.model.BaseContentItem")) {
                hookMethod("com.apple.android.music.model.BaseContentItem",
                    "setId",
                    String.class,
                    new AbsHook() {
                        @Override
                        public void before() {
                            if (playbackItemClass.isInstance(getThisObject())) {
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
                                    playbackItem = getThisObject();
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

                SuperLyricWord[] superLyricWords = null;
                Object words = callMethod(lyricsLine, "getWords");
                if (words != null) {
                    long wordSize = (long) callMethod(words, "size");
                    Object[] wordPtrs = new Object[Math.toIntExact(wordSize)];
                    for (long l = 0; l < wordSize; l++) {
                        Object wordPtr = callMethod(words, "get", l);
                        wordPtrs[Math.toIntExact(l)] = wordPtr;
                    }
                    superLyricWords = new SuperLyricWord[wordPtrs.length];
                    for (int i1 = 0; i1 < wordPtrs.length; i1++) {
                        Object word = callMethod(wordPtrs[i1], "get");
                        String text = (String) callMethod(word, "getHtmlLineText");
                        int subStart = (int) callMethod(word, "getBegin");
                        int subEnd = (int) callMethod(word, "getEnd");
                        superLyricWords[i1] = new SuperLyricWord(text, subStart, subEnd);
                    }
                }

                String translation = (String) callMethod(lyricsLine, "getHtmlTranslationLineText");

                if (lyric != null && start != null && end != null) {
                    LyricsLine line = new LyricsLine(start, end, lyric, superLyricWords, translation);
                    logD(TAG, "Lyric Line: " + line);
                    newLyricList.add(line);
                }
                i++;
            }

            if (!newLyricList.isEmpty()) {
                if (lyricList.isEmpty() || !Objects.equals(newLyricList.getFirst(), lyricList.getFirst())) {
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
                    sendLyric(
                        new SuperLyricData()
                            .setLyric(
                                new SuperLyricLine(
                                    currentLine.lyric,
                                    currentLine.words,
                                    currentLine.start,
                                    currentLine.end
                                )
                            )
                            .setTranslation(
                                new SuperLyricLine(
                                    currentLine.translation
                                )
                            )
                    );
                    lastShownLyric = currentLine;
                }

                // 循环获取
                lyricHandler.postDelayed(this, 400);
            }
        });
    }

    private record LyricsLinePtrHelper(Object lyricsSectionVector) {
        public Object getLyricsLinePtr(int lineIndex) {
            int accumulatedLines = 0;

            long sectionCount = (long) callMethod(lyricsSectionVector, "size");
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                Object sectionPtr = callMethod(lyricsSectionVector, "get", sectionIndex);
                Object section = callMethod(sectionPtr, "get");

                Object lines = callMethod(section, "getLines");
                long lineCount = (long) callMethod(lines, "size");
                if (lineIndex < accumulatedLines + lineCount) {
                    int indexInSection = lineIndex - accumulatedLines;
                    return callMethod(lines, "get", indexInSection);
                }

                accumulatedLines += (int) lineCount;
            }
            return null;
        }
    }
}