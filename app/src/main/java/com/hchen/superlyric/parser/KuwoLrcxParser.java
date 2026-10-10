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
package com.hchen.superlyric.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷我 / 波点 LRCX 原生逐字歌词解析器。
 * <p>
 * <b>技术原理与逆向解析：</b>
 * <ul>
 *   <li><b>八进制动态密钥解算：</b>
 *       解析 {@code [kuwo:xxx]} 头部标签，按 8 进制反解出除数因子 {@code c = key / 10}, {@code d = key % 10}；</li>
 *   <li><b>数学拓扑字级时序：</b>
 *       针对每个词元标签 {@code <v1, v2>}，严格依公式：
 *       {@code start = (v1 + v2) / (c * 2)}，{@code end = start + (v1 - v2) / (d * 2)} 计算原生高精时序；</li>
 *   <li><b>源头杜绝宿主缺陷：</b>
 *       直接解析原始报文，彻底绕过宿主内部 {@code VerbatimLyricsParserImpl.e()} 将相邻词元重叠粗暴覆写导致的 0ms 坍缩；</li>
 *   <li><b>双语翻译自动关联：</b>
 *       同时间戳纯文本行自动关联为主歌词的翻译行。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public final class KuwoLrcxParser {
    private static final String TAG = "KuwoLrcxParser";

    private static final Pattern KUWO_HEADER_PATTERN = Pattern.compile("\\[kuwo:\\s*(\\S+?)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_PATTERN = Pattern.compile("\\[(ti|ar|al):\\s*(.+?)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_TIME_PATTERN = Pattern.compile("^\\[(-?\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern WORD_TAG_PATTERN = Pattern.compile("<(-?\\d+),(-?\\d+)(?:,-?\\d+)?>");

    private KuwoLrcxParser() {
    }

    /**
     * 解析原始 LRCX 歌词文本为全量标准 {@link SuperLyricData}。
     *
     * @param lrcxText 原始 Kuwo / Bodian LRCX 字符串
     * @return 解析完成的 SuperLyricData，失败或无歌词时返回 null
     */
    @Nullable
    public static SuperLyricData parseLrcx(@Nullable String lrcxText) {
        return parseLrcx(lrcxText, null, null, null, 0L);
    }

    /**
     * 解析原始 LRCX 歌词文本为全量标准 {@link SuperLyricData}，附带上下文元数据兜底。
     */
    @Nullable
    public static SuperLyricData parseLrcx(@Nullable String lrcxText,
                                           @Nullable String defaultTitle,
                                           @Nullable String defaultArtist,
                                           @Nullable String defaultAlbum,
                                           long defaultDuration) {
        if (lrcxText == null || lrcxText.trim().isEmpty()) {
            return null;
        }

        try {
            String title = defaultTitle != null ? defaultTitle : "";
            String artist = defaultArtist != null ? defaultArtist : "";
            String album = defaultAlbum != null ? defaultAlbum : "";

            int c = 1;
            int d = 1;

            Matcher kuwoMatcher = KUWO_HEADER_PATTERN.matcher(lrcxText);
            if (kuwoMatcher.find()) {
                String keyStr = kuwoMatcher.group(1);
                if (keyStr != null) {
                    if (keyStr.contains("][")) {
                        keyStr = keyStr.substring(0, keyStr.indexOf("]["));
                    }
                    try {
                        int keyVal = Integer.parseInt(keyStr.trim(), 8);
                        int tempC = keyVal / 10;
                        int tempD = keyVal % 10;
                        if (tempC > 0) c = tempC;
                        if (tempD > 0) d = tempD;
                    } catch (Throwable ignored) {
                    }
                }
            }

            String[] rawLines = lrcxText.split("\r?\n");
            List<ParsedLrcxLine> parsedLines = new ArrayList<>();
            List<ParsedLrcxLine> transLines = new ArrayList<>();

            for (String raw : rawLines) {
                if (raw == null) continue;
                String trimmed = raw.trim();
                if (trimmed.length() < 4) continue;

                Matcher metaMatcher = META_PATTERN.matcher(trimmed);
                if (metaMatcher.find()) {
                    String tag = metaMatcher.group(1);
                    String val = metaMatcher.group(2);
                    if (val != null) {
                        val = val.trim();
                        if ("ti".equalsIgnoreCase(tag) && title.isEmpty()) title = val;
                        else if ("ar".equalsIgnoreCase(tag) && artist.isEmpty()) artist = val;
                        else if ("al".equalsIgnoreCase(tag) && album.isEmpty()) album = val;
                    }
                    continue;
                }

                Matcher timeMatcher = LINE_TIME_PATTERN.matcher(trimmed);
                if (!timeMatcher.find()) {
                    continue;
                }

                long lineStartMs = parseTime(timeMatcher.group(1), timeMatcher.group(2), timeMatcher.group(3));
                String content = trimmed.substring(timeMatcher.end()).trim();
                if (content.isEmpty()) {
                    continue;
                }

                Matcher wordMatcher = WORD_TAG_PATTERN.matcher(content);
                List<WordTagSpan> tagSpans = new ArrayList<>();
                while (wordMatcher.find()) {
                    try {
                        long v1 = Long.parseLong(wordMatcher.group(1));
                        long v2 = Long.parseLong(wordMatcher.group(2));
                        tagSpans.add(new WordTagSpan(v1, v2, wordMatcher.start(), wordMatcher.end()));
                    } catch (Throwable ignored) {
                    }
                }

                if (tagSpans.isEmpty()) {
                    String plainText = LyricSanitizer.cleanInvisibleChars(content);
                    if (plainText != null && !plainText.isEmpty()) {
                        transLines.add(new ParsedLrcxLine(plainText, lineStartMs, null));
                    }
                } else {
                    List<SuperLyricWord> words = new ArrayList<>(tagSpans.size());
                    StringBuilder reconstructedText = new StringBuilder();

                    for (int i = 0; i < tagSpans.size(); i++) {
                        WordTagSpan span = tagSpans.get(i);
                        int textStart = span.matchEnd;
                        int textEnd = (i + 1 < tagSpans.size()) ? tagSpans.get(i + 1).matchStart : content.length();

                        String wordText = "";
                        if (textEnd > textStart) {
                            wordText = content.substring(textStart, textEnd);
                        }
                        wordText = LyricSanitizer.cleanInvisibleChars(wordText);
                        if (wordText == null) wordText = "";

                        long relStart = (span.v1 + span.v2) / ((long) c * 2L);
                        long dur = (span.v1 - span.v2) / ((long) d * 2L);
                        if (dur <= 0) {
                            dur = Math.max(120L, (long) wordText.length() * 150L);
                        }

                        long absStart;
                        long absEnd;
                        if (relStart >= lineStartMs) {
                            absStart = relStart;
                            absEnd = absStart + dur;
                        } else if (relStart >= 0) {
                            absStart = lineStartMs + relStart;
                            absEnd = absStart + dur;
                        } else {
                            absStart = lineStartMs;
                            absEnd = absStart + dur;
                        }

                        if (absEnd <= absStart) {
                            absEnd = absStart + Math.max(120L, (long) wordText.length() * 150L);
                        }

                        words.add(new SuperLyricWord(wordText, absStart, absEnd));
                        reconstructedText.append(wordText);
                    }

                    String fullText = reconstructedText.toString().trim();
                    if (fullText.isEmpty()) {
                        fullText = content.replaceAll("<(-?\\d+),(-?\\d+)(?:,-?\\d+)?>", "").trim();
                    }

                    if (!fullText.isEmpty()) {
                        parsedLines.add(new ParsedLrcxLine(fullText, lineStartMs, words));
                    }
                }
            }

            if (parsedLines.isEmpty()) {
                if (transLines.isEmpty()) return null;
                parsedLines = transLines;
                transLines = Collections.emptyList();
            }

            // 按行起始时序升序排序
            Collections.sort(parsedLines, Comparator.comparingLong(p -> p.startMs));

            // 推导每行结束时序并施加词级自愈
            for (int i = 0; i < parsedLines.size(); i++) {
                ParsedLrcxLine cur = parsedLines.get(i);
                long nextStart = (i + 1 < parsedLines.size()) ? parsedLines.get(i + 1).startMs : (cur.startMs + 5000L);
                if (cur.words != null && !cur.words.isEmpty()) {
                    long lastWordEnd = cur.words.get(cur.words.size() - 1).getEndTime();
                    cur.endMs = Math.max(lastWordEnd, Math.min(nextStart, cur.startMs + 2000L));
                    LyricSanitizer.healWordTimings(cur.words, cur.startMs, cur.endMs);
                } else {
                    cur.endMs = Math.min(nextStart, cur.startMs + 4000L);
                }
            }

            // 关联翻译行 (同时间戳或时间差 < 500ms 优先匹配)
            for (ParsedLrcxLine trans : transLines) {
                ParsedLrcxLine bestMatch = null;
                long minDiff = 500L;
                for (ParsedLrcxLine main : parsedLines) {
                    long diff = Math.abs(main.startMs - trans.startMs);
                    if (diff < minDiff) {
                        minDiff = diff;
                        bestMatch = main;
                    }
                }
                if (bestMatch != null && bestMatch.translation == null) {
                    bestMatch.translation = trans.text;
                }
            }

            SuperLyricLine[] lyricLines = new SuperLyricLine[parsedLines.size()];
            for (int i = 0; i < parsedLines.size(); i++) {
                ParsedLrcxLine pl = parsedLines.get(i);
                SuperLyricWord[] wArr = pl.words != null ? pl.words.toArray(new SuperLyricWord[0]) : null;
                SuperLyricLine rawLine = new SuperLyricLine(pl.text, wArr, pl.translation, pl.startMs, pl.endMs);
                lyricLines[i] = LyricSanitizer.sanitizeLine(rawLine);
            }

            long estimatedDuration = defaultDuration > 0 ? defaultDuration :
                (lyricLines.length > 0 ? lyricLines[lyricLines.length - 1].getEndTime() : 0L);

            SuperLyricData data = new SuperLyricData();
            data.setTitle(title);
            data.setArtist(artist);
            data.setAlbum(album);
            data.setDuration(estimatedDuration);
            data.setAllLyrics(lyricLines);

            return LyricSanitizer.sanitizeData(data);
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to parse Kuwo LRCX lyrics: " + t.getMessage());
            return null;
        }
    }

    private static long parseTime(String minStr, String secStr, @Nullable String fracStr) {
        long min = 0;
        long sec = 0;
        long frac = 0;
        try {
            min = Long.parseLong(minStr);
            sec = Long.parseLong(secStr);
            if (fracStr != null && !fracStr.isEmpty()) {
                if (fracStr.length() == 1) frac = Long.parseLong(fracStr) * 100;
                else if (fracStr.length() == 2) frac = Long.parseLong(fracStr) * 10;
                else frac = Long.parseLong(fracStr.substring(0, 3));
            }
        } catch (Throwable ignored) {
        }
        return min * 60000L + sec * 1000L + frac;
    }

    private static class WordTagSpan {
        final long v1;
        final long v2;
        final int matchStart;
        final int matchEnd;

        WordTagSpan(long v1, long v2, int matchStart, int matchEnd) {
            this.v1 = v1;
            this.v2 = v2;
            this.matchStart = matchStart;
            this.matchEnd = matchEnd;
        }
    }

    private static class ParsedLrcxLine {
        final String text;
        final long startMs;
        long endMs;
        final List<SuperLyricWord> words;
        String translation;

        ParsedLrcxLine(String text, long startMs, @Nullable List<SuperLyricWord> words) {
            this.text = text;
            this.startMs = startMs;
            this.words = words;
        }
    }
}
