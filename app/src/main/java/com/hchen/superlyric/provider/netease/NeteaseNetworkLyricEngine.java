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
package com.hchen.superlyric.provider.netease;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.publisher.AbsPublisher;
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricCacheStore;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 内置网易云音乐主动网络拉取引擎。
 * <p>
 * 提供官方接口拉取（YRC 逐字 / LRC 逐行 / 双语翻译 / 罗马音）、
 * 本地音乐加权检索与候选打分、双层缓存（内存 LRU + 磁盘私有缓存）等能力。
 *
 * @author 焕晨HChen
 */
public class NeteaseNetworkLyricEngine implements INetworkLyricEngine {
    private static final String ENGINE_TAG = "NeteaseNetEngine";
    private static final String PROVIDER_NAME = "Netease";
    private static final String DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; U; Android 14; zh-cn; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36 NeteaseMusic/9.6.05";

    private static final Pattern YRC_LINE_PATTERN = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)");
    private static final Pattern YRC_WORD_TAG_PATTERN = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)");
    private static final Pattern LRC_TIME_TAG_PATTERN = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
    private static final Pattern OFFSET_PATTERN = Pattern.compile("^\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("(\\d{5,12})");

    // 本地歌曲名称清洗规则：音轨序号 (01. 02 -)、伴奏、Live、Remix、现场、翻唱、文件扩展名及 feat.
    private static final Pattern CLEAN_TRACK_NO_PATTERN = Pattern.compile(
        "^\\s*(?:track\\s*)?\\d{1,3}\\s*[-._\\s]\\s*",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLEAN_BRACKETS_PATTERN = Pattern.compile(
        "\\s*[\\[\\(（【].*?(?:伴奏|Live|remix|Remix|现场|纯音乐|Instrumental|Cover|翻唱|官方|Remaster|重制|原声|ver\\.|Version).*?[\\]\\)）】]",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLEAN_FEAT_PATTERN = Pattern.compile(
        "\\s*(?:feat\\.|ft\\.|featuring).*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLEAN_EXT_PATTERN = Pattern.compile(
        "\\.(mp3|flac|wav|m4a|aac|ogg|ape)$",
        Pattern.CASE_INSENSITIVE
    );

    private final Map<String, SuperLyricData> mLyricCache;

    private ExecutorService mExecutor;
    private final AtomicReference<HttpURLConnection> mActiveConnection = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<SuperLyricData>> mActiveFuture = new AtomicReference<>();

    public NeteaseNetworkLyricEngine(@NonNull Map<String, SuperLyricData> lyricCache) {
        this.mLyricCache = lyricCache;
    }

    public NeteaseNetworkLyricEngine() {
        this(Collections.synchronizedMap(
            new LinkedHashMap<String, SuperLyricData>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SuperLyricData> eldest) {
                    return size() > 100;
                }
            }
        ));
    }

    @Override
    public void initEngine() {
        // 有界线程池：core=2, max=6, 60s 回收，CallerRunsPolicy 饱和防崩溃
        mExecutor = new ThreadPoolExecutor(
            2,
            6,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "SuperLyric-NeteaseNet");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        AndroidLog.logI(ENGINE_TAG, "Netease proactive network engine initialized (bounded pool 2-6)");
    }

    @Override
    public void onTrackChanged(@NonNull TrackContext context) {
        AndroidLog.logI(ENGINE_TAG, "onTrackChanged: trackId=" + context.getTrackId()
            + ", title=" + context.getTitle()
            + ", artist=" + context.getArtist()
            + ", duration=" + context.getDuration());
    }

    @Override
    public void cancel() {
        HttpURLConnection conn = mActiveConnection.getAndSet(null);
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
        CompletableFuture<SuperLyricData> future = mActiveFuture.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    @NonNull
    @Override
    public CompletableFuture<SuperLyricData> fetchFullLyric(@NonNull TrackContext context) {
        cancel();
        AndroidLog.logI(ENGINE_TAG, "fetchFullLyric: [START] trackId=" + context.getTrackId()
            + ", title=" + context.getTitle()
            + ", artist=" + context.getArtist()
            + ", duration=" + context.getDuration());

        // 1. 优先检查内存 LRU 缓存
        SuperLyricData cached = mLyricCache.get(context.getTrackId());
        if (cached != null && cached.hasAllLyrics()) {
            AndroidLog.logI(ENGINE_TAG, "fetchFullLyric: [CACHE_MEM_HIT] Found in memory LRU cache for: " + context.getTrackId());
            return CompletableFuture.completedFuture(cached);
        }
        AndroidLog.logD(ENGINE_TAG, "fetchFullLyric: Memory LRU cache missed for: " + context.getTrackId());

        CompletableFuture<SuperLyricData> future = new CompletableFuture<>();
        mActiveFuture.set(future);

        if (mExecutor == null || mExecutor.isShutdown()) {
            initEngine();
        }

        AndroidLog.logD(ENGINE_TAG, "fetchFullLyric: Submitting doFetch to executor pool");
        mExecutor.execute(() -> {
            try {
                SuperLyricData data = doFetch(context, future);
                if (data != null && data.hasAllLyrics()) {
                    mLyricCache.put(context.getTrackId(), data);
                    AndroidLog.logI(ENGINE_TAG, "fetchFullLyric: [COMPLETE] Successfully fetched and cached in memory for: " + context.getTrackId());
                    future.complete(data);
                } else {
                    AndroidLog.logW(ENGINE_TAG, "fetchFullLyric: [COMPLETE] Result empty or incomplete for: " + context.getTrackId());
                    future.complete(null);
                }
            } catch (Throwable t) {
                AndroidLog.logW(ENGINE_TAG, "fetchFullLyric: [FAILED] Network fetch error for " + context.getTrackId() + ": " + t.getMessage(), t);
                future.completeExceptionally(t);
            } finally {
                mActiveConnection.set(null);
            }
        });

        return future;
    }

    @Nullable
    private SuperLyricData doFetch(@NonNull TrackContext context, @NonNull CompletableFuture<SuperLyricData> curFuture) {
        if (isCancelled(curFuture)) {
            AndroidLog.logD(ENGINE_TAG, "doFetch: Cancelled before start for: " + context.getTrackId());
            return null;
        }

        long musicId = parseNumericId(context.getTrackId());
        if (musicId <= 0L && !context.getTitle().isEmpty()) {
            // 本地曲目或未知 trackId：先智能检索并加权匹配官方最佳 musicId
            AndroidLog.logI(ENGINE_TAG, "doFetch: trackId non-numeric (" + context.getTrackId() + "), searching musicId for: " + context.getTitle());
            musicId = searchMusicId(context.getTitle(), context.getArtist(), context.getDuration());
            AndroidLog.logI(ENGINE_TAG, "doFetch: Search resolved musicId=" + musicId + " for: " + context.getTitle());
        } else {
            AndroidLog.logI(ENGINE_TAG, "doFetch: Parsed numeric musicId=" + musicId + " from trackId=" + context.getTrackId());
        }

        if (isCancelled(curFuture)) return null;

        if (musicId <= 0L) {
            AndroidLog.logW(ENGINE_TAG, "doFetch: Cannot determine valid NetEase musicId for track: " + context.getTrackId());
            return null;
        }

        // 2. 检查本地离线磁盘二级缓存 (Disk Cache)
        AndroidLog.logI(ENGINE_TAG, "doFetch: [CACHE_DISK_CHECK] Checking disk cache for musicId=" + musicId);
        JSONObject diskJson = readDiskCache(musicId);
        if (diskJson != null) {
            SuperLyricData diskData = parseResponse(musicId, diskJson, context);
            if (diskData != null && diskData.hasAllLyrics()) {
                AndroidLog.logI(ENGINE_TAG, "doFetch: [CACHE_DISK_HIT] Hit offline disk cache for musicId=" + musicId + " (" + context.getTitle() + ")");
                return diskData;
            }
        }
        AndroidLog.logI(ENGINE_TAG, "doFetch: [CACHE_DISK_MISS] Disk cache missed for musicId=" + musicId + ", proceeding to network fetch");

        if (isCancelled(curFuture)) return null;

        AndroidLog.logI(ENGINE_TAG, "doFetch: [NETWORK_START] Actively fetching from official API: musicId=" + musicId
            + ", title=" + context.getTitle());

        // 3. 联网拉取首选接口 (带 YRC 逐字和 YTLRC 翻译，HTTPS + Keep-Alive + GZIP + 弱网重试)
        JSONObject root = fetchInterface3(musicId, curFuture);
        if (isCancelled(curFuture)) return null;

        if (root == null || root.optInt("code", 0) != 200) {
            AndroidLog.logW(ENGINE_TAG, "doFetch: Primary API (interface3) failed or invalid code, falling back to secondary standard API for " + musicId);
            root = fetchStandardApi(musicId, curFuture);
        }

        if (isCancelled(curFuture)) return null;

        if (root == null) {
            AndroidLog.logW(ENGINE_TAG, "doFetch: All network lyric APIs failed for musicId: " + musicId);
            return null;
        }

        return parseResponse(musicId, root, context);
    }

    private boolean isCancelled(@NonNull CompletableFuture<?> future) {
        return future.isCancelled() || mActiveFuture.get() != future;
    }

    /**
     * 透明从宿主 CookieManager 中读取当前用户的真实登录凭证（如存在），解锁 VIP 完整逐字歌词。
     */
    @Nullable
    private static String getHostCookie() {
        try {
            android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
            if (cookieManager != null) {
                String cookie = cookieManager.getCookie("https://music.163.com");
                if (cookie != null && !cookie.trim().isEmpty()) {
                    return cookie.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void applyStandardHeaders(@NonNull HttpURLConnection conn) {
        conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
        conn.setRequestProperty("Connection", "Keep-Alive");

        String cookie = getHostCookie();
        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
        }
    }

    @Nullable
    private JSONObject fetchInterface3(long musicId, @NonNull CompletableFuture<SuperLyricData> curFuture) {
        return executeWithRetry(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://interface3.music.163.com/api/song/lyric/v1");
                conn = (HttpURLConnection) url.openConnection();
                mActiveConnection.set(conn);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);
                conn.setDoInput(true);
                applyStandardHeaders(conn);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                String postData = "id=" + musicId + "&cp=false&tv=0&lv=0&rv=0&kv=0&yv=0&ytv=0&yrv=0";
                byte[] postBytes = postData.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(postBytes.length));

                AndroidLog.logI(ENGINE_TAG, "[Net-Primary] Connecting to interface3 for musicId=" + musicId);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                AndroidLog.logI(ENGINE_TAG, "[Net-Primary] HTTP code=" + code + " for musicId=" + musicId);
                if (code == 200) {
                    String responseStr = readStream(conn);
                    AndroidLog.logI(ENGINE_TAG, "[Net-Primary] Succeeded for musicId=" + musicId + " (payload length=" + responseStr.length() + ")");
                    writeDiskCacheAsync(mExecutor, musicId, responseStr);
                    return new JSONObject(responseStr);
                } else {
                    AndroidLog.logW(ENGINE_TAG, "[Net-Primary] Failed with HTTP " + code + " for musicId=" + musicId);
                }
            } finally {
                mActiveConnection.compareAndSet(conn, null);
            }
            return null;
        }, "interface3", curFuture);
    }

    @Nullable
    private JSONObject fetchStandardApi(long musicId, @NonNull CompletableFuture<SuperLyricData> curFuture) {
        return executeWithRetry(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://music.163.com/api/song/lyric?id=" + musicId + "&lv=1&kv=1&tv=-1&rv=1");
                conn = (HttpURLConnection) url.openConnection();
                mActiveConnection.set(conn);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                applyStandardHeaders(conn);

                AndroidLog.logI(ENGINE_TAG, "[Net-Fallback] Connecting to standard lyric API for musicId=" + musicId);
                int code = conn.getResponseCode();
                AndroidLog.logI(ENGINE_TAG, "[Net-Fallback] HTTP code=" + code + " for musicId=" + musicId);
                if (code == 200) {
                    String responseStr = readStream(conn);
                    AndroidLog.logI(ENGINE_TAG, "[Net-Fallback] Succeeded for musicId=" + musicId + " (payload length=" + responseStr.length() + ")");
                    writeDiskCacheAsync(mExecutor, musicId, responseStr);
                    return new JSONObject(responseStr);
                } else {
                    AndroidLog.logW(ENGINE_TAG, "[Net-Fallback] Failed with HTTP " + code + " for musicId=" + musicId);
                }
            } finally {
                mActiveConnection.compareAndSet(conn, null);
            }
            return null;
        }, "standardApi", curFuture);
    }

    private long searchMusicId(@NonNull String title, @NonNull String artist, long targetDurationMs) {
        // 1. 原样检索
        long id = searchMusicIdInternal(title, artist, targetDurationMs);
        if (id > 0L) return id;

        // 2. 剥离伴奏/Live/Remix/序号/扩展名等后缀降级检索
        String cleanTitle = cleanSearchKeyword(title);
        if (!cleanTitle.isEmpty() && !cleanTitle.equalsIgnoreCase(title)) {
            AndroidLog.logI(ENGINE_TAG, "[Search] Retrying search with sanitized title: [" + cleanTitle + "]");
            id = searchMusicIdInternal(cleanTitle, artist, targetDurationMs);
            if (id > 0L) return id;
        }

        // 3. 多歌手场景拆解主歌手降级检索
        String mainArtist = extractMainArtist(artist);
        if (!mainArtist.isEmpty() && !mainArtist.equalsIgnoreCase(artist)) {
            String targetTitle = !cleanTitle.isEmpty() ? cleanTitle : title;
            AndroidLog.logI(ENGINE_TAG, "[Search] Retrying search with main artist: [" + targetTitle + " / " + mainArtist + "]");
            id = searchMusicIdInternal(targetTitle, mainArtist, targetDurationMs);
            if (id > 0L) return id;
        }

        return 0L;
    }

    private long searchMusicIdInternal(@NonNull String title, @NonNull String artist, long targetDurationMs) {
        HttpURLConnection conn = null;
        try {
            String query = (title + " " + artist).trim();
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            // 多候选拉取 (limit=5)，提供智能时长与歌手综合打分
            URL url = new URL("https://music.163.com/api/search/get?s=" + encoded + "&type=1&limit=5");
            conn = (HttpURLConnection) url.openConnection();
            mActiveConnection.set(conn);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            applyStandardHeaders(conn);

            AndroidLog.logI(ENGINE_TAG, "[Search] Requesting query: '" + query + "'");
            int code = conn.getResponseCode();
            AndroidLog.logI(ENGINE_TAG, "[Search] HTTP code=" + code + " for query: '" + query + "'");
            if (code == 200) {
                String resStr = readStream(conn);
                JSONObject json = new JSONObject(resStr);
                JSONObject result = json.optJSONObject("result");
                if (result != null) {
                    JSONArray songs = result.optJSONArray("songs");
                    if (songs != null && songs.length() > 0) {
                        long bestId = selectBestMatchSongId(songs, title, artist, targetDurationMs);
                        AndroidLog.logI(ENGINE_TAG, "[Search] Found " + songs.length() + " candidates, best match musicId=" + bestId);
                        return bestId;
                    }
                }
            }
        } catch (Throwable t) {
            AndroidLog.logW(ENGINE_TAG, "[Search] Failed for '" + title + " " + artist + "': " + t.getMessage());
        } finally {
            mActiveConnection.compareAndSet(conn, null);
        }
        return 0L;
    }

    /**
     * 候选歌曲轻量模型。
     */
    static final class CandidateSong {
        final long id;
        final String name;
        final String artists;
        final long durationMs;

        CandidateSong(long id, @NonNull String name, @NonNull String artists, long durationMs) {
            this.id = id;
            this.name = name;
            this.artists = artists;
            this.durationMs = durationMs;
        }
    }

    /**
     * 多候选歌曲智能加权打分：
     * 综合比对歌手名、歌曲时长差值 (dt) 与标题匹配度，杜绝伴奏/片段/翻唱误匹配。
     */
    static long selectBestMatchSongId(@NonNull JSONArray songs, @NonNull String queryTitle,
                                      @NonNull String queryArtist, long targetDurationMs) {
        List<CandidateSong> candidates = new ArrayList<>();
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            long id = song.optLong("id", 0L);
            if (id <= 0L) continue;

            String songName = song.optString("name", "");
            long songDt = song.optLong("dt", 0L);

            StringBuilder artistNames = new StringBuilder();
            JSONArray artistsArr = song.optJSONArray("artists");
            if (artistsArr != null) {
                for (int a = 0; a < artistsArr.length(); a++) {
                    JSONObject ar = artistsArr.optJSONObject(a);
                    if (ar != null) {
                        artistNames.append(ar.optString("name", "")).append(" ");
                    }
                }
            }
            candidates.add(new CandidateSong(id, songName, artistNames.toString(), songDt));
        }
        return calculateBestCandidate(candidates, queryTitle, queryArtist, targetDurationMs);
    }

    static long calculateBestCandidate(@NonNull List<CandidateSong> candidates, @NonNull String queryTitle,
                                       @NonNull String queryArtist, long targetDurationMs) {
        long bestId = 0L;
        int bestScore = Integer.MIN_VALUE;

        String lowerTitle = queryTitle.toLowerCase().trim();
        String lowerArtist = queryArtist.toLowerCase().trim();

        for (int i = 0; i < candidates.size(); i++) {
            CandidateSong song = candidates.get(i);
            String songName = song.name.toLowerCase().trim();
            String songArtists = song.artists.toLowerCase().trim();
            long songDt = song.durationMs;

            int score = 0;

            // 1. 标题匹配
            if (songName.equals(lowerTitle)) {
                score += 40;
            } else if (songName.contains(lowerTitle) || lowerTitle.contains(songName)) {
                score += 25;
            }

            // 2. 歌手匹配
            if (!lowerArtist.isEmpty()) {
                if (songArtists.contains(lowerArtist) || lowerArtist.contains(songArtists)) {
                    score += 35;
                }
            }

            // 3. 时长偏差打分（核心过滤：避免片段/加长版/错曲）
            if (targetDurationMs > 0L && songDt > 0L) {
                long diff = Math.abs(targetDurationMs - songDt);
                if (diff <= 2000L) {
                    score += 50;
                } else if (diff <= 5000L) {
                    score += 30;
                } else if (diff <= 10000L) {
                    score += 10;
                } else if (diff > 20000L) {
                    score -= 40;
                }
            }

            // 4. 原顺序微弱衰减
            score -= (i * 2);

            if (score > bestScore) {
                bestScore = score;
                bestId = song.id;
            }
        }

        if (bestId > 0L) {
            return bestId;
        }
        return !candidates.isEmpty() ? candidates.get(0).id : 0L;
    }

    @FunctionalInterface
    private interface NetworkSupplier<T> {
        T get() throws Exception;
    }

    /**
     * 弱网自愈重试：针对偶发超时进行 1 次 300ms 退避重试，同时支持快速中断响应。
     */
    @Nullable
    private <T> T executeWithRetry(@NonNull NetworkSupplier<T> supplier, @NonNull String apiTag,
                                   @NonNull CompletableFuture<SuperLyricData> curFuture) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (isCancelled(curFuture)) {
                return null;
            }
            try {
                return supplier.get();
            } catch (Throwable t) {
                AndroidLog.logD(ENGINE_TAG, apiTag + " attempt " + attempt + " failed: " + t.getMessage());
                if (attempt == 1) {
                    if (isCancelled(curFuture)) {
                        return null;
                    }
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private SuperLyricData parseResponse(long musicId, @NonNull JSONObject root, @NonNull TrackContext context) {
        try {
            boolean isPureMusic = root.optBoolean("pureMusic", false)
                || root.optBoolean("nolyric", false);

            String yrcStr = extractLyricText(root, "yrc");
            String ytlrcStr = extractLyricText(root, "ytlrc");
            String yromalrcStr = extractLyricText(root, "yromalrc");
            String lrcStr = extractLyricText(root, "lrc");
            String tlyricStr = extractLyricText(root, "tlyric");
            String romalrcStr = extractLyricText(root, "romalrc");

            if (!isPureMusic && (lrcStr != null && lrcStr.contains("纯音乐，请欣赏"))) {
                isPureMusic = true;
            }

            if (isPureMusic) {
                long duration = context.getDuration() > 0 ? context.getDuration() : 3600000L;
                long lineDuration = Math.max(duration, 3600000L); // 纯音乐 1 小时保活保护
                SuperLyricLine pureLine = new SuperLyricLine("纯音乐，请欣赏", 0, lineDuration);
                SuperLyricData data = new SuperLyricData();
                data.setTitle(context.getTitle());
                data.setArtist(context.getArtist());
                data.setAlbum(context.getAlbum());
                data.setLyricId(String.valueOf(musicId));
                data.setDuration(duration);
                data.setAllLyrics(new SuperLyricLine[]{pureLine});
                return LyricSanitizer.sanitizeData(data);
            }

            // 翻译与罗马音分离解析
            // 翻译：优先 ytlrc，次选 tlyric
            Map<Long, String> transMap = parseLrcToMap(ytlrcStr != null && !ytlrcStr.isEmpty() ? ytlrcStr : tlyricStr);
            // 罗马音：优先 yromalrc，次选 romalrc
            Map<Long, String> romaMap = parseLrcToMap(yromalrcStr != null && !yromalrcStr.isEmpty() ? yromalrcStr : romalrcStr);

            // 若无翻译仅有罗马音，降级将罗马音填入翻译槽
            if (transMap.isEmpty() && !romaMap.isEmpty()) {
                transMap = romaMap;
                romaMap = Collections.emptyMap();
            }

            List<SuperLyricLine> lineList = new ArrayList<>();
            boolean hasWords = false;

            // 优先解析高精度逐字 YRC
            if (yrcStr != null && !yrcStr.trim().isEmpty()) {
                lineList = parseYrc(yrcStr, transMap, romaMap);
                for (SuperLyricLine line : lineList) {
                    if (line.getWords() != null && line.getWords().length > 0) {
                        hasWords = true;
                        break;
                    }
                }
            }

            // 缺失 YRC 时降级解析标准 LRC
            if (lineList.isEmpty() && lrcStr != null && !lrcStr.trim().isEmpty()) {
                lineList = parseLrc(lrcStr, transMap, romaMap);
            }

            if (lineList.isEmpty()) {
                AndroidLog.logW(ENGINE_TAG, "No valid lyric lines extracted for musicId: " + musicId);
                return null;
            }

            long trackDuration = context.getDuration();
            if (trackDuration <= 0L && !lineList.isEmpty()) {
                trackDuration = lineList.get(lineList.size() - 1).getEndTime();
            }

            SuperLyricData fullData = new SuperLyricData();
            fullData.setTitle(context.getTitle());
            fullData.setArtist(context.getArtist());
            fullData.setAlbum(context.getAlbum());
            fullData.setLyricId(String.valueOf(musicId));
            fullData.setDuration(trackDuration);
            fullData.setAllLyrics(lineList.toArray(new SuperLyricLine[0]));

            SuperLyricData cleanData = LyricSanitizer.sanitizeData(fullData);
            AndroidLog.logI(ENGINE_TAG, "Successfully parsed proactive NetEase lyric: musicId=" + musicId
                + ", lines=" + lineList.size()
                + ", hasWords=" + hasWords
                + ", hasTrans=" + !transMap.isEmpty()
                + ", hasRoma=" + !romaMap.isEmpty());
            return cleanData;
        } catch (Throwable t) {
            AndroidLog.logE(ENGINE_TAG, "Failed to parse NetEase lyric response for " + musicId, t);
            return null;
        }
    }

    @NonNull
    List<SuperLyricLine> parseYrc(@NonNull String yrcStr, @NonNull Map<Long, String> transMap,
                                  @NonNull Map<Long, String> romaMap) {
        List<SuperLyricLine> lines = new ArrayList<>();
        String[] rawLines = yrcStr.split("\n");

        long offsetMs = 0L;

        for (String rawLine : rawLines) {
            if (rawLine == null) continue;
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) continue;

            // 全局时间偏移标签：[offset:500]
            Matcher offsetMatcher = OFFSET_PATTERN.matcher(trimmed);
            if (offsetMatcher.matches()) {
                try {
                    offsetMs = Long.parseLong(offsetMatcher.group(1));
                } catch (Throwable ignored) {
                }
                continue;
            }

            // 创作者元数据行：{"t":0,"c":[{"tx":"作词: "},{"tx":"李荣浩"}]}
            if (trimmed.startsWith("{")) {
                SuperLyricLine metaLine = parseJsonMetaLine(trimmed);
                if (metaLine != null) {
                    SuperLyricLine sanitized = LyricSanitizer.sanitizeLine(metaLine);
                    if (sanitized != null) lines.add(sanitized);
                }
                continue;
            }

            // YRC 标准行：[startMs,durationMs](wStart,wDur,flag)Word...
            Matcher lineMatcher = YRC_LINE_PATTERN.matcher(trimmed);
            if (!lineMatcher.matches()) continue;

            long rawLineStart = Long.parseLong(lineMatcher.group(1));
            long rawLineDur = Long.parseLong(lineMatcher.group(2));
            long lineStart = Math.max(0L, rawLineStart + offsetMs);
            long lineEnd = lineStart + Math.max(rawLineDur, 500L);
            String remainder = lineMatcher.group(3);

            // 提取所有 (wStart,wDur,flag) 词元标签，文本截取支持字面括号 (Yeah) 等特殊字符与 HTML 实体解码
            Matcher tagMatcher = YRC_WORD_TAG_PATTERN.matcher(remainder);
            List<WordTag> tags = new ArrayList<>();
            while (tagMatcher.find()) {
                long wStart = Long.parseLong(tagMatcher.group(1));
                long wDur = Long.parseLong(tagMatcher.group(2));
                tags.add(new WordTag(wStart, wDur, tagMatcher.start(), tagMatcher.end()));
            }

            List<SuperLyricWord> words = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            long lastWordEnd = lineStart;

            for (int i = 0; i < tags.size(); i++) {
                WordTag curTag = tags.get(i);
                int textStart = curTag.endPos;
                int textEnd = (i + 1 < tags.size()) ? tags.get(i + 1).startPos : remainder.length();
                String rawText = remainder.substring(textStart, textEnd);
                String wText = decodeHtmlEntities(rawText);

                if (!wText.isEmpty()) {
                    long wStart = Math.max(0L, curTag.startMs + offsetMs);
                    long wEnd = wStart + curTag.durMs;

                    // 词元严格单调递增防护：防止时间戳倒流导致进度抖动
                    if (wStart < lastWordEnd) {
                        wStart = lastWordEnd;
                        wEnd = Math.max(wStart, wEnd);
                    }
                    if (wEnd < wStart) {
                        wEnd = wStart;
                    }

                    words.add(new SuperLyricWord(wText, wStart, wEnd));
                    fullText.append(wText);
                    lastWordEnd = wEnd;
                }
            }

            // 严密对齐：行文本严禁单方面 trim()，必须与 words 累加字符严格一致，避免触发 Sanitizer 长度越界判定
            String content = fullText.toString();
            if (content.trim().isEmpty()) continue;

            // 确保行结束时间不早于最后一个词元的结束时间
            if (!words.isEmpty()) {
                lineEnd = Math.max(lineEnd, words.get(words.size() - 1).getEndTime());
            }

            String trans = findClosestTranslation(transMap, lineStart, 350L);
            String roma = findClosestTranslation(romaMap, lineStart, 350L);

            SuperLyricWord[] wordsArr = words.isEmpty() ? null : words.toArray(new SuperLyricWord[0]);
            SuperLyricLine line = new SuperLyricLine(content, wordsArr, trans, roma, lineStart, lineEnd);
            SuperLyricLine sanitized = LyricSanitizer.sanitizeLine(line);
            if (sanitized != null) {
                lines.add(sanitized);
            }
        }
        return lines;
    }

    @NonNull
    List<SuperLyricLine> parseLrc(@NonNull String lrcStr, @NonNull Map<Long, String> transMap,
                                  @NonNull Map<Long, String> romaMap) {
        List<SuperLyricLine> lines = new ArrayList<>();
        String[] rawLines = lrcStr.split("\n");

        long offsetMs = 0L;
        List<TempLine> tempLines = new ArrayList<>();

        for (String rawLine : rawLines) {
            if (rawLine == null) continue;
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) continue;

            // 全局时间偏移标签：[offset:500]
            Matcher offsetMatcher = OFFSET_PATTERN.matcher(trimmed);
            if (offsetMatcher.matches()) {
                try {
                    offsetMs = Long.parseLong(offsetMatcher.group(1));
                } catch (Throwable ignored) {
                }
                continue;
            }

            if (trimmed.startsWith("{")) {
                SuperLyricLine metaLine = parseJsonMetaLine(trimmed);
                if (metaLine != null) {
                    SuperLyricLine sanitized = LyricSanitizer.sanitizeLine(metaLine);
                    if (sanitized != null) lines.add(sanitized);
                }
                continue;
            }

            // 循环匹配前导复合时间戳：[01:23.45][02:34.56]歌词文本
            Matcher matcher = LRC_TIME_TAG_PATTERN.matcher(trimmed);
            List<Long> timeStamps = new ArrayList<>();
            int lastEnd = 0;
            while (matcher.find()) {
                if (matcher.start() == lastEnd) {
                    long startMs = parseTimeTag(matcher.group(1), matcher.group(2));
                    timeStamps.add(Math.max(0L, startMs + offsetMs));
                    lastEnd = matcher.end();
                } else {
                    break;
                }
            }

            if (!timeStamps.isEmpty()) {
                String rawContent = trimmed.substring(lastEnd).trim();
                String content = decodeHtmlEntities(rawContent);
                if (!content.isEmpty()) {
                    for (long timeMs : timeStamps) {
                        tempLines.add(new TempLine(content, timeMs));
                    }
                }
            }
        }

        // 按时间戳递增排序
        tempLines.sort((a, b) -> Long.compare(a.startMs, b.startMs));

        for (int i = 0; i < tempLines.size(); i++) {
            TempLine cur = tempLines.get(i);
            long endMs;
            if (i + 1 < tempLines.size() && tempLines.get(i + 1).startMs > cur.startMs) {
                endMs = tempLines.get(i + 1).startMs;
            } else {
                endMs = cur.startMs + 3000L;
            }

            String trans = findClosestTranslation(transMap, cur.startMs, 350L);
            String roma = findClosestTranslation(romaMap, cur.startMs, 350L);
            SuperLyricLine line = new SuperLyricLine(cur.content, null, trans, roma, cur.startMs, endMs);
            SuperLyricLine sanitized = LyricSanitizer.sanitizeLine(line);
            if (sanitized != null) {
                lines.add(sanitized);
            }
        }

        return lines;
    }

    @Nullable
    private SuperLyricLine parseJsonMetaLine(@NonNull String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            long t = obj.optLong("t", 0L);
            JSONArray cList = obj.optJSONArray("c");
            if (cList != null && cList.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < cList.length(); i++) {
                    JSONObject c = cList.optJSONObject(i);
                    if (c != null) {
                        sb.append(c.optString("tx", ""));
                    }
                }
                String text = decodeHtmlEntities(sb.toString().trim());
                if (!text.isEmpty()) {
                    return new SuperLyricLine(text, t, t + 1500L);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @NonNull
    Map<Long, String> parseLrcToMap(@Nullable String lrcText) {
        if (lrcText == null || lrcText.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new TreeMap<>();
        String[] lines = lrcText.split("\n");
        long offsetMs = 0L;

        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            Matcher offsetMatcher = OFFSET_PATTERN.matcher(trimmed);
            if (offsetMatcher.matches()) {
                try {
                    offsetMs = Long.parseLong(offsetMatcher.group(1));
                } catch (Throwable ignored) {
                }
                continue;
            }

            Matcher matcher = LRC_TIME_TAG_PATTERN.matcher(trimmed);
            List<Long> timeStamps = new ArrayList<>();
            int lastEnd = 0;
            while (matcher.find()) {
                if (matcher.start() == lastEnd) {
                    long startMs = parseTimeTag(matcher.group(1), matcher.group(2));
                    timeStamps.add(Math.max(0L, startMs + offsetMs));
                    lastEnd = matcher.end();
                } else {
                    break;
                }
            }

            if (!timeStamps.isEmpty()) {
                String rawContent = trimmed.substring(lastEnd).trim();
                String content = decodeHtmlEntities(rawContent);
                if (!content.isEmpty()) {
                    for (long timeMs : timeStamps) {
                        map.put(timeMs, content);
                    }
                }
            }
        }
        return map;
    }

    @Nullable
    private String findClosestTranslation(@NonNull Map<Long, String> transMap, long timeMs, long thresholdMs) {
        if (transMap.isEmpty()) return null;
        String exact = transMap.get(timeMs);
        if (exact != null) return exact;

        long bestDiff = thresholdMs + 1;
        String bestTrans = null;
        for (Map.Entry<Long, String> entry : transMap.entrySet()) {
            long diff = Math.abs(entry.getKey() - timeMs);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestTrans = entry.getValue();
            }
        }
        return bestTrans;
    }

    private static long parseTimeTag(@NonNull String minStr, @NonNull String secAndFracStr) {
        try {
            long mins = Long.parseLong(minStr);
            double secAndFrac = Double.parseDouble(secAndFracStr);
            return mins * 60_000L + (long) Math.round(secAndFrac * 1000.0);
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Nullable
    private String extractLyricText(@NonNull JSONObject root, @NonNull String key) {
        JSONObject obj = root.optJSONObject(key);
        if (obj != null) {
            return obj.optString("lyric", null);
        }
        return null;
    }

    /**
     * 针对 HttpURLConnection 连接的 GZIP 与流式块读取。
     */
    @NonNull
    private static String readStream(@NonNull HttpURLConnection conn) throws Exception {
        InputStream in = conn.getInputStream();
        String encoding = conn.getContentEncoding();
        if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
            in = new GZIPInputStream(in);
        } else {
            PushbackInputStream pb = new PushbackInputStream(in, 2);
            byte[] head = new byte[2];
            int count = pb.read(head);
            if (count == 2 && ((head[0] & 0xFF) == 0x1F) && ((head[1] & 0xFF) == 0x8B)) {
                pb.unread(head);
                in = new GZIPInputStream(pb);
            } else if (count > 0) {
                pb.unread(head, 0, count);
                in = pb;
            } else {
                in = pb;
            }
        }
        return readStream(in);
    }

    @NonNull
    private static String readStream(@NonNull InputStream in) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int charsRead;
            while ((charsRead = reader.read(buffer, 0, buffer.length)) != -1) {
                sb.append(buffer, 0, charsRead);
            }
            return sb.toString();
        }
    }

    /**
     * 智能提取音轨中的真实数字 ID，兼容 song_12345678, cloudmusic_123456 等宿主前缀。
     */
    static long parseNumericId(@Nullable String trackId) {
        if (trackId == null) return -1L;
        String trimmed = trackId.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            Matcher m = NUMERIC_ID_PATTERN.matcher(trimmed);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1));
                } catch (NumberFormatException ignored2) {
                }
            }
            return -1L;
        }
    }

    static String cleanSearchKeyword(@NonNull String title) {
        String res = CLEAN_TRACK_NO_PATTERN.matcher(title).replaceAll("");
        res = CLEAN_BRACKETS_PATTERN.matcher(res).replaceAll("");
        res = CLEAN_FEAT_PATTERN.matcher(res).replaceAll("");
        res = CLEAN_EXT_PATTERN.matcher(res).replaceAll("");
        return res.trim();
    }

    static String extractMainArtist(@NonNull String artist) {
        String[] parts = artist.split("[/&,、]");
        return parts.length > 0 ? parts[0].trim() : artist.trim();
    }

    @NonNull
    static String decodeHtmlEntities(@NonNull String text) {
        if (text.indexOf('&') == -1) {
            return text;
        }
        return text.replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ");
    }

    // ========================== 离线磁盘二级缓存 (Disk Cache) ==========================

    @Nullable
    private static JSONObject readDiskCache(long musicId) {
        try {
            Context ctx = AbsPublisher.getAppContext();
            AndroidLog.logD(ENGINE_TAG, "readDiskCache: Querying LyricCacheStore for musicId=" + musicId
                + ", ctx=" + (ctx != null ? ctx.getPackageName() : "null"));
            String jsonStr = LyricCacheStore.get(ctx, PROVIDER_NAME, String.valueOf(musicId));
            if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                AndroidLog.logI(ENGINE_TAG, "readDiskCache: Successfully loaded cached JSON (" + jsonStr.length() + " chars) for musicId=" + musicId);
                return new JSONObject(jsonStr);
            }
            AndroidLog.logD(ENGINE_TAG, "readDiskCache: Missed disk cache for musicId=" + musicId);
        } catch (Throwable t) {
            AndroidLog.logW(ENGINE_TAG, "readDiskCache: Failed for " + musicId + ": " + t.getMessage(), t);
        }
        return null;
    }

    private static void writeDiskCacheAsync(@Nullable ExecutorService executor, long musicId, @NonNull String jsonStr) {
        Context ctx = AbsPublisher.getAppContext();
        AndroidLog.logI(ENGINE_TAG, "writeDiskCacheAsync: Submitting cache write for musicId=" + musicId
            + " (payload length=" + jsonStr.length() + "), ctx=" + (ctx != null ? ctx.getPackageName() : "null"));
        LyricCacheStore.putAsync(executor, ctx, PROVIDER_NAME, String.valueOf(musicId), jsonStr);
    }

    private static final class WordTag {
        final long startMs;
        final long durMs;
        final int startPos;
        final int endPos;

        WordTag(long startMs, long durMs, int startPos, int endPos) {
            this.startMs = startMs;
            this.durMs = durMs;
            this.startPos = startPos;
            this.endPos = endPos;
        }
    }

    private static final class TempLine {
        final String content;
        final long startMs;

        TempLine(String content, long startMs) {
            this.content = content;
            this.startMs = startMs;
        }
    }
}
