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
package com.hchen.superlyric.provider;

import android.content.Context;
import android.media.MediaMetadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.provider.spotify.SpotifyLyricAnalysis;
import com.hchen.superlyric.publisher.AbsPublisher;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricCacheStore;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spotify 统一歌词提供者。
 * <p>
 * <b>架构与技术设计说明：</b>
 * <ul>
 *   <li><b>纯网络模式驱动 (NETWORK_ONLY)：</b>
 *       鉴于 Spotify 内部核心播放与歌词组件全部由 C++ 原生实现（C++ core player），应用层无暴露的 Java 歌词模型，
 *       因此本 Provider 遵循纯网络接口拉取规范，解耦应用内不可靠的私有 Hook。</li>
 *   <li><b>系统媒体会话感知：</b>
 *       通过全局 {@code MediaSession} 自动同步当前曲目的播放状态、进度与音轨标识，
 *       精确过滤无 {@code spotify:track:} 前缀的非歌曲内容（如商业广告音频）。</li>
 *   <li><b>会话头嗅探与动态鉴权：</b>
 *       针对 Spotify 9.1.78+ 开启 R8 全量重命名的情况，结合明文快速通道与 DexKit 构造器指纹（{@code <init>(String[])}）
 *       自适应捕获私有歌词接口所需的全部会话鉴权头，并在鉴权过期（401/403）时自动触发重新捕获与静默刷新。</li>
 *   <li><b>高精 Protobuf / JSON 双规整与本地持久缓存：</b>
 *       优先拉取 Protobuf 编码的逐字毫秒时序并规整映射为标准 {@link SuperLyricLine} 与逐字切片，
 *       并深度集成 {@link LyricCacheStore} 进行本地持久化缓存。</li>
 * </ul>
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
@HookThis(targetPackage = "com.spotify.music")
public class SpotifyProvider extends UnifiedLyricProvider {
    private static final String TAG = "SpotifyProvider";

    public SpotifyProvider() {
        super();
    }

    @NonNull
    @Override
    public ProviderCapability capability() {
        return ProviderCapability.NETWORK_ONLY;
    }

    @Nullable
    @Override
    protected IHookLyricEngine createHookEngine() {
        return null;
    }

    @Nullable
    @Override
    protected INetworkLyricEngine createNetworkEngine() {
        return new SpotifyNetworkLyricEngine();
    }

    /**
     * 从 MediaMetadata.METADATA_KEY_MEDIA_ID 中剥离 {@code spotify:track:} 前缀提取标准音轨 ID。
     * 若缺少前缀或为空（如广告音频），返回 null，触发停止播放与清空歌词。
     */
    @Nullable
    @Override
    protected String extractTrackId(@NonNull MediaMetadata metadata) {
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (mediaId == null) return null;
        String stripped = mediaId.startsWith("spotify:track:")
            ? mediaId.substring("spotify:track:".length())
            : "";
        return stripped.isBlank() ? null : stripped;
    }

    /**
     * Spotify 网络歌词拉取引擎实现。
     */
    private final class SpotifyNetworkLyricEngine implements INetworkLyricEngine {
        private final ExecutorService mExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "SpotifyNetwork-Worker");
            t.setDaemon(true);
            return t;
        });

        private final ScheduledExecutorService mTimeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SpotifyNetwork-Timeout");
            t.setDaemon(true);
            return t;
        });

        @Override
        public void initEngine() {
            hookSessionHeaders();
        }

        @Override
        public void onTrackChanged(@NonNull TrackContext context) {
            SpotifyLyricAnalysis.cancelExcept(context.getTrackId());
        }

        @NonNull
        @Override
        public CompletableFuture<SuperLyricData> fetchFullLyric(@NonNull TrackContext context) {
            CompletableFuture<SuperLyricData> future = new CompletableFuture<>();
            String trackId = context.getTrackId();
            if (trackId == null || trackId.trim().isEmpty()) {
                future.completeExceptionally(new IllegalArgumentException("Empty trackId"));
                return future;
            }

            mExecutor.execute(() -> {
                try {
                    // 1. 优先读取磁盘缓存
                    String cacheKey = LyricCacheStore.currentLocaleTag() + "/" + trackId;
                    Context appContext = AbsPublisher.getAppContext();
                    byte[] cached = LyricCacheStore.getBytes(appContext, "Spotify", cacheKey);
                    if (cached != null) {
                        SpotifyLyricAnalysis.ParseResult result = SpotifyLyricAnalysis.parseResult(cached);
                        if (result.type == SpotifyLyricAnalysis.ParseType.READY && result.lines != null && !result.lines.isEmpty()) {
                            SuperLyricData data = buildSuperLyricData(context, result.lines);
                            SuperLyricData sanitized = LyricSanitizer.sanitizeData(data);
                            if (sanitized != null && sanitized.hasAllLyrics()) {
                                AndroidLog.logI(TAG, "Loaded Spotify lyrics from disk cache for: " + trackId);
                                future.complete(sanitized);
                                return;
                            }
                        }
                        LyricCacheStore.delete(appContext, "Spotify", cacheKey);
                    }

                    // 2. 检查会话头
                    SpotifyLyricAnalysis.HeaderSnapshot headers = SpotifyLyricAnalysis.currentHeaders();
                    if (headers != null) {
                        doNetworkFetch(context, trackId, headers, cacheKey, future);
                    } else {
                        AndroidLog.logI(TAG, "Waiting for Spotify session headers to fetch lyrics for: " + trackId);
                        waitForHeadersAndFetch(context, trackId, cacheKey, future);
                    }
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });

            return future;
        }

        @Override
        public void cancel() {
            SpotifyLyricAnalysis.cancelExcept(null);
        }

        /**
         * 捕获会话头：明文快速通道 + DexKit 结构指纹兜底。
         */
        private void hookSessionHeaders() {
            AbsHook captureHook = new AbsHook() {
                @Override
                public void after() {
                    Object[] args = getArgs();
                    if (args != null && args.length > 0 && args[0] instanceof String[]) {
                        captureNamesAndValues((String[]) args[0]);
                    }
                }
            };

            // ① 明文快速通道
            try {
                Class<?> headersClass = findClass("okhttp3.Headers");
                hookAllConstructor(headersClass, captureHook);
                AndroidLog.logI(TAG, "Session headers: plaintext okhttp3.Headers hooked");
                return;
            } catch (Throwable t) {
                AndroidLog.logD(TAG, "Session headers: plaintext okhttp3.Headers unavailable, switch to DexKit structural scan", t);
            }

            // ② DexKit 结构指纹兜底
            hookSessionHeadersByStructure(captureHook);
        }

        private void hookSessionHeadersByStructure(@NonNull AbsHook captureHook) {
            int hooked = 0;
            List<String> hookedClasses = new ArrayList<>();
            for (Class<?> candidate : findHeaderCandidateClasses()) {
                try {
                    hookAllConstructor(candidate, captureHook);
                    hooked++;
                    hookedClasses.add(candidate.getName());
                } catch (Throwable t) {
                    AndroidLog.logW(TAG, "Session headers: hook constructors of DexKit candidate "
                        + candidate.getName() + " failed", t);
                }
            }
            if (hooked == 0) {
                AndroidLog.logW(TAG, "Session headers: no DexKit candidate hooked; color-lyrics requests will stay 401 on this Spotify build");
                return;
            }
            AndroidLog.logI(TAG, "Session headers: hooked " + hooked + " DexKit candidate class(es) by <init>(String[]) fingerprint: " + hookedClasses);
        }

        @NonNull
        private List<Class<?>> findHeaderCandidateClasses() {
            List<Class<?>> candidates = new ArrayList<>();

            // 精确查询缓存键：含 <init>(String[]) 构造器且实现 Iterable 的类
            try {
                Class<?>[] precise = DexkitCache.findMember("spotify$headers_ctor_iterable",
                    new IDexkit<ClassDataList>() {
                        @NonNull
                        @Override
                        public ClassDataList dexkit(@NonNull DexKitBridge bridge) {
                            return bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                    .addInterface(ClassMatcher.create(Iterable.class))
                                    .addMethod(MethodMatcher.create()
                                        .name("<init>")
                                        .paramTypes("java.lang.String[]")
                                    )
                                )
                            );
                        }
                    });
                if (precise != null) {
                    Collections.addAll(candidates, precise);
                }
            } catch (Throwable t) {
                AndroidLog.logD(TAG, "Precise DexKit scan (<init>(String[]) + Iterable) failed, fallback to loose scan", t);
            }
            if (!candidates.isEmpty()) return candidates;

            // 宽松查询缓存键：仅含 <init>(String[]) 构造器的类
            try {
                Class<?>[] loose = DexkitCache.findMember("spotify$headers_ctor",
                    new IDexkit<ClassDataList>() {
                        @NonNull
                        @Override
                        public ClassDataList dexkit(@NonNull DexKitBridge bridge) {
                            return bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                    .addMethod(MethodMatcher.create()
                                        .name("<init>")
                                        .paramTypes("java.lang.String[]")
                                    )
                                )
                            );
                        }
                    });
                if (loose != null) {
                    Collections.addAll(candidates, loose);
                }
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Loose DexKit scan failed: " + t.getMessage());
            }
            return candidates;
        }

        private void captureNamesAndValues(@NonNull String[] namesAndValues) {
            SpotifyLyricAnalysis.updateHeaders(namesAndValues);
        }

        private void waitForHeadersAndFetch(@NonNull TrackContext context, @NonNull String trackId,
                                            @NonNull String cacheKey,
                                            @NonNull CompletableFuture<SuperLyricData> future) {
            AtomicBoolean triggered = new AtomicBoolean(false);
            Runnable listener = new Runnable() {
                @Override
                public void run() {
                    if (triggered.compareAndSet(false, true)) {
                        SpotifyLyricAnalysis.removeHeaderListener(this);
                        SpotifyLyricAnalysis.HeaderSnapshot headers = SpotifyLyricAnalysis.currentHeaders();
                        if (headers != null) {
                            mExecutor.execute(() -> doNetworkFetch(context, trackId, headers, cacheKey, future));
                        } else {
                            future.completeExceptionally(new IOException("Headers not available after wakeup"));
                        }
                    }
                }
            };
            SpotifyLyricAnalysis.addHeaderListener(listener);

            mTimeoutScheduler.schedule(() -> {
                if (triggered.compareAndSet(false, true)) {
                    SpotifyLyricAnalysis.removeHeaderListener(listener);
                    future.completeExceptionally(new TimeoutException("Timed out waiting for Spotify session headers"));
                }
            }, 15, TimeUnit.SECONDS);
        }

        private void doNetworkFetch(@NonNull TrackContext context, @NonNull String trackId,
                                    @NonNull SpotifyLyricAnalysis.HeaderSnapshot headers,
                                    @NonNull String cacheKey,
                                    @NonNull CompletableFuture<SuperLyricData> future) {
            try {
                byte[] raw = SpotifyLyricAnalysis.fetchLyric(trackId, headers);
                SpotifyLyricAnalysis.ParseResult result = SpotifyLyricAnalysis.parseResult(raw);
                if (result.type != SpotifyLyricAnalysis.ParseType.READY || result.lines == null || result.lines.isEmpty()) {
                    future.completeExceptionally(new IOException("No usable lyric for " + trackId + " (type=" + result.type + ")"));
                    return;
                }

                // 写入磁盘缓存
                Context appContext = AbsPublisher.getAppContext();
                LyricCacheStore.put(appContext, "Spotify", cacheKey, raw);

                SuperLyricData data = buildSuperLyricData(context, result.lines);
                SuperLyricData sanitized = LyricSanitizer.sanitizeData(data);
                if (sanitized != null && sanitized.hasAllLyrics()) {
                    future.complete(sanitized);
                } else {
                    future.completeExceptionally(new IOException("Sanitization failed for " + trackId));
                }
            } catch (SpotifyLyricAnalysis.LyricNotFoundException e) {
                AndroidLog.logI(TAG, "No lyric found (404) for Spotify track: " + trackId);
                future.completeExceptionally(e);
            } catch (SpotifyLyricAnalysis.AuthenticationException e) {
                AndroidLog.logW(TAG, "Spotify authentication failed (" + e.code + "), waiting for refreshed headers");
                waitForHeadersAndFetch(context, trackId, cacheKey, future);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }

        private SuperLyricData buildSuperLyricData(@NonNull TrackContext context,
                                                   @NonNull List<SpotifyLyricAnalysis.SpotifyLine> lines) {
            SuperLyricLine[] lyricLines = new SuperLyricLine[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                SpotifyLyricAnalysis.SpotifyLine line = lines.get(i);
                SuperLyricLine rawLine = new SuperLyricLine(
                    line.text,
                    line.words,
                    line.transliteratedWords,
                    line.startTimeMs,
                    line.endTimeMs
                );
                SuperLyricLine sanitized = LyricSanitizer.sanitizeLine(rawLine);
                lyricLines[i] = (sanitized != null) ? sanitized : rawLine;
            }

            SuperLyricData data = new SuperLyricData();
            data.setAllLyrics(lyricLines);
            data.setLyricId(context.getTrackId());
            data.setTitle(context.getTitle());
            data.setArtist(context.getArtist());
            data.setAlbum(context.getAlbum());
            data.setDuration(context.getDuration());
            return data;
        }
    }
}
