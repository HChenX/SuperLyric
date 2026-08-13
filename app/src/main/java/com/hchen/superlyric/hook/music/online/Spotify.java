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

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private Handler mLyricHandler;

    // 播放状态（setPlaybackState 写入，轮询线程读取）：
    // 单一不可变快照整体发布，避免 state/position/speed/锚点多次 volatile 写入的中间态
    private volatile PlaybackSnapshot mPlayback = PlaybackSnapshot.INITIAL;

    // 当前歌曲与歌词快照（不可变，整体发布）：
    // song + lyric + lastShownIndex 原子可见；行推进 / 歌词写入均以 compareAndSet
    // 原子替换，快速切歌时异步拉取与切歌不产生歌词与元数据错配、无上一首残留
    private final AtomicReference<TrackSnapshot> mTrackRef = new AtomicReference<>();

    // 轮询推进：mIsRunning 防重入；mLoopToken 使重启后的旧轮询链立即失效，
    // 避免 stopLoop→startLoop 时序下出现双链并行
    private volatile boolean mIsRunning = false;
    private volatile long mLoopToken = 0L;

    // 异步拉取（缓存线程池：快速切歌时新请求不被慢的旧请求阻塞，
    // 过期结果由 applyLyrics 的 trackId 校验丢弃）
    private final ExecutorService mDownloadExecutor = Executors.newCachedThreadPool();
    private final Set<String> mDownloadingIds = ConcurrentHashMap.newKeySet();

    /** 歌曲上下文快照：音轨标识 + 标题/歌手，与歌词配对发布的元数据来源。 */
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

    /** 歌词快照：所属音轨标识 + 规整后的行列表（不可变）。 */
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

    /** 播放状态不可变快照：state / position / speed 与锚点时间一次写入。 */
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

    /** 歌曲上下文快照：当前歌曲 + 歌词（可为空）+ 已展示行索引，整体原子替换。 */
    private static final class TrackSnapshot {
        @NonNull
        final SongInfo song;
        @Nullable
        final LyricData lyric;
        final int lastShownIndex;

        TrackSnapshot(@NonNull SongInfo song, @Nullable LyricData lyric, int lastShownIndex) {
            this.song = song;
            this.lyric = lyric;
            this.lastShownIndex = lastShownIndex;
        }

        @NonNull
        TrackSnapshot withLyric(@NonNull LyricData newLyric) {
            return new TrackSnapshot(song, newLyric, -1);
        }

        @NonNull
        TrackSnapshot withShownIndex(int index) {
            return new TrackSnapshot(song, lyric, index);
        }
    }

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        // 轮询线程
        HandlerThread lyricThread = new HandlerThread("SpotifyLyricThread");
        lyricThread.start();
        mLyricHandler = new Handler(lyricThread.getLooper());

        hookPlaybackState();
        hookMetadata();
        hookSessionHeaders();

        logI(TAG, "Spotify hooks loaded (package: " + param.getPackageName() + ")");
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

        logD(TAG, "PlaybackState: state=" + mPlayback.state
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
            logD(TAG, "setMetadata: no valid track id, treat as ad/unknown, sendStop");
            sendStop();
            stopLoop();
            return;
        }

        if (current != null && id.equals(current.song.trackId)) {
            logD(TAG, "setMetadata: same track, ignore: " + id);
            return;
        }

        // 切歌：立即清空旧歌词与显示，避免残留上一首
        sendStop();
        stopLoop();

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        mTrackRef.set(new TrackSnapshot(
            new SongInfo(id, title != null ? title : "", artist != null ? artist : ""),
            null,
            -1
        ));
        logD(TAG, "Track changed: id=" + id + ", title=" + title + ", artist=" + artist);

        fetchLyricsForTrack(id);
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

    private void fetchLyricsForTrack(@NonNull String id) {
        // 独立命名空间（宿主私有缓存，与 LyricProvider 同方案）：
        // cacheDir/SuperLyric/lyric/Spotify/{locale}/{id}.json，locale 目录与 LyricProvider 一致
        String cacheKey = LyricCacheStore.currentLocaleTag() + "/" + id;

        byte[] cached = LyricCacheStore.getBytes(mAppContext, "Spotify", cacheKey);
        if (cached != null) {
            List<SpotifyLine> lines = parseOrNull(cached);
            if (lines != null) {
                logD(TAG, "Lyric cache hit for " + id + ", no network request");
                applyLyrics(id, lines);
                return;
            }
            // 坏缓存：删除并回退网络，避免被永久毒化
            logW(TAG, "Cached lyric unparseable for " + id + ", evict and refetch");
            LyricCacheStore.delete(mAppContext, "Spotify", cacheKey);
        }

        if (!mDownloadingIds.add(id)) {
            logD(TAG, "Lyric already downloading for " + id);
            return;
        }

        mDownloadExecutor.execute(() -> {
            try {
                byte[] raw = SpotifyLyricAnalysis.fetchLyric(id);
                logD(TAG, "Lyric fetched for " + id + ", bytes length=" + raw.length);
                List<SpotifyLine> lines = parseOrNull(raw);
                if (lines == null) {
                    // 非法响应不入缓存，保持空白，避免坏数据持久化
                    logW(TAG, "Lyric response unparseable for " + id + ", keep blank");
                    return;
                }
                LyricCacheStore.put(mAppContext, "Spotify", cacheKey, raw);
                logD(TAG, "Lyric cache written: Spotify/" + cacheKey + ".json");
                applyLyrics(id, lines);
            } catch (SpotifyLyricAnalysis.NoFoundLyricException e) {
                logD(TAG, "No lyric found (404) for " + id + ", keep blank");
            } catch (Exception e) {
                logE(TAG, "Failed to fetch lyric for " + id, e);
            } finally {
                mDownloadingIds.remove(id);
            }
        });
    }

    /**
     * 解析原始响应（JSON / protobuf 自动分流），无歌词或解析失败返回 {@code null}。
     */
    @Nullable
    private List<SpotifyLine> parseOrNull(byte[] raw) {
        List<SpotifyLine> lines = SpotifyLyricAnalysis.parseLyrics(raw);
        return (lines == null || lines.isEmpty()) ? null : lines;
    }

    /**
     * 原子写入歌词快照并启动行推进；
     * 仅当前音轨与请求音轨一致时才生效，过期结果直接丢弃。
     */
    private void applyLyrics(@NonNull String id, @NonNull List<SpotifyLine> lines) {
        // 竞态防护：异步拉取完成时校验当前音轨（与当前歌曲快照比对）
        TrackSnapshot current = mTrackRef.get();
        if (current == null || !id.equals(current.song.trackId)) {
            logD(TAG, "Stale lyric response for " + id
                + ", current=" + (current == null ? "null" : current.song.trackId) + ", discard");
            return;
        }

        // CAS 写入：快照已换代（切歌）时不覆盖，旧响应直接丢弃
        if (!mTrackRef.compareAndSet(current, current.withLyric(new LyricData(id, lines)))) {
            logD(TAG, "Track changed while applying lyric for " + id + ", discard");
            return;
        }
        logD(TAG, "Lyrics ready for " + id + ", lines=" + lines.size() + ", first=" + lines.get(0).text
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
            logE(TAG, "Lyric loop error", t);
        }

        if (mIsRunning && token == mLoopToken) {
            mLyricHandler.postDelayed(() -> runLoop(token), LOOP_INTERVAL_MS);
        }
    }

    /**
     * 位置插值：上次采样位置 + 速度 × 流逝时间。
     */
    private static long estimatePosition(@NonNull PlaybackSnapshot playback, long now) {
        long elapsed = now - playback.anchorTime;
        return playback.position + (long) (elapsed * playback.speed);
    }

    /**
     * 顺序查找 startTime ≤ 位置 < endTime 的行。
     */
    private static int findLineIndex(@NonNull List<SpotifyLine> lines, long position) {
        for (int i = 0; i < lines.size(); i++) {
            SpotifyLine line = lines.get(i);
            if (position >= line.startTimeMs && position < line.endTimeMs) {
                return i;
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
        logD(TAG, "sendLyric: [" + song.title + " - " + song.artist + "] " + line.text);
    }

    // ------------------------------ 会话头捕获 ------------------------------

    private void hookSessionHeaders() {
        try {
            Class<?> headersClass = findClass("okhttp3.Headers");
            logD(TAG, "Session headers target: class=" + headersClass.getName()
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
            logI(TAG, "Session headers: okhttp3.Headers constructor hooked");
        } catch (Throwable t) {
            logW(TAG, "Session headers: constructor hook unsupported", t);
        }
    }

    private void captureNamesAndValues(@NonNull String[] namesAndValues) {
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            captureHeader(namesAndValues[i], namesAndValues[i + 1]);
        }
    }

    private void captureHeader(@NonNull String name, @NonNull String value) {
        String lower = name.toLowerCase(Locale.ENGLISH);
        if (SpotifyLyricAnalysis.isKeyRequired(lower)) {
            SpotifyLyricAnalysis.setHeader(lower, value);
        }
    }
}
