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
package com.hchen.superlyric.engine.multisource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.parser.KrcDecoder;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 酷狗音乐开放接口歌词拉取数据源。
 * <p>
 * <b>技术原理：</b>
 * <ul>
 *   <li><b>开放免鉴权：</b>通过移动端公开检索与 KRC 接口获取逐字歌词，已稳定存续 15 年，无 Cookie/Token 校验，响应极快 (<50ms)。</li>
 *   <li><b>精确 Hash 直查：</b>若已知音轨的 {@code hash}，直接跳过检索直接查询 KRC 密钥并下载。</li>
 *   <li><b>模糊检索加权打分：</b>基于 {@link LyricScorer} 对返回候选进行歌名、歌手与高斯时长打分，杜绝翻唱误匹配。</li>
 *   <li><b>原生 KRC 解析：</b>下载 Base64 内容经 {@link KrcDecoder} 16 字节密钥异或与 ZLib 解压为标准逐字。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public final class KuGouLyricSource {
    private static final String TAG = "KuGouLyricSource";
    private static final String USER_AGENT = "KuGou2012-7093-Android601";

    private static final OkHttpClient sClient = new OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build();

    private KuGouLyricSource() {
    }

    public static class Candidate {
        public final String hash;
        public final String title;
        public final String artist;
        public final long durationMs;
        public final double score;

        public Candidate(String hash, String title, String artist, long durationMs, double score) {
            this.hash = hash;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.score = score;
        }
    }

    /**
     * 根据歌名、歌手及目标时长检索酷狗候选曲目并按打分降序排列。
     */
    @NonNull
    public static List<Candidate> searchCandidates(@NonNull String title,
                                                   @Nullable String artist,
                                                   long targetDurationMs) {
        String cleanTitle = LyricScorer.cleanTitle(title);
        if (cleanTitle.isEmpty()) {
            return Collections.emptyList();
        }

        String keyword = title;
        if (artist != null && !artist.trim().isEmpty()) {
            keyword = title + " " + artist;
        }

        try {
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8.name());
            String url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword="
                + encodedKeyword + "&page=1&pagesize=5";

            Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

            try (Response response = sClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return Collections.emptyList();
                }
                ResponseBody body = response.body();
                if (body == null) return Collections.emptyList();

                String jsonStr = body.string();
                JSONObject root = new JSONObject(jsonStr);
                JSONObject dataObj = root.optJSONObject("data");
                if (dataObj == null) return Collections.emptyList();

                JSONArray infoArr = dataObj.optJSONArray("info");
                if (infoArr == null || infoArr.length() == 0) return Collections.emptyList();

                List<Candidate> candidates = new ArrayList<>(infoArr.length());
                for (int i = 0; i < infoArr.length(); i++) {
                    JSONObject item = infoArr.optJSONObject(i);
                    if (item == null) continue;

                    String hash = item.optString("hash", "");
                    if (hash.isEmpty()) continue;

                    String songName = item.optString("songname", "");
                    String singerName = item.optString("singername", "");
                    long duration = item.optLong("duration", 0L) * 1000L;

                    double score = LyricScorer.scoreCandidate(
                        title, artist, targetDurationMs,
                        songName, singerName, duration,
                        true // 酷狗默认含逐字
                    );

                    if (score >= 60.0) {
                        candidates.add(new Candidate(hash, songName, singerName, duration, score));
                    }
                }

                Collections.sort(candidates, (c1, c2) -> Double.compare(c2.score, c1.score));
                return candidates;
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Search KuGou candidates failed: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 根据音频 Hash 与时长直接从酷狗下载并解析整首 KRC 歌词。
     */
    @Nullable
    public static SuperLyricData fetchLyricByHash(@NonNull String hash,
                                                  long durationMs,
                                                  @Nullable String songTitle,
                                                  @Nullable String songArtist) {
        try {
            // 1. 查询对应 Hash 的歌词候选 ID 与 accesskey
            String searchUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration="
                + durationMs + "&hash=" + hash;

            Request sRequest = new Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .build();

            String lyricId = null;
            String accessKey = null;

            try (Response response = sClient.newCall(sRequest).execute()) {
                if (!response.isSuccessful()) return null;
                ResponseBody body = response.body();
                if (body == null) return null;

                JSONObject root = new JSONObject(body.string());
                JSONArray candidates = root.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) {
                    return null;
                }

                JSONObject first = candidates.optJSONObject(0);
                if (first == null) return null;

                lyricId = first.optString("id", "");
                accessKey = first.optString("accesskey", "");
            }

            if (lyricId == null || lyricId.isEmpty() || accessKey == null || accessKey.isEmpty()) {
                return null;
            }

            // 2. 下载原始 Base64 KRC 密文
            String downloadUrl = "http://krcs.kugou.com/download?ver=1&client=mobi&id="
                + lyricId + "&accesskey=" + accessKey + "&fmt=krc&charset=utf8";

            Request dRequest = new Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", USER_AGENT)
                .build();

            String krcBase64 = null;
            try (Response response = sClient.newCall(dRequest).execute()) {
                if (!response.isSuccessful()) return null;
                ResponseBody body = response.body();
                if (body == null) return null;

                JSONObject root = new JSONObject(body.string());
                krcBase64 = root.optString("content", "");
            }

            if (krcBase64 == null || krcBase64.isEmpty()) {
                return null;
            }

            // 3. 解密解析 KRC
            SuperLyricLine[] lines = KrcDecoder.decodeFromBase64(krcBase64);
            if (lines == null || lines.length == 0) {
                return null;
            }

            SuperLyricData data = new SuperLyricData()
                .setTitle(songTitle != null ? songTitle : "")
                .setArtist(songArtist != null ? songArtist : "")
                .setLyricId(hash)
                .setDuration(durationMs)
                .setAllLyrics(lines);

            return LyricSanitizer.sanitizeData(data);
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Fetch KuGou lyric by hash failed: " + t.getMessage());
            return null;
        }
    }
}
