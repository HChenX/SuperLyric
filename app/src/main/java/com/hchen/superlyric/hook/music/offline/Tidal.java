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

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Tidal Music
 *
 * @author Coolkie(PL7963)
 */
@HookThis(targetPackage = "com.aspiro.tidal")
public final class Tidal extends AbsPublisher {
    private static class LineData {
        long startTime;
        long endTime;
        String text;

        LineData(long startTime, long endTime, String text) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.text = text;
        }
    }

    private final List<LineData> mLines = new ArrayList<>();
    private String mCurrentLyricsId;
    private int mLastIndex = -1;

    private Field mLineTextField;
    private Field mLineStartTimeField;

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        logD(tag, "Tidal hook starting...");

        // 1. 動態尋找歌詞行類別
        // 特徵：toString 包含 "LyricLine(text=" 和 "startTimeMs="
        Class<?> lineClass = DexkitCache.findMember("tidal$lineClass", bridge -> bridge.findClass(FindClass.create()
                .matcher(ClassMatcher.create()
                        .usingStrings("LyricLine(text=", "startTimeMs=")
                )
        ).single());

        // 2. 動態尋找同步歌詞狀態類別
        // 特徵：toString 包含 "Synced(lyricsId=" 和 "highlightedIndex="
        Class<?> syncedLyricsClass = DexkitCache.findMember("tidal$syncedLyricsClass", bridge -> bridge.findClass(FindClass.create()
                .matcher(ClassMatcher.create()
                        .usingStrings("Synced(lyricsId=", "highlightedIndex=")
                )
        ).single());

        if (lineClass == null || syncedLyricsClass == null) {
            logE(tag, "Failed to find critical classes via Dexkit. Hook aborted.");
            return;
        }

        logD(tag, "Discovered classes: SyncedLyrics=" + syncedLyricsClass.getName() + ", Line=" + lineClass.getName());

        // 3. Hook 構造函數
        hookAllConstructor(syncedLyricsClass, new AbsHook() {
            @Override
            public void after() {
                try {
                    String lyricsId = (String) getArg(0);
                    Collection<?> lines = (Collection<?>) getArg(1);
                    int index = (int) getArg(2);

                    if (lyricsId == null || lines == null) return;

                    // 1. 如果是新歌詞 ID，重新解析全量歌詞
                    if (!Objects.equals(lyricsId, mCurrentLyricsId)) {
                        logD(tag, "New lyrics detected, ID: " + lyricsId + ", size: " + lines.size());
                        mCurrentLyricsId = lyricsId;
                        parseTidalLines(lines, lineClass);
                    }

                    // 2. 根據 index 發送當前行
                    if (index != mLastIndex && index >= 0 && index < mLines.size()) {
                        mLastIndex = index;
                        LineData data = mLines.get(index);
                        logD(tag, "Sending lyric [" + index + "]: " + data.text);
                        
                        sendLyric(new SuperLyricData()
                            .setLyric(new SuperLyricLine(data.text, data.startTime, data.endTime)));
                    }
                } catch (Throwable e) {
                    logE(tag, "Error in SyncedLyrics constructor hook", e);
                }
            }
        });
        
        logD(tag, "Tidal hook installed successfully.");
    }

    private void parseTidalLines(Collection<?> rawLines, Class<?> lineClass) {
        mLines.clear();
        mLastIndex = -1;

        if (mLineTextField == null || mLineStartTimeField == null) {
            discoverLineFields(lineClass);
        }

        List<LineData> tempLines = new ArrayList<>();
        for (Object obj : rawLines) {
            try {
                String text = (String) mLineTextField.get(obj);
                long start = 0;
                Object startVal = mLineStartTimeField.get(obj);
                if (startVal instanceof Long) start = (long) startVal;
                else if (startVal instanceof Integer) start = (int) startVal;

                tempLines.add(new LineData(start, start, text != null ? text : ""));
            } catch (Throwable e) {
                logE(tag, "Failed to parse individual line", e);
            }
        }

        // 計算結束時間 (下一行的開始時間)
        for (int i = 0; i < tempLines.size(); i++) {
            if (i + 1 < tempLines.size()) {
                tempLines.get(i).endTime = tempLines.get(i + 1).startTime;
            } else {
                tempLines.get(i).endTime = tempLines.get(i).startTime + 5000;
            }
        }
        
        mLines.addAll(tempLines);
        logD(tag, "Successfully parsed " + mLines.size() + " lines.");
    }

    private void discoverLineFields(Class<?> lineClass) {
        for (Field f : lineClass.getDeclaredFields()) {
            f.setAccessible(true);
            Class<?> type = f.getType();
            if (type == String.class && mLineTextField == null) {
                mLineTextField = f;
            } else if ((type == long.class || type == int.class || type == Long.class || type == Integer.class) 
                    && mLineStartTimeField == null) {
                mLineStartTimeField = f;
            }
        }
    }
}
