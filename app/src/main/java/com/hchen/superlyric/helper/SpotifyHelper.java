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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.hchen.hooktool.log.XposedLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Spotify 歌词数据链路辅助：会话头捕获 + 私有歌词接口拉取 + gson 解析 + 行数据规整。
 * <p>
 * 与 hook 解耦，对齐 {@link KuGouHelper} 等既有辅助模块范式。
 * <p>
 * 会话头由 Spotify.java 的 Headers hook 填充（会话级稳定，捕获一次复用）；
 * 磁盘缓存经由通用 {@link LyricCacheHelper}（provider = Spotify）落盘。
 * <p>
 * Inspired from tomakino/LyricProvider/spotify-music.
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
public final class SpotifyHelper {
    private static final String TAG = "SpotifyHelper";

    private static final String BASE_URL = "https://guc3-spclient.spotify.com/color-lyrics/v2/track/";

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

    private SpotifyHelper() {
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
     * 拉取指定音轨的歌词原始 JSON。
     *
     * @param id 音轨标识（已剥 spotify:track: 前缀）
     * @return 歌词接口原始 JSON 字符串
     * @throws NoFoundLyricException 接口 404（无歌词）
     * @throws IOException           网络错误 / 非成功状态码 / JSON 非法
     */
    @NonNull
    public static String fetchLyric(@NonNull String id) throws IOException {
        String url = BASE_URL + id
            + "?vocalRemoval=false&clientLanguage=" + Locale.getDefault().toLanguageTag()
            + "&preview=false";

        Request.Builder builder = new Request.Builder()
            .url(url)
            .get()
            .addHeader("accept", "application/json")
            .addHeader("app-platform", "WebPlayer");
        mHeaders.forEach(builder::addHeader);

        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";

            if (code == 404) {
                throw new NoFoundLyricException(id, "No lyric found for " + id);
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP error code: " + code + ", msg: " + response.message());
            }

            try {
                gson.fromJson(body, JsonObject.class);
            } catch (JsonSyntaxException e) {
                throw new IOException("Invalid JSON response for " + id + ": " + body, e);
            }
            return body;
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
            LyricResponse response = gson.fromJson(json, LyricResponse.class);
            if (response == null || response.lyrics == null || response.lyrics.lines == null) {
                XposedLog.logD(TAG, "No lyrics data in response");
                return null;
            }
            return normalizeLines(response.lyrics.lines);
        } catch (JsonSyntaxException e) {
            XposedLog.logE(TAG, "Failed to parse lyric json", e);
            return null;
        }
    }

    @NonNull
    private static List<SpotifyLine> normalizeLines(@NonNull List<LyricLine> lines) {
        List<SpotifyLine> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            LyricLine line = lines.get(i);
            if (line == null || line.words == null || line.words.trim().isEmpty()) {
                continue;
            }

            long end = line.endTimeMs;
            if (end == 0L) {
                if (i + 1 < lines.size() && lines.get(i + 1) != null) {
                    end = lines.get(i + 1).startTimeMs;
                } else {
                    end = line.startTimeMs + 5000L;
                }
            }

            result.add(new SpotifyLine(
                line.words,
                line.startTimeMs,
                end,
                line.transliteratedWords
            ));
        }
        return result;
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

        public SpotifyLine(@NonNull String text, long startTimeMs, long endTimeMs, @Nullable String transliteratedWords) {
            this.text = text;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.transliteratedWords = transliteratedWords;
        }

        @NonNull
        @Override
        public String toString() {
            return "SpotifyLine{start=" + startTimeMs
                + ", end=" + endTimeMs
                + ", text='" + text + '\''
                + ", transliteratedWords='" + transliteratedWords + '\''
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

    private static final class LyricResponse {
        @Nullable
        LyricsData lyrics;
    }

    private static final class LyricsData {
        @Nullable
        List<LyricLine> lines;
    }

    private static final class LyricLine {
        long startTimeMs;
        @Nullable
        String words;
        long endTimeMs;
        @Nullable
        String transliteratedWords;
    }
}
