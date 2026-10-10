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
import com.hchen.superlyric.parser.QrcDecoder;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * QQ 音乐官方接口数据源。
 * <p>
 * <b>技术原理：</b>
 * <ul>
 *   <li><b>songmid 精准直查 (100% 准确度)：</b>通过 {@code musicu.fcg} 直接以 {@code songMID} 查询 {@code GetPlayLyricInfo}，
 *       零模糊搜索开销，杜绝任何同名翻唱错配。</li>
 *   <li><b>双端备用通道：</b>首选移动轻量端 (u.y.qq.com)，失败时平滑降级至桌面端入口 (shu6.y.qq.com)。</li>
 *   <li><b>原生 QRC 3DES 解密：</b>获取的密文由 {@link QrcDecoder} 独立解密解压为 XML 结构。</li>
 *   <li><b>模糊多候选打分检索：</b>在缺失 MID 的第三方应用调用时，通过 Lite 搜索与 {@link LyricScorer} 打分排序。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public final class QQMusicLyricSource {
    private static final String TAG = "QQMusicLyricSource";

    private static final String URL_U_Y = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String URL_SHU6 = "https://shu6.y.qq.com/cgi-bin/musicu.fcg";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient sClient = new OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build();

    private QQMusicLyricSource() {
    }

    public static class Candidate {
        public final String mid;
        public final long id;
        public final String title;
        public final String artist;
        public final long durationMs;
        public final double score;

        public Candidate(String mid, long id, String title, String artist, long durationMs, double score) {
            this.mid = mid;
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.score = score;
        }
    }

    /**
     * 根据 QQ 音乐唯一的 songMID 与可选的 songID 精准拉取官方 QRC 歌词并解析。
     */
    @Nullable
    public static SuperLyricData fetchLyricByMid(@NonNull String songMid,
                                                 long songId,
                                                 @Nullable String songTitle,
                                                 @Nullable String songArtist) {
        if (songMid.trim().isEmpty() && songId <= 0) {
            return null;
        }

        String[] urls = new String[]{URL_U_Y, URL_SHU6};
        for (String url : urls) {
            try {
                JSONObject payload = new JSONObject();
                JSONObject comm = new JSONObject();
                comm.put("ct", "19");
                comm.put("cv", "1873");
                comm.put("uin", "0");
                payload.put("comm", comm);

                JSONObject req0 = new JSONObject();
                req0.put("module", "music.musichallSong.PlayLyricInfo");
                req0.put("method", "GetPlayLyricInfo");
                JSONObject param = new JSONObject();
                param.put("songMID", songMid.trim());
                if (songId > 0) {
                    param.put("songID", songId);
                }
                param.put("musicType", 0);
                param.put("qrc", 1);
                param.put("trans", 1);
                param.put("roma", 1);
                req0.put("param", param);
                payload.put("req_0", req0);

                RequestBody requestBody = RequestBody.create(payload.toString(), JSON_MEDIA_TYPE);
                Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) QQMusic/18.73")
                    .build();

                try (Response response = sClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) continue;
                    ResponseBody body = response.body();
                    if (body == null) continue;

                    JSONObject respJson = new JSONObject(body.string());
                    JSONObject req0Resp = respJson.optJSONObject("req_0");
                    if (req0Resp == null) continue;

                    JSONObject dataObj = req0Resp.optJSONObject("data");
                    if (dataObj == null) continue;

                    String lyricRaw = dataObj.optString("lyric", "");
                    if (lyricRaw.isEmpty()) continue;

                    SuperLyricLine[] lines = QrcDecoder.decodeFromHexOrBase64(lyricRaw);
                    if (lines == null || lines.length == 0) continue;

                    // 若独立包含翻译字段且当前行缺失翻译，尝试合并
                    String transRaw = dataObj.optString("trans", "");
                    if (!transRaw.isEmpty()) {
                        SuperLyricLine[] transLines = QrcDecoder.decodeFromHexOrBase64(transRaw);
                        if (transLines != null && transLines.length > 0) {
                            mergeTranslations(lines, transLines);
                        }
                    }

                    long duration = lines[lines.length - 1].getEndTime();
                    SuperLyricData data = new SuperLyricData()
                        .setTitle(songTitle != null ? songTitle : "")
                        .setArtist(songArtist != null ? songArtist : "")
                        .setLyricId(songMid)
                        .setDuration(duration)
                        .setAllLyrics(lines);

                    return LyricSanitizer.sanitizeData(data);
                }
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "fetchLyricByMid (" + url + ") failed: " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * 跨应用模糊检索 QQ 音乐候选曲目列表并按打分降序排列。
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
            JSONObject payload = new JSONObject();
            JSONObject comm = new JSONObject();
            comm.put("ct", "11");
            comm.put("cv", "1003006");
            comm.put("v", "1003006");
            comm.put("tmeAppID", "qqmusiclight");
            comm.put("nettype", "NETWORK_WIFI");
            payload.put("comm", comm);

            JSONObject req0 = new JSONObject();
            req0.put("method", "DoSearchForQQMusicLite");
            req0.put("module", "music.search.SearchCgiService");
            JSONObject param = new JSONObject();
            param.put("query", keyword);
            param.put("search_type", 0);
            param.put("num_per_page", 5);
            param.put("page_num", 1);
            param.put("grp", 1);
            req0.put("param", param);
            payload.put("req_0", req0);

            RequestBody requestBody = RequestBody.create(payload.toString(), JSON_MEDIA_TYPE);
            Request request = new Request.Builder()
                .url(URL_U_Y)
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build();

            try (Response response = sClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return Collections.emptyList();
                ResponseBody body = response.body();
                if (body == null) return Collections.emptyList();

                JSONObject root = new JSONObject(body.string());
                JSONObject req0Resp = root.optJSONObject("req_0");
                if (req0Resp == null) return Collections.emptyList();

                JSONObject dataObj = req0Resp.optJSONObject("data");
                if (dataObj == null) return Collections.emptyList();

                JSONObject bodyObj = dataObj.optJSONObject("body");
                if (bodyObj == null) return Collections.emptyList();

                JSONArray songsArr = bodyObj.optJSONArray("item_song");
                if (songsArr == null) {
                    JSONObject songObj = bodyObj.optJSONObject("song");
                    if (songObj != null) {
                        songsArr = songObj.optJSONArray("list");
                    }
                }
                if (songsArr == null || songsArr.length() == 0) return Collections.emptyList();

                List<Candidate> candidates = new ArrayList<>(songsArr.length());
                for (int i = 0; i < songsArr.length(); i++) {
                    JSONObject item = songsArr.optJSONObject(i);
                    if (item == null) continue;

                    String mid = item.optString("mid", item.optString("songmid", ""));
                    if (mid.isEmpty()) continue;
                    long id = item.optLong("id", item.optLong("songid", 0L));

                    String songName = item.optString("title", item.optString("songname", ""));
                    StringBuilder singerSb = new StringBuilder();
                    JSONArray singerArr = item.optJSONArray("singer");
                    if (singerArr != null) {
                        for (int s = 0; s < singerArr.length(); s++) {
                            JSONObject sObj = singerArr.optJSONObject(s);
                            if (sObj != null) {
                                String sName = sObj.optString("name", "");
                                if (!sName.isEmpty()) {
                                    if (singerSb.length() > 0) singerSb.append("/");
                                    singerSb.append(sName);
                                }
                            }
                        }
                    }
                    String singer = singerSb.toString();
                    long duration = item.optLong("interval", 0L) * 1000L;

                    double score = LyricScorer.scoreCandidate(
                        title, artist, targetDurationMs,
                        songName, singer, duration,
                        true
                    );

                    if (score >= 60.0) {
                        candidates.add(new Candidate(mid, id, songName, singer, duration, score));
                    }
                }

                Collections.sort(candidates, (c1, c2) -> Double.compare(c2.score, c1.score));
                return candidates;
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Search QQMusic candidates failed: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    private static void mergeTranslations(@NonNull SuperLyricLine[] mainLines, @NonNull SuperLyricLine[] transLines) {
        int tIdx = 0;
        for (SuperLyricLine mLine : mainLines) {
            if (mLine.hasTranslation()) continue;
            while (tIdx < transLines.length && transLines[tIdx].getEndTime() <= mLine.getStartTime()) {
                tIdx++;
            }
            if (tIdx < transLines.length) {
                SuperLyricLine tLine = transLines[tIdx];
                if (Math.abs(tLine.getStartTime() - mLine.getStartTime()) <= 1000L) {
                    mLine.setTranslation(tLine.getText());
                    tIdx++;
                }
            }
        }
    }
}
