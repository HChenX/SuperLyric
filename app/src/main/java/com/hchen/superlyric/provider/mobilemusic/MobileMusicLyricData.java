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
package com.hchen.superlyric.provider.mobilemusic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将咪咕原生歌词模型转换为整首歌词数据，不修改宿主对象。
 */
public final class MobileMusicLyricData {
    private MobileMusicLyricData() {
    }

    @Nullable
    public static SuperLyricData read(@NonNull Object manager, @Nullable Object song, long duration, @Nullable Long liveOffset)
        throws ReflectiveOperationException {
        if (song == null || Boolean.TRUE.equals(invoke(song, "isVideoResource"))) return null;
        Object parsedSong = field(manager, "mCurrentParseSong");
        if (!sameSong(song, parsedSong)) return null;
        List<?> lines = (List<?>) field(manager, "mLrcLineList");
        if (lines == null || lines.isEmpty()) return null;
        if (Boolean.TRUE.equals(invoke(manager, "isStaticLrc"))) return null;

        long offset = 0;
        if (Boolean.TRUE.equals(field(manager, "isMrc"))) {
            Object parser = field(manager, "lyricsParser");
            if (liveOffset != null) offset = liveOffset;
            else if (parser != null) offset = number(invoke(parser, "getPlayOffset"));
        }
        List<?> translations = (List<?>) field(manager, "mLrcTrcsLineList");
        ArrayList<SuperLyricLine> result = new ArrayList<>(lines.size());
        long previousStart = Long.MIN_VALUE;
        for (int index = 0; index < lines.size(); index++) {
            Object line = lines.get(index);
            long start = number(invoke(line, "getStartTime"));
            // 宿主列表按时间排序，拒绝乱序数据以保证后续二分查找正确。
            if (start < 0 || start < previousStart) return null;
            long end = number(invoke(line, "getEndTime"));
            long nextStart = index + 1 < lines.size()
                ? number(invoke(lines.get(index + 1), "getStartTime")) : duration + offset;
            if (end <= start) end = Math.max(start, nextStart);
            String text = text(invoke(line, "getLineLyrics"));
            String translation = findTranslation(translations, start, previousStart, nextStart);
            if (translation == null || translation.isBlank()) translation = text(invoke(line, "getTrcLrc"));
            // 保留带时间的空行，在间奏期间清除上一句歌词。
            result.add(new SuperLyricLine(text, readWords(line, text, start, end, offset),
                translation.isBlank() ? null : translation,
                Math.max(0L, start - offset), Math.max(0L, end - offset)));
            previousStart = start;
        }
        return new SuperLyricData()
            .setTitle((String) invoke(song, "getSongName"))
            .setArtist((String) invoke(song, "getSingerName"))
            .setAlbum((String) invoke(song, "getAlbum"))
            .setDuration(Math.max(0L, duration))
            .setAllLyrics(result.toArray(new SuperLyricLine[0]));
    }

    public static boolean sameSong(@Nullable Object song, @Nullable Object parsedSong) throws ReflectiveOperationException {
        if (song == parsedSong) return true;
        if (song == null || parsedSong == null) return false;
        for (String getter : List.of("getSongId", "getContentId", "getLocalPathMd5")) {
            String identity = (String) invoke(song, getter);
            if (identity != null && !identity.isBlank()
                && Objects.equals(identity, invoke(parsedSong, getter))) return true;
        }
        return false;
    }

    /**
     * 按当前播放进度填充单行通道，避免更新偏移时短暂跳回第一行。
     */
    public static void setPosition(@NonNull SuperLyricData data, long position) {
        SuperLyricLine[] lines = data.getAllLyrics();
        int low = 0;
        int high = lines.length - 1;
        int selected = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines[middle].getStartTime() <= position) {
                selected = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        SuperLyricLine current = selected >= 0 ? lines[selected] : new SuperLyricLine("", 0, lines[0].getStartTime());
        data.setPosition(position).setCurrentLyricIndex(selected).setLyric(current);
        data.setTranslation(current.hasTranslation() ? current.getTranslationLine() : null);
    }

    private static int findLine(List<?> lines, long position) throws ReflectiveOperationException {
        int selected = -1;
        int low = 0;
        int high = lines.size() - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            if (number(invoke(lines.get(middle), "getStartTime")) <= position) {
                selected = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return selected;
    }

    @Nullable
    private static SuperLyricWord[] readWords(Object line, String lineText, long start, long end, long offset)
        throws ReflectiveOperationException {
        String[] texts = (String[]) invoke(line, "getLyricsWords");
        int[] durations = (int[]) invoke(line, "getWordsDisInterval");
        if (texts == null || durations == null || texts.length == 0 || texts.length != durations.length) {
            return null;
        }
        ArrayList<SuperLyricWord> words = new ArrayList<>(texts.length);
        StringBuilder combined = new StringBuilder();
        long wordStart = start;
        for (int index = 0; index < texts.length; index++) {
            if (texts[index] == null || durations[index] < 0) return null;
            String word = text(texts[index]);
            combined.append(word);
            long wordEnd = wordStart + durations[index];
            if (!word.isEmpty()) {
                words.add(new SuperLyricWord(word, Math.max(0L, wordStart - offset), Math.max(0L, wordEnd - offset)));
            }
            wordStart = wordEnd;
        }
        if (!lineText.contentEquals(combined) || wordStart <= start || wordStart > end || words.isEmpty()) {
            return null;
        }
        return words.toArray(new SuperLyricWord[0]);
    }

    @Nullable
    private static String findTranslation(List<?> translations, long start, long previousStart, long nextStart)
        throws ReflectiveOperationException {
        if (translations == null || translations.isEmpty()) return null;
        Object closest = null;
        long distance = Long.MAX_VALUE;
        int preceding = findLine(translations, start);
        for (int index = Math.max(0, preceding); index <= preceding + 1 && index < translations.size(); index++) {
            Object translation = translations.get(index);
            long translationStart = number(invoke(translation, "getStartTime"));
            long difference = Math.abs(translationStart - start);
            boolean nearerPrevious = previousStart != Long.MIN_VALUE
                && Math.abs(translationStart - previousStart) <= difference;
            boolean nearerNext = nextStart > start && Math.abs(translationStart - nextStart) <= difference;
            if (difference <= 1000L && difference < distance
                && !nearerPrevious && !nearerNext) {
                closest = translation;
                distance = difference;
            }
        }
        return closest == null ? null : text(invoke(closest, "getLineLyrics"));
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getField(name).get(target);
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static String text(Object value) {
        return value instanceof String string ? string.replace("\r", "").replace("\n", "") : "";
    }
}
