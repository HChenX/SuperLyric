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
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用多源在线歌词聚合与打分仲裁网络引擎。
 * <p>
 * <b>技术原理：</b>
 * <ul>
 *   <li><b>分级路由优先机制：</b>
 *       若音轨 ID 匹配已知精确特征（如 14 位 QQ songmid 或 32 位酷狗 Hash），直接走 100% 精确官方直查通道；</li>
 *   <li><b>多源异步检索与仲裁：</b>
 *       首选酷狗开放 KRC 源（版权覆盖全、接口快速免签、逐字率极高）；次选 QQ 音乐 QRC 源；
 *       严苛降权/排除网易云源；</li>
 *   <li><b>三维加权几何打分：</b>
 *       基于 {@link LyricScorer} 对返回候选进行歌名、歌手与高斯时长打分，得分 >= 80 且具备逐字时序方可采纳；</li>
 *   <li><b>高精双层缓存机制：</b>
 *       内置线程安全的 LRU 内存缓存，实现秒开与零重复网络请求。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public class MultiSourceLyricEngine implements INetworkLyricEngine {
    private static final String TAG = "MultiSourceLyricEngine";

    private static final int MAX_CACHE_SIZE = 120;
    private static final Map<String, SuperLyricData> sMemoryCache = Collections.synchronizedMap(
        new LinkedHashMap<String, SuperLyricData>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SuperLyricData> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        }
    );

    private static final ExecutorService sExecutor = new ThreadPoolExecutor(
        2, 8, 30L, TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        r -> {
            Thread t = new Thread(r, "SuperLyric-MultiSourceNet-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<SuperLyricData>> mCurrentFuture = new AtomicReference<>();

    public MultiSourceLyricEngine() {
    }

    @Override
    public void initEngine() {
        AndroidLog.logI(TAG, "MultiSourceLyricEngine initialized");
    }

    @Override
    public void onTrackChanged(@NonNull TrackContext context) {
        mActiveTrack.set(context);
        cancel();
    }

    @NonNull
    @Override
    public CompletableFuture<SuperLyricData> fetchFullLyric(@NonNull TrackContext context) {
        cancel();
        mActiveTrack.set(context);

        String trackId = context.getTrackId();
        String title = context.getTitle();
        String artist = context.getArtist();
        long duration = context.getDuration();

        // 1. 检查内存 LRU 缓存
        String cacheKey = buildCacheKey(trackId, title, artist);
        SuperLyricData cached = sMemoryCache.get(cacheKey);
        if (cached != null && cached.hasAllLyrics()) {
            AndroidLog.logI(TAG, "Hit memory cache for: " + cacheKey);
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<SuperLyricData> future = new CompletableFuture<>();
        mCurrentFuture.set(future);

        sExecutor.execute(() -> {
            try {
                if (future.isCancelled()) return;

                SuperLyricData resolved = resolveLyricInternal(context);
                if (resolved != null && resolved.hasAllLyrics()) {
                    SuperLyricData clean = LyricSanitizer.sanitizeData(resolved);
                    if (clean != null) {
                        sMemoryCache.put(cacheKey, clean);
                        if (!future.isCancelled()) {
                            future.complete(clean);
                            return;
                        }
                    }
                }

                if (!future.isCancelled()) {
                    future.complete(null);
                }
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Error in MultiSource fetchFullLyric for " + trackId + ": " + t.getMessage());
                if (!future.isCancelled()) {
                    future.complete(null);
                }
            }
        });

        return future;
    }

    @Override
    public void cancel() {
        CompletableFuture<SuperLyricData> old = mCurrentFuture.getAndSet(null);
        if (old != null && !old.isDone()) {
            old.cancel(true);
        }
    }

    @Nullable
    private SuperLyricData resolveLyricInternal(@NonNull TrackContext context) {
        String trackId = context.getTrackId();
        String title = context.getTitle();
        String artist = context.getArtist();
        long duration = context.getDuration();

        // 通道 1：精确 ID 直查（100% 准确度）
        // 1.1 若 trackId 为 14 位 QQ songmid (字母与数字组合)
        if (trackId != null && trackId.length() == 14 && trackId.matches("^[0-9a-zA-Z]{14}$")) {
            AndroidLog.logI(TAG, "Exact match: Attempting QQ Music mid lookup for: " + trackId);
            SuperLyricData data = QQMusicLyricSource.fetchLyricByMid(trackId, 0L, title, artist);
            if (data != null && data.hasAllLyrics()) {
                return data;
            }
        }

        // 1.2 若 trackId 为纯数字 (QQ 音乐 songID 或官方数字标识)
        if (trackId != null && trackId.matches("^\\d{5,12}$")) {
            try {
                long numId = Long.parseLong(trackId);
                AndroidLog.logI(TAG, "Exact match: Attempting QQ Music songID lookup for: " + numId);
                SuperLyricData data = QQMusicLyricSource.fetchLyricByMid("", numId, title, artist);
                if (data != null && data.hasAllLyrics()) {
                    return data;
                }
            } catch (Throwable ignored) {
            }
        }

        // 1.3 若 trackId 为 32 位酷狗 Hash (16进制)
        if (trackId != null && trackId.length() == 32 && trackId.matches("^[0-9a-fA-F]{32}$")) {
            AndroidLog.logI(TAG, "Exact match: Attempting KuGou hash lookup for: " + trackId);
            SuperLyricData data = KuGouLyricSource.fetchLyricByHash(trackId, duration, title, artist);
            if (data != null && data.hasAllLyrics()) {
                return data;
            }
        }

        if (title == null || title.trim().isEmpty()) {
            return null;
        }

        // 通道 2：首选酷狗开放 KRC 检索（覆盖面全，响应极快，100% 逐字）
        AndroidLog.logD(TAG, "Searching KuGou for: \"" + title + "\" - \"" + artist + "\" (dur: " + duration + "ms)");
        List<KuGouLyricSource.Candidate> kgCandidates = KuGouLyricSource.searchCandidates(title, artist, duration);
        if (!kgCandidates.isEmpty()) {
            KuGouLyricSource.Candidate best = kgCandidates.get(0);
            if (best.score >= 80.0) {
                AndroidLog.logI(TAG, "KuGou matched candidate: " + best.title + " - " + best.artist
                    + " (score=" + String.format("%.1f", best.score) + ", hash=" + best.hash + ")");
                SuperLyricData data = KuGouLyricSource.fetchLyricByHash(best.hash, best.durationMs, title, artist);
                if (data != null && data.hasAllLyrics()) {
                    return data;
                }
            }
        }

        // 通道 3：次选 QQ 音乐模糊检索（高官方质量逐字）
        AndroidLog.logD(TAG, "Searching QQ Music for: \"" + title + "\" - \"" + artist + "\"");
        List<QQMusicLyricSource.Candidate> qqCandidates = QQMusicLyricSource.searchCandidates(title, artist, duration);
        if (!qqCandidates.isEmpty()) {
            QQMusicLyricSource.Candidate best = qqCandidates.get(0);
            if (best.score >= 80.0) {
                AndroidLog.logI(TAG, "QQ Music matched candidate: " + best.title + " - " + best.artist
                    + " (score=" + String.format("%.1f", best.score) + ", mid=" + best.mid + ")");
                SuperLyricData data = QQMusicLyricSource.fetchLyricByMid(best.mid, best.id, title, artist);
                if (data != null && data.hasAllLyrics()) {
                    return data;
                }
            }
        }

        AndroidLog.logI(TAG, "No candidate met acceptance threshold (>= 80.0) for: " + title);
        return null;
    }

    @NonNull
    private static String buildCacheKey(@Nullable String trackId, @Nullable String title, @Nullable String artist) {
        if (trackId != null && !trackId.isEmpty()) {
            return trackId;
        }
        return (title != null ? title.trim() : "") + "_" + (artist != null ? artist.trim() : "");
    }
}
