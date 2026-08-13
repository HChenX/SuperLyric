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
package com.hchen.superlyric.hook.music.online.netease;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyricapi.SuperLyricWord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.brotli.BrotliInterceptor;

/**
 * 网易云歌词数据链路辅助：eapi 加密 + 网络拉取 + gson 解析 + 行级数据模型。
 * <p>
 * 歌词接口为 eapi {@code song/lyric/v1}：一次拉取全量歌词（原词 / 翻译 / 逐字原词 /
 * 逐字翻译 / 音译 / 纯音乐标志），无任何请求头 / cookie。
 * <p>
 * Inspired from tomakino/LyricProvider/163-music.
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
public final class NeteaseLyricAnalysis {
    private static final String TAG = "NeteaseLyricAnalysis";

    private static final String BASE_URL = "https://interface.music.163.com/eapi/";
    private static final String LYRIC_ENDPOINT = "song/lyric/v1";

    private static final String E_API_KEY = "e82ckenh8dichen8";
    private static final String E_API_FORMAT = "%s-36cd479b6b5-%s-36cd479b6b5-%s";
    private static final String E_API_SALT = "nobody%suse%smd5forencrypt";

    private static final Pattern YRC_LINE_HEADER_REGEX = Pattern.compile("\\[(\\d+),(\\d+)]");
    private static final Pattern YRC_SYLLABLE_REGEX = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)");
    private static final Pattern LRC_LINE_VALIDATOR =
        Pattern.compile("^\\[\\d{1,3}[ :.]\\d{2}(?:[ :.]\\d{1,3})?].*");
    private static final Pattern LRC_TAG_PATTERN =
        Pattern.compile("\\[(\\d{1,3})[ :.](\\d{2})(?:[ :.](\\d{1,3}))?]");

    private static final Gson gson = new Gson();

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(BrotliInterceptor.INSTANCE)
        .build();

    static {
        // 模块内 okhttp 自检：确认客户端正常创建。
        XposedLog.logD(TAG, "OkHttpClient created: okhttpClass=" + OkHttpClient.class.getName()
            + ", classloader=" + OkHttpClient.class.getClassLoader()
            + ", brotliInterceptor=okhttp3.brotli.BrotliInterceptor");
    }

    private NeteaseLyricAnalysis() {
    }

    // ------------------------------ eapi 加密 ------------------------------

    /**
     * eapi 加密：MD5 摘要 + AES/ECB/PKCS5Padding。
     *
     * @param url      请求路由，如 {@code /eapi/song/lyric/v1}
     * @param jsonData 业务参数的 JSON 字符串
     * @return 大写 Hex 密文
     */
    @NonNull
    public static String eApiEncrypt(@NonNull String url, @NonNull String jsonData) {
        String modifiedUrl = url.replace("eapi", "api");
        String digest = md5Hex(String.format(E_API_SALT, modifiedUrl, jsonData));
        String text = String.format(E_API_FORMAT, modifiedUrl, jsonData, digest);
        return aesEncryptEcb(text, E_API_KEY).toUpperCase(Locale.ENGLISH);
    }

    @NonNull
    private static String md5Hex(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return hex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    @NonNull
    private static String aesEncryptEcb(@NonNull String text, @NonNull String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return hex(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("AES/ECB encryption failed", e);
        }
    }

    @NonNull
    private static String hex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------ 网络拉取 ------------------------------

    /**
     * 拉取指定歌曲的歌词接口原始 JSON。
     *
     * @param id 网易云歌曲数字音轨标识
     * @return 接口原始 JSON 字符串（已确认 JSON 可解析）
     * @throws IOException 网络错误 / 非成功状态码 / JSON 非法
     */
    @NonNull
    public static String fetchLyricJson(long id) throws IOException {
        String url = BASE_URL + LYRIC_ENDPOINT;
        String jsonParams = buildLyricParams(id);
        String encrypted = eApiEncrypt("/eapi/" + LYRIC_ENDPOINT, jsonParams);

        FormBody body = new FormBody.Builder(StandardCharsets.UTF_8)
            .add("params", encrypted)
            .build();
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .build();

        try (Response response = client.newCall(request).execute()) {
            String bodyStr = response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP error code: " + response.code() + ", msg: " + response.message());
            }

            try {
                gson.fromJson(bodyStr, JsonObject.class);
            } catch (JsonSyntaxException e) {
                throw new IOException("Invalid JSON response for " + id + ": " + bodyStr, e);
            }
            XposedLog.logD(TAG, "eapi lyric response: httpCode=" + response.code()
                + ", jsonLength=" + bodyStr.length() + ", brotli decode ok");
            return bodyStr;
        }
    }

    @NonNull
    private static String buildLyricParams(long id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", String.valueOf(id));
        params.put("cp", false);
        params.put("lv", 0);
        params.put("tv", 0);
        params.put("rv", 0);
        params.put("yv", 0);
        params.put("ytv", 0);
        params.put("yrv", 0);
        return gson.toJson(params);
    }

    // ------------------------------ 解析与数据模型 ------------------------------

    /**
     * 解析接口原始 JSON 为行级数据模型：
     * <ul>
     *   <li>逐字原词（yrc）优先 → LRC 纯文本回退</li>
     *   <li>翻译 ytlrc 优先 → tlyric</li>
     *   <li>音译 romalrc</li>
     *   <li>翻译 / 音译按 {@code findClosest(begin, 1000)} 容差匹配到源行</li>
     * </ul>
     *
     * @param json 接口原始 JSON
     * @return 解析结果（含纯音乐标志；无歌词 / 404 时 lines 为 null）
     */
    @NonNull
    public static LyricData parseLyrics(@NonNull String json) {
        try {
            LyricResponse response = gson.fromJson(json, LyricResponse.class);
            if (response == null) {
                return new LyricData(0, false, null);
            }
            if (response.code != 200) {
                XposedLog.logD(TAG, "eapi lyric code=" + response.code + ", treat as no lyric");
                return new LyricData(response.code, response.pureMusic, null);
            }
            List<LyricLineData> lines = buildLines(response);
            return new LyricData(
                response.code,
                response.pureMusic,
                lines != null ? Collections.unmodifiableList(lines) : null
            );
        } catch (JsonSyntaxException e) {
            XposedLog.logE(TAG, "Failed to parse netease lyric json", e);
            return new LyricData(0, false, null);
        }
    }

    @Nullable
    private static List<LyricLineData> buildLines(@NonNull LyricResponse response) {
        List<ParsedLine> source = parseSourceLines(response);
        if (source == null || source.isEmpty()) return null;

        List<ParsedLine> translations = parseTranslationLines(response);
        List<ParsedLine> romas = parseRomaLines(response);

        return source.stream()
            .map(line -> new LyricLineData(
                line.begin,
                line.end,
                line.text,
                toSuperLyricWords(line.words),
                Optional.ofNullable(findClosest(translations, line.begin, 1000L))
                    .map(match -> match.text)
                    .orElse(null),
                Optional.ofNullable(findClosest(romas, line.begin, 1000L))
                    .map(match -> match.text)
                    .orElse(null)
            ))
            .collect(Collectors.toList());
    }

    @Nullable
    private static List<ParsedLine> parseSourceLines(@NonNull LyricResponse response) {
        if (response.yrc != null && !TextUtils.isEmpty(response.yrc.lyric)) {
            List<ParsedLine> lines = parseYrc(response.yrc.lyric);
            if (!lines.isEmpty()) return lines;
        }
        if (response.lrc != null && !TextUtils.isEmpty(response.lrc.lyric)) {
            List<ParsedLine> lines = parseLrc(response.lrc.lyric);
            if (!lines.isEmpty()) return lines;
        }
        return null;
    }

    @Nullable
    private static List<ParsedLine> parseTranslationLines(@NonNull LyricResponse response) {
        if (response.ytlrc != null && !TextUtils.isEmpty(response.ytlrc.lyric)) {
            List<ParsedLine> lines = parseLrc(response.ytlrc.lyric);
            if (!lines.isEmpty()) return lines;
        }
        if (response.tlyric != null && !TextUtils.isEmpty(response.tlyric.lyric)) {
            List<ParsedLine> lines = parseLrc(response.tlyric.lyric);
            if (!lines.isEmpty()) return lines;
        }
        return null;
    }

    @Nullable
    private static List<ParsedLine> parseRomaLines(@NonNull LyricResponse response) {
        if (response.romalrc != null && !TextUtils.isEmpty(response.romalrc.lyric)) {
            List<ParsedLine> lines = parseLrc(response.romalrc.lyric);
            if (!lines.isEmpty()) return lines;
        }
        return null;
    }

    /**
     * 逐字原词解析：行头 {@code [开始,时长]} + 逐字 {@code (开始,时长,色)字}。
     */
    @NonNull
    private static List<ParsedLine> parseYrc(@Nullable String raw) {
        List<ParsedLine> entries = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return entries;

        for (String rawLine : raw.split("\\r?\\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("{")) continue;

            Matcher headerMatcher = YRC_LINE_HEADER_REGEX.matcher(line);
            if (!headerMatcher.find()) continue;

            long lineStart = parseLong(headerMatcher.group(1));
            long lineDuration = parseLong(headerMatcher.group(2));
            long lineEnd = lineStart + lineDuration;

            List<ParsedWord> words = new ArrayList<>();
            String contentPart = line.substring(headerMatcher.end());
            Matcher wordMatcher = YRC_SYLLABLE_REGEX.matcher(contentPart);
            while (wordMatcher.find()) {
                long start = parseLong(wordMatcher.group(1));
                long duration = parseLong(wordMatcher.group(2));
                String text = wordMatcher.group(3);
                if (text == null || text.isEmpty()) continue;
                words.add(new ParsedWord(start, start + duration, text));
            }
            words.sort(Comparator.comparingLong(word -> word.begin));

            StringBuilder textBuilder = new StringBuilder();
            for (ParsedWord word : words) {
                textBuilder.append(word.text);
            }
            String text = textBuilder.toString();
            if (text.isBlank()) continue;

            entries.add(new ParsedLine(lineStart, lineEnd, text, words));
        }

        entries.sort(Comparator.comparingLong(entry -> entry.begin));
        return Collections.unmodifiableList(entries);
    }

    /**
     * LRC 解析：跳过非 {@code [} 开头的行，兼容 JSON 元数据头；
     * 末行结束时间兜底 {@code +5000ms}，支持 {@code [offset:]} 全局偏移。
     */
    @NonNull
    private static List<ParsedLine> parseLrc(@Nullable String raw) {
        List<ParsedLine> entries = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return entries;

        Map<String, String> meta = new HashMap<>();
        for (String rawLine : raw.split("\\r?\\n", -1)) {
            String trimmed = rawLine.trim();
            if (!trimmed.startsWith("[")) continue;

            if (LRC_LINE_VALIDATOR.matcher(trimmed).matches()) {
                Matcher tagMatcher = LRC_TAG_PATTERN.matcher(trimmed);
                List<Long> times = new ArrayList<>();
                int lastTagEnd = 0;
                while (tagMatcher.find()) {
                    if (tagMatcher.start() != lastTagEnd) break;
                    times.add(toMs(tagMatcher.group(1), tagMatcher.group(2), tagMatcher.group(3)));
                    lastTagEnd = tagMatcher.end();
                }
                String content = trimmed.substring(lastTagEnd).trim();
                if (content.isEmpty()) continue;
                for (Long time : times) {
                    entries.add(new ParsedLine(time, time, content, Collections.emptyList()));
                }
            } else {
                parseLrcMeta(trimmed, meta);
            }
        }
        return Collections.unmodifiableList(finalizeLrc(entries, meta));
    }

    private static void parseLrcMeta(@NonNull String line, @NonNull Map<String, String> meta) {
        int colon = line.indexOf(':');
        if (colon > 1 && line.endsWith("]")) {
            String key = line.substring(1, colon).trim();
            String value = line.substring(colon + 1, line.length() - 1).trim();
            meta.put(key, value);
        }
    }

    @NonNull
    private static List<ParsedLine> finalizeLrc(@NonNull List<ParsedLine> entries, @NonNull Map<String, String> meta) {
        entries.sort(Comparator.comparingLong(entry -> entry.begin));

        List<ParsedLine> finalized = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            ParsedLine current = entries.get(i);
            long end = i + 1 < entries.size() ? entries.get(i + 1).begin : current.begin + 5000L;
            finalized.add(new ParsedLine(current.begin, end, current.text, current.words));
        }

        long offset = parseLong(meta.get("offset"));
        if (offset == 0L) return finalized;

        List<ParsedLine> adjusted = new ArrayList<>(finalized.size());
        for (ParsedLine line : finalized) {
            long newBegin = Math.max(0L, line.begin + offset);
            long newEnd = newBegin + (line.end - line.begin);
            adjusted.add(new ParsedLine(newBegin, newEnd, line.text, line.words));
        }
        return adjusted;
    }

    /**
     * 在已排序列表中查找与目标开始时间最接近且误差在容差内的行。
     */
    @Nullable
    private static ParsedLine findClosest(@Nullable List<ParsedLine> lines, long targetBegin, long tolerance) {
        if (lines == null || lines.isEmpty()) return null;

        int index = binarySearchBegin(lines, targetBegin);
        if (index >= 0) return lines.get(index);

        int insertionPoint = -(index + 1);
        ParsedLine best = null;
        long bestDiff = Long.MAX_VALUE;
        if (insertionPoint < lines.size()) {
            ParsedLine candidate = lines.get(insertionPoint);
            long diff = Math.abs(candidate.begin - targetBegin);
            if (diff <= tolerance && diff < bestDiff) {
                best = candidate;
                bestDiff = diff;
            }
        }
        if (insertionPoint > 0) {
            ParsedLine candidate = lines.get(insertionPoint - 1);
            long diff = Math.abs(candidate.begin - targetBegin);
            if (diff <= tolerance && diff < bestDiff) {
                best = candidate;
            }
        }
        return best;
    }

    private static int binarySearchBegin(@NonNull List<ParsedLine> lines, long target) {
        int low = 0;
        int high = lines.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midValue = lines.get(mid).begin;
            if (midValue < target) {
                low = mid + 1;
            } else if (midValue > target) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -(low + 1);
    }

    @Nullable
    private static SuperLyricWord[] toSuperLyricWords(@NonNull List<ParsedWord> words) {
        if (words.isEmpty()) return null;
        SuperLyricWord[] result = new SuperLyricWord[words.size()];
        for (int i = 0; i < words.size(); i++) {
            ParsedWord word = words.get(i);
            result[i] = new SuperLyricWord(word.text, word.begin, word.end);
        }
        return result;
    }

    private static long parseLong(@Nullable String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long toMs(@Nullable String minute, @Nullable String second, @Nullable String fraction) {
        long ms = 0L;
        if (fraction != null) {
            switch (fraction.length()) {
                case 1:
                    ms = parseLong(fraction) * 100L;
                    break;
                case 2:
                    ms = parseLong(fraction) * 10L;
                    break;
                case 3:
                    ms = parseLong(fraction);
                    break;
                default:
                    break;
            }
        }
        return parseLong(minute) * 60000L + parseLong(second) * 1000L + ms;
    }

    // ------------------------------ 数据模型 ------------------------------

    /**
     * 行级歌词数据模型：start / end / text / words[] / translation / roma。
     * <p>
     * 逐字原词多数歌曲缺失，此时 words 为 null（纯文本回退）。
     */
    public static final class LyricLineData {
        public final long start;
        public final long end;
        @NonNull
        public final String text;
        @Nullable
        public final SuperLyricWord[] words;
        @Nullable
        public final String translation;
        @Nullable
        public final String roma;

        public LyricLineData(
            long start,
            long end,
            @NonNull String text,
            @Nullable SuperLyricWord[] words,
            @Nullable String translation,
            @Nullable String roma
        ) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.words = words;
            this.translation = translation;
            this.roma = roma;
        }

        @NonNull
        @Override
        public String toString() {
            return "LyricLineData{start=" + start
                + ", end=" + end
                + ", text='" + text + '\''
                + ", words=" + (words != null ? words.length : 0)
                + ", translation='" + translation + '\''
                + ", roma='" + roma + '\''
                + '}';
        }
    }

    /**
     * 解析结果：接口 code、纯音乐标志与行列表。
     */
    public static final class LyricData {
        public final int code;
        public final boolean pureMusic;
        @Nullable
        public final List<LyricLineData> lines;

        public LyricData(int code, boolean pureMusic, @Nullable List<LyricLineData> lines) {
            this.code = code;
            this.pureMusic = pureMusic;
            this.lines = lines;
        }

        public boolean hasLyrics() {
            return lines != null && !lines.isEmpty();
        }
    }

    private static final class ParsedLine {
        final long begin;
        final long end;
        @NonNull
        final String text;
        @NonNull
        final List<ParsedWord> words;

        ParsedLine(long begin, long end, @NonNull String text, @NonNull List<ParsedWord> words) {
            this.begin = begin;
            this.end = end;
            this.text = text;
            this.words = words;
        }
    }

    private static final class ParsedWord {
        final long begin;
        final long end;
        @NonNull
        final String text;

        ParsedWord(long begin, long end, @NonNull String text) {
            this.begin = begin;
            this.end = end;
            this.text = text;
        }
    }

    private static final class LyricResponse {
        int code;
        @Nullable
        LyricContent lrc;
        @Nullable
        LyricContent tlyric;
        @Nullable
        LyricContent yrc;
        @Nullable
        LyricContent ytlrc;
        @Nullable
        LyricContent romalrc;
        boolean pureMusic;
    }

    private static final class LyricContent {
        int version;
        @Nullable
        String lyric;
    }
}
