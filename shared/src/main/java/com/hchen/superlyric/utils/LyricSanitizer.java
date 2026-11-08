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
package com.hchen.superlyric.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.kernel.text.LyricText;
import com.hchen.superlyricapi.model.Line;
import com.hchen.superlyricapi.model.Lyric;
import com.hchen.superlyricapi.model.Word;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词数据的安全清洗与合法性校验
 *
 * <p>拦下损坏的逐字切片、倒错的字符索引、零宽不可见字符与累积字长越界，避免下游
 * 接收端调用 {@code Canvas.drawTextRun} 时抛出 {@link IndexOutOfBoundsException}。
 *
 * <p>这是 3.x/发布边界的防御层；kernel 的 LineNormalizer 负责规范化，两者不合并。
 */
public final class LyricSanitizer {
    private static final String TAG = "LyricSanitizer";

    private LyricSanitizer() {
    }

    /**
     * 剥离零宽字符、换行符与 NUL；字符表只在内核 LyricText 中维护一份。
     */
    @NonNull
    public static String cleanInvisibleChars(@Nullable String text) {
        return LyricText.INSTANCE.cleanInvisibleChars(text);
    }

    /**
     * 清洗单行；空文本也保留为空行，由上层决定是否丢弃。
     */
    @Nullable
    public static Line sanitizeLine(@Nullable Line line) {
        if (line == null) return null;

        String cleanText = cleanInvisibleChars(line.getText());
        long start = Math.max(0L, line.getStart());
        long end = Math.max(start, line.getEnd());

        String cleanTranslation = cleanInvisibleChars(line.getTranslation());
        if (cleanTranslation.trim().isEmpty()) cleanTranslation = null;

        String cleanPhonetic = cleanInvisibleChars(line.getPhonetic());
        if (cleanPhonetic.trim().isEmpty()) cleanPhonetic = null;

        List<Word> cleanWords = null;
        List<Word> rawWords = line.getWords();
        if (rawWords != null && !rawWords.isEmpty()) {
            cleanWords = validateAndCleanWords(rawWords, cleanText, start, end);
        }

        return new Line(cleanText, start, end, cleanWords, cleanTranslation, cleanPhonetic);
    }

    /**
     * 清洗完整文档；没有有效行时返回 null。
     */
    @Nullable
    public static Lyric sanitizeLyric(@Nullable Lyric lyric) {
        if (lyric == null) return null;

        try {
            List<Line> cleanLines = new ArrayList<>(lyric.getLines().size());
            for (Line line : lyric.getLines()) {
                Line clean = sanitizeLine(line);
                if (clean != null) cleanLines.add(clean);
            }
            if (cleanLines.isEmpty()) return null;

            return new Lyric(
                lyric.getId(),
                lyric.getTitle(),
                lyric.getArtist(),
                lyric.getAlbum(),
                Math.max(0L, lyric.getDuration()),
                cleanLines
            );
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Dropping lyric: sanitization failed", t);
            return null;
        }
    }

    @Nullable
    private static List<Word> validateAndCleanWords(@NonNull List<Word> rawWords,
                                                    @NonNull String cleanLineText,
                                                    long lineStart,
                                                    long lineEnd) {
        List<Word> validated = new ArrayList<>(rawWords.size());
        StringBuilder wordsText = new StringBuilder();

        for (int i = 0; i < rawWords.size(); i++) {
            Word word = rawWords.get(i);
            if (word == null) {
                AndroidLog.logW(TAG, "Sanitizer: dropping words due to null word element at index "
                    + i + " for line: \"" + cleanLineText + "\"");
                return null;
            }

            String text = cleanInvisibleChars(word.getText());
            long start = Math.max(0L, word.getStart());
            long end = Math.max(start, word.getEnd());
            wordsText.append(text);
            validated.add(new Word(text, start, end));
        }

        String combined = wordsText.toString();
        if (combined.length() > cleanLineText.length()
            && combined.trim().equals(cleanLineText.trim())) {
            trimOuterWhitespace(validated, cleanLineText);
            wordsText.setLength(0);
            for (Word word : validated) wordsText.append(word.getText());
            combined = wordsText.toString();
        }

        if (combined.length() > cleanLineText.length()) {
            AndroidLog.logW(TAG, "Sanitizer: DROPPING CORRUPTED WORDS! Total words length ("
                + combined.length() + ") exceeds line text length (" + cleanLineText.length()
                + ") | line=\"" + cleanLineText + "\" | combinedWords=\"" + combined + "\"");
            return null;
        }

        boolean exactMatch = combined.equals(cleanLineText);
        if (!exactMatch) {
            String combinedNoSpace = combined.replaceAll("[\\s\\u3000]+", "");
            String lineNoSpace = cleanLineText.replaceAll("[\\s\\u3000]+", "");
            if (!combinedNoSpace.equals(lineNoSpace)) {
                AndroidLog.logW(TAG, "Sanitizer: DROPPING MISMATCHED WORDS! Combined words mismatch line text"
                    + " | line=\"" + cleanLineText + "\" | combined=\"" + combined + "\"");
                return null;
            }
        }

        healWordTimings(validated, lineStart, lineEnd);
        return validated;
    }

    private static void trimOuterWhitespace(@NonNull List<Word> words, @NonNull String targetLine) {
        if (words.isEmpty()) return;

        while (!words.isEmpty()) {
            Word first = words.get(0);
            String text = first.getText();
            if (text.trim().isEmpty() && !targetLine.startsWith(text)) {
                words.remove(0);
            } else {
                int trimLeading = 0;
                while (trimLeading < text.length() && Character.isWhitespace(text.charAt(trimLeading))) {
                    trimLeading++;
                }
                if (trimLeading > 0 && !targetLine.startsWith(text)) {
                    words.set(0, new Word(text.substring(trimLeading), first.getStart(), first.getEnd()));
                }
                break;
            }
        }

        while (!words.isEmpty()) {
            int lastIndex = words.size() - 1;
            Word last = words.get(lastIndex);
            String text = last.getText();
            if (text.trim().isEmpty() && !targetLine.endsWith(text)) {
                words.remove(lastIndex);
            } else {
                int trimTrailing = 0;
                while (trimTrailing < text.length()
                    && Character.isWhitespace(text.charAt(text.length() - 1 - trimTrailing))) {
                    trimTrailing++;
                }
                if (trimTrailing > 0 && !targetLine.endsWith(text)) {
                    words.set(lastIndex, new Word(
                        text.substring(0, text.length() - trimTrailing),
                        last.getStart(),
                        last.getEnd()
                    ));
                }
                break;
            }
        }
    }

    /**
     * 全局逐字时序纠偏，消除 0ms 与越界时长。
     */
    public static void healWordTimings(@NonNull List<Word> words, long lineStart, long lineEnd) {
        int size = words.size();
        if (size == 0) return;

        long runningStart = lineStart;
        for (int i = 0; i < size; i++) {
            Word current = words.get(i);
            long start = current.getStart();
            long end = current.getEnd();
            String text = current.getText();
            long fallbackDuration = LyricText.INSTANCE.fallbackWordDuration(text != null ? text : "");

            if (start < runningStart) start = runningStart;

            long duration = end - start;
            if (duration <= 0 || duration > 15000L) {
                long nextValidStart = -1L;
                for (int j = i + 1; j < size; j++) {
                    long nextStart = words.get(j).getStart();
                    if (nextStart > start) {
                        nextValidStart = nextStart;
                        break;
                    }
                }

                if (nextValidStart > start) {
                    duration = Math.min(fallbackDuration, nextValidStart - start);
                } else if (lineEnd > start) {
                    duration = Math.min(lineEnd - start,
                        Math.max(fallbackDuration, (lineEnd - start) / Math.max(1, size - i)));
                } else {
                    duration = fallbackDuration;
                }
                if (duration <= 0) duration = fallbackDuration;
                end = start + duration;
            }

            runningStart = start;
            words.set(i, new Word(text, start, end));
        }

        for (int i = 0; i < size - 1; i++) {
            Word current = words.get(i);
            Word next = words.get(i + 1);
            if (current.getEnd() > next.getStart()) {
                long adjustedEnd = next.getStart();
                if (adjustedEnd > current.getStart()) {
                    words.set(i, new Word(current.getText(), current.getStart(), adjustedEnd));
                } else {
                    long minEnd = current.getStart()
                        + Math.max(80L, (long) Math.max(1, current.getText().length()) * 100L);
                    words.set(i, new Word(current.getText(), current.getStart(), minEnd));
                    if (next.getStart() < minEnd) {
                        long nextDuration = Math.max(100L, next.getEnd() - next.getStart());
                        words.set(i + 1, new Word(next.getText(), minEnd, minEnd + nextDuration));
                    }
                }
            }
        }

        if (lineEnd > lineStart) {
            List<Word> normalized = new ArrayList<>(size);
            long previousEnd = lineStart;
            for (Word word : words) {
                long start = Math.max(previousEnd, word.getStart());
                long end = Math.max(start, word.getEnd());

                if (start >= lineEnd) {
                    if (normalized.isEmpty()) {
                        // 没有任何前词可合并时，让首个越界词占用整行，保留其文本。
                        start = lineStart;
                        end = lineEnd;
                    } else {
                        // 行尾已无空间：把越界词合并进前词，避免重叠/乱序，也不丢文本。
                        int lastIndex = normalized.size() - 1;
                        Word previous = normalized.get(lastIndex);
                        normalized.set(lastIndex, new Word(
                            previous.getText() + word.getText(),
                            previous.getStart(),
                            lineEnd
                        ));
                        previousEnd = lineEnd;
                        continue;
                    }
                }

                if (start < lineStart) start = lineStart;
                if (end > lineEnd) end = lineEnd;
                if (end < start) end = start;
                normalized.add(new Word(word.getText(), start, end));
                previousEnd = end;
            }

            // 最后一个词仍为零时长：并入前词，保证有序且不越界。
            if (normalized.size() > 1) {
                Word last = normalized.get(normalized.size() - 1);
                if (last.getEnd() <= last.getStart()) {
                    Word previous = normalized.get(normalized.size() - 2);
                    normalized.set(normalized.size() - 2, new Word(
                        previous.getText() + last.getText(),
                        previous.getStart(),
                        lineEnd
                    ));
                    normalized.remove(normalized.size() - 1);
                }
            } else if (!normalized.isEmpty()) {
                Word only = normalized.get(0);
                if (only.getEnd() <= only.getStart()) {
                    normalized.set(0, new Word(only.getText(), lineStart, lineEnd));
                }
            }

            words.clear();
            words.addAll(normalized);
            return;
        }

        Word last = words.get(size - 1);
        if (last.getEnd() <= last.getStart()) {
            words.set(size - 1, new Word(last.getText(), last.getStart(), last.getStart() + 150L));
        }
    }
}
