/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.kernel.parse;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.hchen.superlyric.kernel.model.LyricWord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spotify color-lyrics JSON / protobuf 的纯解析
 */
public final class SpotifyLyricParser {
    private static final Gson gson = new Gson();

    private SpotifyLyricParser() {
    }

    public static List<Line> parseLyrics(String json) {
        try {
            JsonLyricResponse response = gson.fromJson(json, JsonLyricResponse.class);
            if (response == null || response.lyrics == null || response.lyrics.lines == null) {
                return null;
            }
            return normalizeLines(mapJsonLines(response.lyrics.lines));
        } catch (JsonSyntaxException | IndexOutOfBoundsException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析歌词响应：按首字节自动分流：JSON 走 Gson，protobuf 走手写 wire 解析
     */
    public static List<Line> parseLyrics(byte[] data) {
        int offset = data.length >= 3 && data[0] == (byte) 0xEF
            && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF ? 3 : 0;
        int first = offset;
        while (first < data.length && (data[first] == ' ' || data[first] == '\t'
            || data[first] == '\r' || data[first] == '\n')) {
            first++;
        }
        if (first < data.length && data[first] == '{') {
            return parseLyrics(new String(data, offset, data.length - offset, StandardCharsets.UTF_8));
        }
        return parseProtobufLyrics(data);
    }

    /**
     * 判断解析结果是否有可用时间轴。
     *
     * 全部行共用 0 起点时是平台返回的未同步歌词：行序与索引都无法随播放推进，
     * 按定时文档发布会让客户端永远高亮首行。单行 0 起点仍是合法歌词。
     */
    public static boolean hasUsableTimeline(List<Line> lines) {
        if (lines.size() < 2) return true;
        for (Line line : lines) {
            if (line.startTimeMs > 0L) return true;
        }
        return false;
    }

    private static List<RawLine> mapJsonLines(List<JsonLyricLine> jsonLines) {
        List<RawLine> lines = new ArrayList<>(jsonLines.size());
        for (JsonLyricLine jsonLine : jsonLines) {
            if (jsonLine == null) continue;
            lines.add(new RawLine(
                jsonLine.startTimeMs,
                jsonLine.words,
                jsonLine.endTimeMs,
                jsonLine.transliteratedWords,
                mapJsonSyllables(jsonLine.syllables)
            ));
        }
        return lines;
    }

    private static List<Syllable> mapJsonSyllables(List<JsonSyllable> jsonSyllables) {
        if (jsonSyllables == null || jsonSyllables.isEmpty()) return null;
        List<Syllable> syllables = new ArrayList<>(jsonSyllables.size());
        for (JsonSyllable jsonSyllable : jsonSyllables) {
            if (jsonSyllable != null) {
                syllables.add(new Syllable(
                    jsonSyllable.startTimeMs,
                    jsonSyllable.count,
                    jsonSyllable.endTimeMs
                ));
            }
        }
        return syllables.isEmpty() ? null : syllables;
    }

    private static final int MAX_PROTO_LINES = 10000;
    private static final int MAX_PROTO_SYLLABLES_PER_LINE = 10000;
    private static final int MAX_PROTO_STRING_BYTES = 64 * 1024;
    private static final int MAX_PROTO_TAGS = 200000;

    private static final class ParseBudget {
        int tags = MAX_PROTO_TAGS;
        int lines = MAX_PROTO_LINES;

        void consumeTag() throws IOException {
            if (--tags < 0) throw new IOException("Protobuf tag budget exceeded");
        }

        void consumeLine() throws IOException {
            if (--lines < 0) throw new IOException("Protobuf line budget exceeded");
        }
    }

    private static List<Line> parseProtobufLyrics(byte[] data) {
        try {
            ProtoReader reader = new ProtoReader(data, new ParseBudget());
            LyricsData lyrics = null;
            while (reader.hasMore()) {
                int tag = reader.readTag();
                int field = tag >>> 3;
                int wireType = tag & 7;
                if (field == 1 && wireType == 2) {
                    int end = reader.ensureEnd(reader.readLength());
                    lyrics = parseLyricsData(reader, end);
                } else {
                    reader.skipField(wireType);
                }
            }
            return lyrics == null ? null : normalizeLines(lyrics.lines);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static LyricsData parseLyricsData(ProtoReader reader, int end) throws IOException {
        List<RawLine> lines = new ArrayList<>();
        while (reader.pos < end) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wireType = tag & 7;
            if (field == 2 && wireType == 2) {
                reader.budget.consumeLine();
                int lineEnd = reader.ensureEnd(reader.readLength());
                if (lineEnd > end) throw new IOException("Line exceeds parent message");
                lines.add(parseLyricLine(reader, lineEnd));
            } else {
                reader.skipField(wireType);
            }
        }
        if (reader.pos != end) throw new IOException("Lyrics message boundary mismatch");
        return new LyricsData(lines);
    }

    private static RawLine parseLyricLine(ProtoReader reader, int end) throws IOException {
        long startTimeMs = 0L;
        String words = null;
        List<Syllable> syllables = null;
        while (reader.pos < end) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wireType = tag & 7;
            if (wireType == 0) {
                long value = reader.readVarint();
                if (field == 1) {
                    startTimeMs = value;
                }
            } else if (wireType == 2) {
                int len = reader.readLength();
                int fieldEnd = reader.ensureEnd(len);
                if (fieldEnd > end) throw new IOException("Field exceeds parent message");
                if (field == 2) {
                    if (len > MAX_PROTO_STRING_BYTES)
                        throw new IOException("Lyric string too large");
                    words = new String(reader.readBytes(len), StandardCharsets.UTF_8);
                } else if (field == 3) {
                    if (syllables == null) syllables = new ArrayList<>();
                    if (syllables.size() >= MAX_PROTO_SYLLABLES_PER_LINE) {
                        throw new IOException("Syllable budget exceeded");
                    }
                    Syllable syllable = parseSyllable(reader, fieldEnd);
                    syllables.add(syllable);
                } else {
                    reader.skip(len);
                }
            } else {
                reader.skipField(wireType);
            }
        }
        if (reader.pos != end) throw new IOException("Line message boundary mismatch");
        return new RawLine(startTimeMs, words, 0L, null, syllables);
    }

    private static Syllable parseSyllable(ProtoReader reader, int end) throws IOException {
        long startTimeMs = 0L;
        int count = 0;
        long endTimeMs = 0L;
        while (reader.pos < end) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wireType = tag & 7;
            if (wireType == 0) {
                long value = reader.readVarint();
                if (field == 1) {
                    startTimeMs = value;
                } else if (field == 2) {
                    if (value > Integer.MAX_VALUE) throw new IOException("Invalid syllable count");
                    count = (int) value;
                } else if (field == 3) {
                    endTimeMs = value;
                }
            } else {
                reader.skipField(wireType);
            }
        }
        if (reader.pos != end) throw new IOException("Syllable message boundary mismatch");
        return new Syllable(startTimeMs, count, endTimeMs);
    }

    private static final class ProtoReader {
        private final byte[] data;
        private final ParseBudget budget;
        private int pos;

        ProtoReader(byte[] data, ParseBudget budget) {
            this.data = data;
            this.budget = budget;
        }

        boolean hasMore() {
            return pos < data.length;
        }

        long readVarint() throws IOException {
            long result = 0;
            int shift = 0;
            while (shift < 64) {
                if (pos >= data.length) throw new IOException("Truncated varint");
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IOException("Malformed varint");
        }

        int readLength() throws IOException {
            long len = readVarint();
            if (len < 0 || len > Integer.MAX_VALUE) throw new IOException("Invalid length: " + len);
            return (int) len;
        }

        int readTag() throws IOException {
            budget.consumeTag();
            long tag = readVarint();
            if (tag <= 0 || tag > 0xFFFFFFFFL) throw new IOException("Invalid tag: " + tag);
            return (int) tag;
        }

        void skip(int count) throws IOException {
            if (count < 0 || count > data.length - pos) throw new IOException("Truncated skip");
            pos += count;
        }

        int ensureEnd(int length) throws IOException {
            if (length < 0 || length > data.length - pos) throw new IOException("Truncated field");
            return pos + length;
        }

        byte[] readBytes(int length) throws IOException {
            if (length < 0 || length > data.length - pos) throw new IOException("Truncated bytes");
            byte[] bytes = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return bytes;
        }

        void skipField(int wireType) throws IOException {
            switch (wireType) {
                case 0:
                    readVarint();
                    break;
                case 1:
                    skip(8);
                    break;
                case 2:
                    skip(readLength());
                    break;
                case 5:
                    skip(4);
                    break;
                default:
                    throw new IOException("Unsupported wire type: " + wireType);
            }
        }
    }

    private static List<Line> normalizeLines(List<RawLine> lines) {
        List<RawLine> validLines = new ArrayList<>(lines.size());
        for (RawLine line : lines) {
            if (line != null && line.startTimeMs >= 0L
                && line.words != null && !line.words.trim().isEmpty()) {
                validLines.add(line);
            }
        }
        validLines.sort((left, right) -> Long.compare(left.startTimeMs, right.startTimeMs));

        List<Line> result = new ArrayList<>(validLines.size());
        for (int i = 0; i < validLines.size(); i++) {
            RawLine line = validLines.get(i);
            long endTimeMs = effectiveEndTime(validLines, i, line);
            if (i + 1 < validLines.size()) {
                endTimeMs = Math.min(endTimeMs, validLines.get(i + 1).startTimeMs);
            }
            // 同一时间戳的多行（对唱/双语）不能因为 end == start 被整行丢弃；
            // 给 1ms 的可渲染区间，消费方按 start 切行。
            if (endTimeMs == line.startTimeMs) endTimeMs = line.startTimeMs + 1L;

            result.add(new Line(
                line.words,
                line.startTimeMs,
                endTimeMs,
                line.transliteratedWords,
                toWords(line.words, line.syllables, line.startTimeMs, endTimeMs)
            ));
        }
        return result;
    }

    private static long effectiveEndTime(List<RawLine> lines, int index, RawLine line) {
        if (line.endTimeMs > line.startTimeMs) return line.endTimeMs;
        if (index + 1 < lines.size()) {
            return lines.get(index + 1).startTimeMs;
        }
        return line.startTimeMs <= Long.MAX_VALUE - 5000L
            ? line.startTimeMs + 5000L
            : Long.MAX_VALUE;
    }

    private static List<LyricWord> toWords(String text, List<Syllable> syllables,
                                                      long lineStart, long lineEnd) {
        if (syllables == null || syllables.isEmpty()) return null;

        List<LyricWord> result = new ArrayList<>(syllables.size());
        int cursor = 0;
        long previousEnd = lineStart;
        for (int i = 0; i < syllables.size(); i++) {
            Syllable syllable = syllables.get(i);
            int count = syllable.count;
            int nextCursor;
            if (count <= 0 || count > text.length() - cursor) {
                return null;
            }
            nextCursor = cursor + count;
            if (syllable.startTimeMs < previousEnd || syllable.endTimeMs <= syllable.startTimeMs
                || syllable.startTimeMs < lineStart || syllable.endTimeMs > lineEnd) {
                return null;
            }
            result.add(new LyricWord(
                text.substring(cursor, nextCursor),
                syllable.startTimeMs,
                syllable.endTimeMs
            ));
            cursor = nextCursor;
            previousEnd = syllable.endTimeMs;
        }
        return cursor == text.length() ? result : null;
    }
    public static final class Line {
        public final String text;
        public final long startTimeMs;
        public final long endTimeMs;
        public final String transliteratedWords;
        public final List<LyricWord> words;

        Line(String text, long startTimeMs, long endTimeMs,
             String transliteratedWords, List<LyricWord> words) {
            this.text = text;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.transliteratedWords = transliteratedWords;
            this.words = words;
        }
    }

    private static final class RawLine {
        final long startTimeMs;
        final String words;
        final long endTimeMs;
        final String transliteratedWords;
        final List<Syllable> syllables;

        RawLine(long startTimeMs, String words, long endTimeMs,
                String transliteratedWords, List<Syllable> syllables) {
            this.startTimeMs = startTimeMs;
            this.words = words;
            this.endTimeMs = endTimeMs;
            this.transliteratedWords = transliteratedWords;
            this.syllables = syllables;
        }
    }

    private static final class LyricsData {
        final List<RawLine> lines;

        LyricsData(List<RawLine> lines) {
            this.lines = lines;
        }
    }

    private static final class Syllable {
        final long startTimeMs;
        final int count;
        final long endTimeMs;

        Syllable(long startTimeMs, int count, long endTimeMs) {
            this.startTimeMs = startTimeMs;
            this.count = count;
            this.endTimeMs = endTimeMs;
        }
    }

    private static final class JsonLyricResponse {
        JsonLyricsData lyrics;
    }

    private static final class JsonLyricsData {
        List<JsonLyricLine> lines;
    }

    private static final class JsonLyricLine {
        long startTimeMs;
        String words;
        long endTimeMs;
        String transliteratedWords;
        List<JsonSyllable> syllables;
    }

    private static final class JsonSyllable {
        long startTimeMs;
        int count;
        long endTimeMs;
    }
}
