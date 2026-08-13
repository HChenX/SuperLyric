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
package com.hchen.superlyric.hook.music.online.spotify;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyricapi.SuperLyricWord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Spotify 歌词数据链路辅助：会话头捕获 + 私有歌词接口拉取 + JSON / protobuf 解析 + 行数据规整。
 * <p>
 * Inspired from tomakino/LyricProvider/spotify-music.
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
public final class SpotifyLyricAnalysis {
    private static final String TAG = "SpotifyLyricAnalysis";

    private static final String BASE_URL = "https://gae2-spclient.spotify.com/color-lyrics/v2/track/";

    /**
     * 私有歌词接口所需的 4 个关键会话头（不可变）。
     */
    private static final List<String> KEYS_REQUIRED = List.of(
        "authorization",
        "client-token",
        "user-agent",
        "x-client-id"
    );

    /**
     * 会话头存储：key 统一小写，由 hook 填充，全局复用。
     */
    private static final Map<String, String> mHeaders = new ConcurrentHashMap<>();

    private static volatile boolean mHeaderCompleteLogged = false;

    private static final Gson gson = new Gson();

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();

    private SpotifyLyricAnalysis() {
    }

    // ------------------------------ 会话头捕获 ------------------------------

    /**
     * 判断是否为关键会话头（key 需已小写）。
     */
    public static boolean isKeyRequired(@NonNull String keyLowercase) {
        return KEYS_REQUIRED.contains(keyLowercase);
    }

    /**
     * 保存捕获到的会话头（key 已小写）。
     */
    public static void setHeader(@NonNull String keyLowercase, @NonNull String value) {
        mHeaders.put(keyLowercase, value);
        XposedLog.logD(TAG, "Captured session header: " + keyLowercase + "=" + mask(value));
        if (!mHeaderCompleteLogged && hasAllRequiredHeaders()) {
            mHeaderCompleteLogged = true;
            XposedLog.logI(TAG, "All 4 required session headers captured: " + KEYS_REQUIRED);
        }
    }

    /**
     * 会话头是否已齐全（4 个关键头全部捕获）。
     */
    public static boolean hasAllRequiredHeaders() {
        return mHeaders.keySet().containsAll(KEYS_REQUIRED);
    }

    private static String mask(@NonNull String value) {
        int len = value.length();
        if (len <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(len - 4);
    }

    // ------------------------------ 网络拉取 ------------------------------

    /**
     * 拉取指定音轨的歌词原始响应（protobuf 编码）。
     *
     * @param id 音轨标识（已剥 spotify:track: 前缀）
     * @return 歌词接口原始响应字节
     * @throws NoFoundLyricException 接口 404（无歌词）
     * @throws IOException           网络错误 / 非成功状态码
     */
    @NonNull
    public static byte[] fetchLyric(@NonNull String id) throws IOException {
        String url = BASE_URL + id
            + "?vocalRemoval=true&clientLanguage=" + Locale.getDefault().toLanguageTag()
            + "&preview=false";

        Request.Builder builder = new Request.Builder()
            .url(url)
            .get()
            // 请求头切到 protobuf 编码：同一接口的 JSON 响应不带逐字（syllables）数据，
            // 仅 protobuf 响应携带逐字时间轴
            .addHeader("accept", "application/protobuf")
            .addHeader("content-type", "application/protobuf")
            .addHeader("app-platform", "Android");
        mHeaders.forEach(builder::addHeader);

        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            if (code == 404) {
                throw new NoFoundLyricException(id, "No lyric found for " + id);
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP error code: " + code + ", msg: " + response.message());
            }

            return response.body().bytes();
        }
    }

    // ------------------------------ 解析与规整 ------------------------------

    /**
     * 解析歌词 JSON 并规整行数据（照源 Converter 行为）：
     * <ul>
     *   <li>{@code endTimeMs == 0} → 取下一行 {@code startTimeMs}；最后一行兜底 {@code +5000ms}</li>
     *   <li>空文本行跳过</li>
     * </ul>
     *
     * @return 规整后的行列表；解析失败或无歌词返回 {@code null}
     */
    @Nullable
    public static List<SpotifyLine> parseLyrics(@NonNull String json) {
        try {
            JsonLyricResponse response = gson.fromJson(json, JsonLyricResponse.class);
            if (response == null || response.lyrics == null || response.lyrics.lines == null) {
                XposedLog.logD(TAG, "No lyrics data in response");
                return null;
            }
            return normalizeLines(mapJsonLines(response.lyrics.lines));
        } catch (JsonSyntaxException e) {
            XposedLog.logE(TAG, "Failed to parse lyric json", e);
            return null;
        }
    }

    /**
     * 解析歌词响应：按首字节自动分流——JSON 走 Gson，protobuf 走手写 wire 解析。
     */
    @Nullable
    public static List<SpotifyLine> parseLyrics(@NonNull byte[] data) {
        if (data.length > 0 && data[0] == '{') {
            return parseLyrics(new String(data, StandardCharsets.UTF_8));
        }
        return parseProtobufLyrics(data);
    }

    /**
     * JSON DTO → 不可变解析模型；空文本行保留原样，由规整阶段统一跳过。
     */
    @NonNull
    private static List<LyricLine> mapJsonLines(@NonNull List<JsonLyricLine> jsonLines) {
        List<LyricLine> lines = new ArrayList<>(jsonLines.size());
        for (JsonLyricLine jsonLine : jsonLines) {
            if (jsonLine == null) continue;
            lines.add(new LyricLine(
                jsonLine.startTimeMs,
                jsonLine.words,
                jsonLine.endTimeMs,
                jsonLine.transliteratedWords,
                mapJsonSyllables(jsonLine.syllables)
            ));
        }
        return lines;
    }

    /**
     * JSON 逐字块 → 不可变模型；空列表视为无逐字（返回 null，退回纯行级）。
     */
    @Nullable
    private static List<Syllable> mapJsonSyllables(@Nullable List<JsonSyllable> jsonSyllables) {
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

    // ------------------------------ protobuf 解析（手写 wire format） ------------------------------

    /**
     * 解析 color-lyrics 接口的 protobuf 响应（schema 由 Surge 抓包逆向）：
     * <pre>
     * LyricResponse { LyricsData lyrics = 1; Colors colors = 2; }
     * LyricsData    { int32 sync_type = 1; repeated LyricLine lines = 2;
     *                 string provider = 3; provider_lyrics_id = 4; provider_display_name = 5;
     *                 repeated LyricLine preview_lines = 17; }
     * LyricLine     { int64 start_time_ms = 1; string words = 2;
     *                 repeated Syllable syllables = 3; }
     * Syllable      { int64 start_time_ms = 1; int32 count = 2; int64 end_time_ms = 3; }
     * </pre>
     * 逐字粒度是音节级：{@code Syllable.count} 为 UTF-16 字符数，按累计游标从
     * {@code words} 切片重建文本（见 {@link #toSuperLyricWords}）。
     */
    @Nullable
    private static List<SpotifyLine> parseProtobufLyrics(@NonNull byte[] data) {
        try {
            ProtoReader reader = new ProtoReader(data);
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
        } catch (Throwable t) {
            XposedLog.logE(TAG, "Failed to parse lyric protobuf", t);
            return null;
        }
    }

    @NonNull
    private static LyricsData parseLyricsData(@NonNull ProtoReader reader, int end) throws IOException {
        List<LyricLine> lines = new ArrayList<>();
        while (reader.pos < end) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wireType = tag & 7;
            if (field == 2 && wireType == 2) {
                int lineEnd = reader.ensureEnd(reader.readLength());
                lines.add(parseLyricLine(reader, lineEnd));
            } else {
                reader.skipField(wireType);
            }
        }
        return new LyricsData(lines);
    }

    @NonNull
    private static LyricLine parseLyricLine(@NonNull ProtoReader reader, int end) throws IOException {
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
                if (field == 2) {
                    words = new String(reader.readBytes(len), StandardCharsets.UTF_8);
                } else if (field == 3) {
                    if (syllables == null) syllables = new ArrayList<>();
                    Syllable syllable = parseSyllable(reader, fieldEnd);
                    syllables.add(syllable);
                } else {
                    reader.skip(len);
                }
            } else {
                reader.skipField(wireType);
            }
        }
        // schema 中行级 end_time_ms 不存在（缺省为 0），由规整阶段按下一行兜底
        return new LyricLine(startTimeMs, words, 0L, null, syllables);
    }

    @NonNull
    private static Syllable parseSyllable(@NonNull ProtoReader reader, int end) throws IOException {
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
                    count = (int) value;
                } else if (field == 3) {
                    endTimeMs = value;
                }
            } else {
                reader.skipField(wireType);
            }
        }
        return new Syllable(startTimeMs, count, endTimeMs);
    }

    /**
     * protobuf wire format 最小读取器：只实现 varint / 定长 / length-delimited，
     * 未知字段按 wire type 跳过。
     */
    private static final class ProtoReader {
        private final byte[] data;
        private int pos;

        ProtoReader(@NonNull byte[] data) {
            this.data = data;
        }

        boolean hasMore() {
            return pos < data.length;
        }

        long readVarint() throws IOException {
            long result = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) throw new IOException("Truncated varint");
                int b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift > 63) throw new IOException("Varint too long");
            }
        }

        int readTag() throws IOException {
            long tag = readVarint();
            if (tag <= 0 || tag > 0xFFFFFFFFL) throw new IOException("Invalid tag: " + tag);
            return (int) tag;
        }

        int readLength() throws IOException {
            long len = readVarint();
            if (len < 0 || len > Integer.MAX_VALUE) throw new IOException("Invalid length: " + len);
            return (int) len;
        }

        int ensureEnd(int length) throws IOException {
            int end = pos + length;
            if (length < 0 || end > data.length) throw new IOException("Truncated field");
            return end;
        }

        @NonNull
        byte[] readBytes(int length) throws IOException {
            ensureEnd(length);
            byte[] out = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return out;
        }

        void skip(int length) throws IOException {
            ensureEnd(length);
            pos += length;
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

    @NonNull
    private static List<SpotifyLine> normalizeLines(@NonNull List<LyricLine> lines) {
        List<SpotifyLine> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            if (line == null || line.words == null || line.words.trim().isEmpty()) {
                continue;
            }

            result.add(new SpotifyLine(
                line.words,
                line.startTimeMs,
                effectiveEndTime(lines, i, line),
                line.transliteratedWords,
                toSuperLyricWords(line.words, line.syllables)
            ));
        }
        return result;
    }

    /**
     * 行结束时间：显式 {@code endTimeMs} 缺失（0）时取下一行 {@code startTimeMs}，
     * 最后一行兜底 {@code +5000ms}。
     */
    private static long effectiveEndTime(@NonNull List<LyricLine> lines, int index, @NonNull LyricLine line) {
        if (line.endTimeMs != 0L) return line.endTimeMs;
        if (index + 1 < lines.size() && lines.get(index + 1) != null) {
            return lines.get(index + 1).startTimeMs;
        }
        return line.startTimeMs + 5000L;
    }

    /**
     * 逐字切片：syllables 的 {@code count} 为字符数，按累计游标从 words 中切出每段文本；
     * 计数与文本长度不匹配时放弃逐字（返回 null，退回纯行级）。
     */
    @Nullable
    private static SuperLyricWord[] toSuperLyricWords(@NonNull String text, @Nullable List<Syllable> syllables) {
        if (syllables == null || syllables.isEmpty()) return null;

        SuperLyricWord[] result = new SuperLyricWord[syllables.size()];
        int cursor = 0;
        for (int i = 0; i < syllables.size(); i++) {
            Syllable syllable = syllables.get(i);
            int count = syllable.count;
            if (count <= 0 || cursor + count > text.length()) {
                return null;
            }
            result[i] = new SuperLyricWord(
                text.substring(cursor, cursor + count),
                syllable.startTimeMs,
                syllable.endTimeMs
            );
            cursor += count;
        }
        return cursor == text.length() ? result : null;
    }

    // ------------------------------ 数据模型 ------------------------------

    /**
     * 规整后的歌词行。
     */
    public static final class SpotifyLine {
        @NonNull
        public final String text;
        public final long startTimeMs;
        public final long endTimeMs;
        @Nullable
        public final String transliteratedWords;
        @Nullable
        public final SuperLyricWord[] words;

        public SpotifyLine(@NonNull String text, long startTimeMs, long endTimeMs,
                           @Nullable String transliteratedWords, @Nullable SuperLyricWord[] words) {
            this.text = text;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.transliteratedWords = transliteratedWords;
            this.words = words;
        }

        @NonNull
        @Override
        public String toString() {
            return "SpotifyLine{start=" + startTimeMs
                + ", end=" + endTimeMs
                + ", text='" + text + '\''
                + ", transliteratedWords='" + transliteratedWords + '\''
                + ", words=" + (words == null ? null : words.length)
                + '}';
        }
    }

    /**
     * 无歌词异常：接口 404 时抛出，视为无歌词（不发任何 lyric）。
     */
    public static final class NoFoundLyricException extends RuntimeException {
        @NonNull
        public final String id;

        public NoFoundLyricException(@NonNull String id, @NonNull String message) {
            super(message);
            this.id = id;
        }
    }

    // JSON 响应 DTO（Gson 反射填充；可变字段仅限 DTO 边界，不参与业务流转）

    private static final class JsonLyricResponse {
        @Nullable
        JsonLyricsData lyrics;
    }

    private static final class JsonLyricsData {
        @Nullable
        List<JsonLyricLine> lines;
    }

    private static final class JsonLyricLine {
        long startTimeMs;
        @Nullable
        String words;
        long endTimeMs;
        @Nullable
        String transliteratedWords;
        @Nullable
        List<JsonSyllable> syllables;
    }

    private static final class JsonSyllable {
        long startTimeMs;
        int count;
        long endTimeMs;
    }

    // 解析后的不可变模型（JSON 与 protobuf 两路共用）

    /** 解析后的歌词数据（不可变）。 */
    private static final class LyricsData {
        @NonNull
        final List<LyricLine> lines;

        LyricsData(@NonNull List<LyricLine> lines) {
            this.lines = lines;
        }
    }

    /** 解析后的歌词行（不可变）。 */
    private static final class LyricLine {
        final long startTimeMs;
        @Nullable
        final String words;
        final long endTimeMs;
        @Nullable
        final String transliteratedWords;
        @Nullable
        final List<Syllable> syllables;

        LyricLine(long startTimeMs, @Nullable String words, long endTimeMs,
                  @Nullable String transliteratedWords, @Nullable List<Syllable> syllables) {
            this.startTimeMs = startTimeMs;
            this.words = words;
            this.endTimeMs = endTimeMs;
            this.transliteratedWords = transliteratedWords;
            this.syllables = syllables;
        }
    }

    /** 解析后的逐字块（不可变）：{@code count} 为 UTF-16 字符数。 */
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
}
