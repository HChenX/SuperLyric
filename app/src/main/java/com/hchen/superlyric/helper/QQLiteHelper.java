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

import static com.hchen.hooktool.core.CoreTool.getField;
import static com.hchen.hooktool.core.CoreTool.hasClass;
import static com.hchen.hooktool.core.CoreTool.hookMethod;
import static com.hchen.hooktool.core.CoreTool.returnResult;
import static com.hchen.hooktool.core.CoreTool.setField;
import static com.hchen.superlyric.hook.AbsPublisher.sendLyric;
import static com.hchen.superlyric.hook.AbsPublisher.sendSuperLyricData;

import android.os.Message;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.util.List;
import java.util.Objects;

/**
 * 通过 QQLite 获取歌词
 *
 * @author 焕晨HChen
 */
public final class QQLiteHelper {
    private static final String TAG = "QQLiteHelper";

    /**
     * 是否支持 QQLite
     */
    public static boolean isSupportQQLite() {
        return hasClass("com.tencent.qqmusic.core.song.SongInfo");
    }

    private static int mLastIndex = -1;

    public static void hookLyric() {
        if (!isSupportQQLite()) return;

        hookMethod("com.tencent.qqmusiccommon.util.music.RemoteLyricController",
            "BluetoothA2DPConnected",
            returnResult(true)
        );

        try {
            // 逐字支持
            hookMethod("com.tencent.qqmusiccommon.util.music.RemoteLyricController$LyricCallback",
                "handleMessage",
                Message.class,
                new AbsHook() {
                    @Override
                    public void before() {
                        setField(
                            getField(getThisObject(), "this$0"),
                            "mSentenceIndex",
                            -1
                        );
                    }
                }
            );

            hookMethod("com.tencent.qqmusiccommon.util.music.RemoteLyricController",
                "getLyricSentenceIndex",
                long.class, "com.lyricengine.base.Lyric",
                new AbsHook() {
                    @Override
                    public void after() {
                        int index = (int) getResult();

                        if (index != -1) {
                            if (mLastIndex == index) {
                                return;
                            }
                            mLastIndex = index;

                            Object lyric = getArg(1);

                            String mTitle = (String) getField(lyric, "mTitle");
                            String mArtist = (String) getField(lyric, "mArtist");
                            String mAlbum = (String) getField(lyric, "mAlbum");

                            List<?> mSentences = (List<?>) getField(lyric, "mSentences");
                            if (mSentences != null) {
                                Object sentence = mSentences.get(index);
                                String mText = (String) getField(sentence, "mText");
                                long mStartTime = (long) getField(sentence, "mStartTime");
                                long mDuration = (long) getField(sentence, "mDuration");

                                SuperLyricWord[] words = null;
                                List<?> mCharacters = (List<?>) getField(sentence, "mCharacters");
                                if (mCharacters != null) {
                                    words = new SuperLyricWord[mCharacters.size()];
                                    for (int i = 0; i < mCharacters.size(); i++) {
                                        Object character = mCharacters.get(i);

                                        int mStart = (int) getField(character, "mStart");
                                        int mEnd = (int) getField(character, "mEnd");
                                        long wordStartTime = (long) getField(character, "mStartTime");
                                        long wordDuration = (long) getField(character, "mDuration");

                                        String word = mText.subSequence(mStart, mEnd).toString();

                                        words[i] = new SuperLyricWord(word, wordStartTime, wordStartTime + wordDuration);
                                    }
                                }

                                sendSuperLyricData(
                                    new SuperLyricData()
                                        .setTitle(mTitle)
                                        .setArtist(mArtist)
                                        .setAlbum(mAlbum)
                                        .setLyric(
                                            new SuperLyricLine(
                                                mText,
                                                words,
                                                mStartTime,
                                                mStartTime + mDuration
                                            )
                                        )
                                );
                            }
                        } else {
                            mLastIndex = -1;
                        }
                    }
                }
            );
        } catch (Throwable throwable) {
            XposedLog.logW(TAG, throwable);

            hookMethod("com.tencent.qqmusiccommon.util.music.RemoteControlManager",
                "updataMetaData",
                "com.tencent.qqmusic.core.song.SongInfo", String.class,
                new AbsHook() {
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
}
