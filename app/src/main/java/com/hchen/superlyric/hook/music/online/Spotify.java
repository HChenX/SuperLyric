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
package com.hchen.superlyric.hook.music.online;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyric.hook.music.online.spotify.SpotifyLyricAnalysis;
import com.hchen.superlyric.hook.music.online.spotify.SpotifyLyricAnalysis.SpotifyLine;
import com.hchen.superlyric.utils.LyricCacheStore;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Spotify 歌词提供者。
 * <p>
 * hook 宿主媒体会话（setPlaybackState / setMetadata）取音轨与播放状态，
 * hook 宿主网络栈（okhttp3.Headers）捕获会话头，调用 Spotify color-lyrics
 * 私有接口拉取歌词，按「位置插值 + Handler 轮询」推进当前行发布，音译进翻译槽位。
 * 磁盘缓存命中不触发网络请求；404 视为无歌词；广告 / 无法解析音轨 → sendStop。
 * <p>
 * Inspired from LyricProvider/spotify-music.
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
@HookThis(targetPackage = "com.spotify.music")
public final class Spotify extends AbsPublisher {
    private static final long LOOP_INTERVAL_MS = 42L;

    private Context mAppContext;
    private HandlerThread mLyricThread;
    private Handler mLyricHandler;
    private boolean mHooksInitialized;

    // 播放状态（setPlaybackState 写入，轮询线程读取）：
    // 单一不可变快照整体发布，避免 state/position/speed/锚点多次 volatile 写入的中间态
    private volatile PlaybackSnapshot mPlayback = PlaybackSnapshot.INITIAL;

    // 当前歌曲与歌词快照（不可变，整体发布）：
    // song + lyric + lastShownIndex 原子可见；行推进 / 歌词写入均以 compareAndSet
    // 原子替换，快速切歌时异步拉取与切歌不产生歌词与元数据错配、无上一首残留
    private final AtomicReference<TrackSnapshot> mTrackRef = new AtomicReference<>();
    private final AtomicLong mTrackGeneration = new AtomicLong();

    // 轮询推进：mIsRunning 防重入；mLoopToken 使重启后的旧轮询链立即失效，
    // 避免 stopLoop→startLoop 时序下出现双链并行
    private volatile boolean mIsRunning = false;
    private volatile long mLoopToken = 0L;

    private static final class HeaderWait {
        @NonNull
        final String trackId;
        final long headerGeneration;
        final long trackGeneration;

        HeaderWait(@NonNull String trackId, long headerGeneration, long trackGeneration) {
            this.trackId = trackId;
            this.headerGeneration = headerGeneration;
            this.trackGeneration = trackGeneration;
        }
    }

    private final AtomicReference<HeaderWait> mHeaderWait = new AtomicReference<>();

    private final ThreadPoolExecutor mDownloadExecutor = new ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final Set<String> mDownloadingIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_FETCH_RETRIES = 3;
    private static final int MAX_AUTH_REFRESHES = 2;
    private final ConcurrentHashMap<String, Integer> mRetryCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> mAuthRefreshCounts = new ConcurrentHashMap<>();


    /**
     * 歌曲上下文快照：音轨标识 + 标题/歌手，与歌词配对发布的元数据来源。
     */
    private static final class SongInfo {
        @NonNull
        final String trackId;
        @NonNull
        final String title;
        @NonNull
        final String artist;

        SongInfo(@NonNull String trackId, @NonNull String title, @NonNull String artist) {
            this.trackId = trackId;
            this.title = title;
            this.artist = artist;
        }
    }

    /**
     * 歌词快照：所属音轨标识 + 规整后的行列表（不可变）。
     */
    private static final class LyricData {
        @NonNull
        final String trackId;
        @NonNull
        final List<SpotifyLine> lines;

        LyricData(@NonNull String trackId, @NonNull List<SpotifyLine> lines) {
            this.trackId = trackId;
            this.lines = lines;
        }
    }

    /**
     * 播放状态不可变快照：state / position / speed 与锚点时间一次写入。
     */
    private static final class PlaybackSnapshot {
        static final PlaybackSnapshot INITIAL =
            new PlaybackSnapshot(PlaybackState.STATE_NONE, 0L, 0f, 0L);

        final int state;
        final long position;
        final float speed;
        final long anchorTime;

        PlaybackSnapshot(int state, long position, float speed, long anchorTime) {
            this.state = state;
            this.position = position;
            this.speed = speed;
            this.anchorTime = anchorTime;
        }
    }

    /**
     * 歌曲上下文快照：当前歌曲 + 歌词（可为空）+ 已展示行索引，整体原子替换。
     */
    private static final class TrackSnapshot {
        final long generation;
        @NonNull
        final SongInfo song;
        @Nullable
        final LyricData lyric;
        final int lastShownIndex;

        TrackSnapshot(long generation, @NonNull SongInfo song, @Nullable LyricData lyric, int lastShownIndex) {
            this.generation = generation;
            this.song = song;
            this.lyric = lyric;
            this.lastShownIndex = lastShownIndex;
        }

        @NonNull
        TrackSnapshot withLyric(@NonNull LyricData newLyric) {
            return new TrackSnapshot(generation, song, newLyric, -1);
        }

        @NonNull
        TrackSnapshot withShownIndex(int index) {
            return new TrackSnapshot(generation, song, lyric, index);
        }
    }

    @Override
    protected synchronized void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        if (mHooksInitialized) {
            logD(tag, "Spotify hooks already initialized for " + param.getPackageName());
            return;
        }
        mLyricThread = new HandlerThread("SpotifyLyricThread");
        mLyricThread.start();
        mLyricHandler = new Handler(mLyricThread.getLooper());

        hookPlaybackState();
        hookMetadata();
        hookSessionHeaders();
        mHooksInitialized = true;

        logI(tag, "Spotify hooks loaded (package: " + param.getPackageName() + ")");
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        mAppContext = context.getApplicationContext();
    }

    // ------------------------------ MediaSession hooks ------------------------------

    private void hookPlaybackState() {
        hookMethod("android.media.session.MediaSession",
            "setPlaybackState",
            "android.media.session.PlaybackState",
            new AbsHook() {
                @Override
                public void after() {
                    Object arg = getArg(0);
                    if (arg instanceof PlaybackState state) {
                        onPlaybackStateChanged(state);
                    }
                }
            }
        );
    }

    private void hookMetadata() {
        hookMethod("android.media.session.MediaSession",
            "setMetadata",
            "android.media.MediaMetadata",
            new AbsHook() {
                @Override
                public void after() {
                    Object arg = getArg(0);
                    if (arg instanceof MediaMetadata metadata) {
                        onMetadataChanged(metadata);
                    }
                }
            }
        );
    }

    private void onPlaybackStateChanged(@NonNull PlaybackState state) {
        mPlayback = new PlaybackSnapshot(
            state.getState(),
            state.getPosition(),
            state.getPlaybackSpeed(),
            SystemClock.elapsedRealtime()
        );

        logD(tag, "PlaybackState: state=" + mPlayback.state
            + ", position=" + mPlayback.position + ", speed=" + mPlayback.speed);

        switch (mPlayback.state) {
            case PlaybackState.STATE_PLAYING:
                startLoop();
                break;
            case PlaybackState.STATE_STOPPED:
                sendStop();
                stopLoop();
                break;
            default:
                // 暂停/缓冲等：停表保留最后一行
                stopLoop();
                break;
        }
    }

    private void onMetadataChanged(@NonNull MediaMetadata metadata) {
        String id = extractTrackId(metadata);
        TrackSnapshot current = mTrackRef.get();
        if (id == null) {
            // 广告 / 无法解析音轨标识 → sendStop 清空（Apple 范式）
            logD(tag, "setMetadata: no valid track id, treat as ad/unknown, sendStop");
            SpotifyLyricAnalysis.cancelExcept(null);
            mHeaderWait.set(null);
            mTrackRef.set(null);
            sendStop();
            stopLoop();
            return;
        }

        if (current != null && id.equals(current.song.trackId)) {
            logD(tag, "setMetadata: same track, ignore: " + id);
            return;
        }

        SpotifyLyricAnalysis.cancelExcept(id);
        // 切歌：立即清空旧歌词与显示，避免残留上一首
        sendStop();
        stopLoop();

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        long generation = mTrackGeneration.incrementAndGet();
        mTrackRef.set(new TrackSnapshot(
            generation,
            new SongInfo(id, title != null ? title : "", artist != null ? artist : ""),
            null,
            -1
        ));
        logD(tag, "Track changed: id=" + id);

        fetchLyricsForTrack(id, generation);
    }

    /**
     * 从 MEDIA_ID 剥 {@code spotify:track:} 前缀取音轨标识；
     * 无前缀 / 为空 → 非歌曲内容（广告），返回 null。
     */
    @Nullable
    private String extractTrackId(@NonNull MediaMetadata metadata) {
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (mediaId == null) return null;
        String stripped = mediaId.startsWith("spotify:track:")
            ? mediaId.substring("spotify:track:".length())
            : "";
        return stripped.isBlank() ? null : stripped;
    }

    // ------------------------------ 拉取与缓存 ------------------------------

    private static String taskKey(@NonNull String id, long generation) {
        return id + "#" + generation;
    }

    private void fetchLyricsForTrack(@NonNull String id, long generation) {
        String taskKey = taskKey(id, generation);
        if (!mDownloadingIds.add(taskKey)) {
            logD(tag, "Lyric already loading for " + id);
            return;
        }
        try {
            mDownloadExecutor.execute(() -> loadLyricsForTrack(id, generation, taskKey));
        } catch (RejectedExecutionException e) {
            mDownloadingIds.remove(taskKey);
            scheduleFetchRetry(id, generation);
        }
    }

    private void loadLyricsForTrack(@NonNull String id, long generation, @NonNull String taskKey) {
        String cacheKey = LyricCacheStore.currentLocaleTag() + "/" + id;
        try {
            if (!isCurrentTrack(id, generation)) return;
            byte[] cached = LyricCacheStore.getBytes(mAppContext, "Spotify", cacheKey);
            if (cached != null) {
                SpotifyLyricAnalysis.ParseResult result = SpotifyLyricAnalysis.parseResult(cached);
                if (result.type == SpotifyLyricAnalysis.ParseType.READY && result.lines != null) {
                    applyLyrics(id, generation, result.lines);
                    return;
                }
                LyricCacheStore.delete(mAppContext, "Spotify", cacheKey);
            }

            SpotifyLyricAnalysis.HeaderSnapshot headers = SpotifyLyricAnalysis.currentHeaders();
            if (headers == null) {
                waitForHeaders(id, 0L, generation);
                return;
            }
            byte[] raw = SpotifyLyricAnalysis.fetchLyric(id, headers);
            SpotifyLyricAnalysis.ParseResult result = SpotifyLyricAnalysis.parseResult(raw);
            if (result.type == SpotifyLyricAnalysis.ParseType.MALFORMED) {
                scheduleFetchRetry(id, generation);
                return;
            }
            if (result.type != SpotifyLyricAnalysis.ParseType.READY || result.lines == null) {
                logW(tag, "Lyric response not usable for " + id + ", type=" + result.type);
                return;
            }
            if (!isCurrentTrack(id, generation)) return;
            LyricCacheStore.put(mAppContext, "Spotify", cacheKey, raw);
            applyLyrics(id, generation, result.lines);
        } catch (SpotifyLyricAnalysis.AuthenticationException e) {
            String retryKey = taskKey(id, generation);
            int refreshes = mAuthRefreshCounts.merge(retryKey, 1, Integer::sum);
            if (refreshes <= MAX_AUTH_REFRESHES) {
                waitForHeaders(id, e.generation, generation);
            } else {
                mAuthRefreshCounts.remove(retryKey);
                logW(tag, "Spotify authentication refresh exhausted for " + id);
            }
        } catch (SpotifyLyricAnalysis.LyricNotFoundException e) {
            logD(tag, "No lyric found (404) for " + id);
        } catch (SpotifyLyricAnalysis.OversizeException e) {
            logW(tag, "Spotify lyric response exceeds budget for " + id);
        } catch (SpotifyLyricAnalysis.HttpStatusException e) {
            if (e.retryable) scheduleFetchRetry(id, generation);
            else logW(tag, "Non-retryable Spotify HTTP " + e.code + " for " + id);
        } catch (IOException e) {
            scheduleFetchRetry(id, generation);
        } catch (Exception e) {
            if (isCurrentTrack(id, generation)) logE(tag, "Failed to fetch lyric for " + id, e);
        } finally {
            mDownloadingIds.remove(taskKey);
            resumeHeaderWaitIfReady();
        }
    }

    private void scheduleFetchRetry(@NonNull String id, long generation) {
        if (!isCurrentTrack(id, generation)) return;
        String retryKey = taskKey(id, generation);
        int attempt = mRetryCounts.merge(retryKey, 1, Integer::sum);
        if (attempt > MAX_FETCH_RETRIES) {
            mRetryCounts.remove(retryKey);
            logW(tag, "Spotify lyric retry exhausted for " + id);
            return;
        }
        long delay = Math.min(5000L << (attempt - 1), 30000L);
        mLyricHandler.postDelayed(() -> {
            if (isCurrentTrack(id, generation)) {
                fetchLyricsForTrack(id, generation);
            } else {
                mRetryCounts.remove(retryKey);
                mAuthRefreshCounts.remove(retryKey);
            }
        }, delay);
    }

    private boolean isCurrentTrack(@NonNull String id, long generation) {
        TrackSnapshot current = mTrackRef.get();
        return current != null && current.generation == generation && id.equals(current.song.trackId);
    }

    private void waitForHeaders(@NonNull String id, long headerGeneration, long trackGeneration) {
        if (isCurrentTrack(id, trackGeneration)) {
            mHeaderWait.set(new HeaderWait(id, headerGeneration, trackGeneration));
            resumeHeaderWaitIfReady();
        }
    }

    private void resumeHeaderWaitIfReady() {
        HeaderWait wait = mHeaderWait.get();
        SpotifyLyricAnalysis.HeaderSnapshot headers = SpotifyLyricAnalysis.currentHeaders();
        if (wait == null || headers == null || headers.generation <= wait.headerGeneration) return;
        String taskKey = taskKey(wait.trackId, wait.trackGeneration);
        if (!isCurrentTrack(wait.trackId, wait.trackGeneration) || mDownloadingIds.contains(taskKey))
            return;
        if (mHeaderWait.compareAndSet(wait, null))
            fetchLyricsForTrack(wait.trackId, wait.trackGeneration);
    }

    /**
     * 原子写入歌词快照并启动行推进；
     * 仅当前音轨与请求音轨一致时才生效，过期结果直接丢弃。
     */
    private void applyLyrics(@NonNull String id, long generation,
                             @NonNull List<SpotifyLine> lines) {
        TrackSnapshot current = mTrackRef.get();
        if (current == null || current.generation != generation || !id.equals(current.song.trackId)) {
            logD(tag, "Stale lyric response for " + id + ", discard");
            return;
        }

        // CAS 写入：快照已换代（切歌）时不覆盖，旧响应直接丢弃
        if (!mTrackRef.compareAndSet(current, current.withLyric(new LyricData(id, lines)))) {
            logD(tag, "Track changed while applying lyric for " + id + ", discard");
            return;
        }
        String completedKey = taskKey(id, generation);
        mRetryCounts.remove(completedKey);
        mAuthRefreshCounts.remove(completedKey);
        logD(tag, "Lyrics ready for " + id + ", lines=" + lines.size()
            + ", firstWords=" + (lines.get(0).words == null ? 0 : lines.get(0).words.length));

        if (mPlayback.state == PlaybackState.STATE_PLAYING) {
            startLoop();
        }
    }

    // ------------------------------ 当前行推进 ------------------------------

    private synchronized void startLoop() {
        if (mIsRunning) return;
        mIsRunning = true;
        final long token = ++mLoopToken;
        mLyricHandler.post(() -> runLoop(token));
    }

    private synchronized void stopLoop() {
        mIsRunning = false;
    }

    private void runLoop(long token) {
        if (!mIsRunning || token != mLoopToken) return;

        try {
            PlaybackSnapshot playback = mPlayback;
            if (playback.state != PlaybackState.STATE_PLAYING) {
                mIsRunning = false;
                return;
            }

            // 同一轮迭代内取一致快照：歌词与歌曲上下文配对校验后再发送
            TrackSnapshot current = mTrackRef.get();
            if (current == null || current.lyric == null
                || !current.lyric.trackId.equals(current.song.trackId)) {
                mIsRunning = false;
                return;
            }

            long estimated = estimatePosition(playback, SystemClock.elapsedRealtime());
            int index = findLineIndex(current.lyric.lines, estimated);
            if (index >= 0 && index != current.lastShownIndex) {
                // CAS 原子推进：切歌后旧轮询链的替换失败，立即放弃本轮
                TrackSnapshot updated = current.withShownIndex(index);
                if (mTrackRef.compareAndSet(current, updated)) {
                    sendCurrentLine(current.lyric.lines.get(index), current.song);
                }
            }
        } catch (Throwable t) {
            logE(tag, "Lyric loop error", t);
        }

        if (mIsRunning && token == mLoopToken) {
            mLyricHandler.postDelayed(() -> runLoop(token), LOOP_INTERVAL_MS);
        }
    }

    /**
     * 位置插值：上次采样位置 + 速度 × 流逝时间。
     */
    private static long estimatePosition(@NonNull PlaybackSnapshot playback, long now) {
        long base = Math.max(0L, playback.position);
        if (!Float.isFinite(playback.speed) || playback.speed < 0f || now <= playback.anchorTime)
            return base;
        long elapsed = now - playback.anchorTime;
        double delta = elapsed * (double) playback.speed;
        if (!Double.isFinite(delta) || delta >= Long.MAX_VALUE) return Long.MAX_VALUE;
        try {
            return Math.max(0L, Math.addExact(base, (long) delta));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 顺序查找 startTime ≤ 位置 < endTime 的行。
     */
    private static int findLineIndex(@NonNull List<SpotifyLine> lines, long position) {
        int low = 0;
        int high = lines.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            SpotifyLine line = lines.get(mid);
            if (position < line.startTimeMs) {
                high = mid - 1;
            } else if (position >= line.endTimeMs) {
                low = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    private void sendCurrentLine(@NonNull SpotifyLine line, @NonNull SongInfo song) {
        SuperLyricData data = new SuperLyricData()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setLyric(new SuperLyricLine(line.text, line.words, line.startTimeMs, line.endTimeMs));
        if (line.transliteratedWords != null && !line.transliteratedWords.trim().isEmpty()) {
            data.setTranslation(new SuperLyricLine(line.transliteratedWords));
        }
        sendLyric(data);
        logD(tag, "sendLyric: track=" + song.trackId + ", start=" + line.startTimeMs);
    }

    // ------------------------------ 会话头捕获 ------------------------------

    private void hookSessionHeaders() {
        try {
            Class<?> headersClass = findClass("okhttp3.Headers");
            logD(tag, "Session headers target: class=" + headersClass.getName()
                + ", classloader=" + headersClass.getClassLoader());
            hookAllConstructor(headersClass, new AbsHook() {
                @Override
                public void after() {
                    Object[] args = getArgs();
                    if (args != null && args.length > 0 && args[0] instanceof String[]) {
                        captureNamesAndValues((String[]) args[0]);
                    }
                }
            });
            logI(tag, "Session headers: okhttp3.Headers constructor hooked");
        } catch (Throwable t) {
            logW(tag, "Session headers: constructor hook unsupported", t);
        }
    }

    private void captureNamesAndValues(@NonNull String[] namesAndValues) {
        SpotifyLyricAnalysis.HeaderSnapshot headers = SpotifyLyricAnalysis.updateHeaders(namesAndValues);
        if (headers != null) resumeHeaderWaitIfReady();
    }
}
