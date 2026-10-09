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
package com.hchen.superlyric.publisher;

import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 播放进度追踪器与跨行增量推进器。
 * <p>
 * <b>核心机制说明：</b>
 * <ul>
 *   <li>基于播放状态（{@link PlaybackState}）与流逝物理时间外推计算高精度毫秒播放进度；</li>
 *   <li>在跨入新行时构造极轻量增量包（{@code progress}）通知观察者，杜绝重复传输全量歌词开销；</li>
 *   <li>支持多进程/系统级时基推演，解耦宿主内部私有时钟。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public final class PlaybackTracker {
    private static final String TAG = "PlaybackTracker";
    private static final long LOOP_INTERVAL_MS = 50L;

    @FunctionalInterface
    public interface OnProgressAdvanceListener {
        void onProgressAdvance(int newIndex, @NonNull SuperLyricData progress);
    }

    private final HandlerThread mThread;
    private final Handler mHandler;

    private volatile boolean mIsRunning = false;
    private volatile long mLoopToken = 0L;

    private final AtomicReference<PlaybackSnapshot> mPlaybackRef = new AtomicReference<>(PlaybackSnapshot.INITIAL);
    private final AtomicReference<SuperLyricData> mCurrentLyricRef = new AtomicReference<>();
    private final AtomicReference<String> mBoundTrackId = new AtomicReference<>();
    private volatile int mLastShownIndex = -1;
    @Nullable
    private volatile OnProgressAdvanceListener mListener;

    public PlaybackTracker() {
        this("SuperLyric-Tracker");
    }

    public PlaybackTracker(@NonNull String threadName) {
        mThread = new HandlerThread(threadName);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
    }

    public void setOnProgressAdvanceListener(@Nullable OnProgressAdvanceListener listener) {
        this.mListener = listener;
    }

    /**
     * 绑定当前曲目的全量歌词数据并重置行索引。
     */
    public void setLyrics(@Nullable SuperLyricData lyricData) {
        SuperLyricData clean = LyricSanitizer.sanitizeData(lyricData);
        mCurrentLyricRef.set(clean);
        mBoundTrackId.set(clean != null ? clean.getLyricId() : null);
        mLastShownIndex = -1;
        AndroidLog.logD(TAG, "setLyrics: " + (clean != null ? ("title=" + clean.getTitle() + ", lines=" + clean.getAllLyricsCount() + ", trackId=" + clean.getLyricId()) : "null"));
        checkLoopStatus();
    }

    /**
     * 更新播放状态快照。
     */
    public void onPlaybackStateChanged(@Nullable PlaybackState state) {
        if (state == null) return;

        PlaybackSnapshot snapshot = new PlaybackSnapshot(
            state.getState(),
            state.getPosition(),
            state.getPlaybackSpeed(),
            SystemClock.elapsedRealtime()
        );
        mPlaybackRef.set(snapshot);
        AndroidLog.logD(TAG, "onPlaybackStateChanged: state=" + state.getState() + ", pos=" + state.getPosition() + "ms, speed=" + state.getPlaybackSpeed());
        if (state.getState() != PlaybackState.STATE_PLAYING) {
            mLastShownIndex = -1;
        }
        checkLoopStatus();
    }

    /**
     * 音轨变更通知：若当前绑定的歌词不属于新音轨，立即停止追踪并彻底清空缓存歌词，杜绝切歌后残留推送。
     */
    public void onTrackChanged(@Nullable TrackContext context) {
        String newTrackId = context != null ? context.getTrackId() : null;
        String boundId = mBoundTrackId.get();
        if (boundId != null && !Objects.equals(boundId, newTrackId)) {
            AndroidLog.logD(TAG, "onTrackChanged: stopping tracker and clearing stale lyrics ('"
                + boundId + "' != '" + newTrackId + "')");
            stop();
        }
    }

    /**
     * 外部直接同步播放位置（如从宿主内部心跳获取）。
     */
    public void onPositionSynced(long positionMs) {
        PlaybackSnapshot prev = mPlaybackRef.get();
        long prevEst = estimatePosition(prev, SystemClock.elapsedRealtime());
        long drift = Math.abs(prevEst - positionMs);
        if (drift > 200) {
            AndroidLog.logD(TAG, "onPositionSynced: syncPos=" + positionMs + "ms, estimated=" + prevEst + "ms, drift=" + drift + "ms");
        }
        PlaybackSnapshot updated = new PlaybackSnapshot(
            prev.state,
            positionMs,
            prev.speed,
            SystemClock.elapsedRealtime()
        );
        mPlaybackRef.set(updated);
        checkLoopStatus();
    }

    public void onPlay(long positionMs) {
        AndroidLog.logD(TAG, "onPlay: startPos=" + positionMs + "ms");
        PlaybackSnapshot snapshot = new PlaybackSnapshot(
            PlaybackState.STATE_PLAYING,
            positionMs,
            1.0f,
            SystemClock.elapsedRealtime()
        );
        mPlaybackRef.set(snapshot);
        checkLoopStatus();
    }

    public void onPause() {
        PlaybackSnapshot prev = mPlaybackRef.get();
        long estPos = estimatePosition(prev, SystemClock.elapsedRealtime());
        AndroidLog.logD(TAG, "onPause: estPos=" + estPos + "ms");
        PlaybackSnapshot snapshot = new PlaybackSnapshot(
            PlaybackState.STATE_PAUSED,
            estPos,
            0.0f,
            SystemClock.elapsedRealtime()
        );
        mPlaybackRef.set(snapshot);
        mLastShownIndex = -1;
        checkLoopStatus();
    }

    public void onSeek(long positionMs) {
        AndroidLog.logD(TAG, "onSeek: seekTo=" + positionMs + "ms");
        onPositionSynced(positionMs);
    }

    public void stop() {
        AndroidLog.logD(TAG, "stop: tracker loop stopped and lyrics cleared");
        mIsRunning = false;
        mLastShownIndex = -1;
        mCurrentLyricRef.set(null);
        mBoundTrackId.set(null);
    }

    private synchronized void checkLoopStatus() {
        PlaybackSnapshot playback = mPlaybackRef.get();
        SuperLyricData lyric = mCurrentLyricRef.get();
        String boundId = mBoundTrackId.get();

        boolean canPlay = playback.state == PlaybackState.STATE_PLAYING
            && lyric != null
            && lyric.hasAllLyrics()
            && lyric.getAllLyricsCount() > 0
            && (boundId == null || Objects.equals(boundId, lyric.getLyricId()));

        if (canPlay) {
            if (!mIsRunning) {
                mIsRunning = true;
                final long token = ++mLoopToken;
                mHandler.post(() -> runLoop(token));
            }
        } else {
            mIsRunning = false;
        }
    }

    private void runLoop(long token) {
        if (!mIsRunning || token != mLoopToken) return;

        try {
            PlaybackSnapshot playback = mPlaybackRef.get();
            SuperLyricData lyric = mCurrentLyricRef.get();
            String boundId = mBoundTrackId.get();

            if (playback.state != PlaybackState.STATE_PLAYING || lyric == null || !lyric.hasAllLyrics()
                || (boundId != null && !Objects.equals(boundId, lyric.getLyricId()))) {
                mIsRunning = false;
                return;
            }

            long estimatedPosition = estimatePosition(playback, SystemClock.elapsedRealtime());
            SuperLyricLine[] allLines = lyric.getAllLyrics();
            int newIndex = findLineIndex(allLines, estimatedPosition);

            if (newIndex >= 0 && newIndex < allLines.length && newIndex != mLastShownIndex) {
                mLastShownIndex = newIndex;
                SuperLyricLine currentLine = allLines[newIndex];

                // 构造极轻量增量包，不传输 allLyrics
                SuperLyricData progress = new SuperLyricData()
                    .setTitle(lyric.getTitle())
                    .setArtist(lyric.getArtist())
                    .setAlbum(lyric.getAlbum())
                    .setLyricId(lyric.getLyricId())
                    .setCurrentLyricIndex(newIndex)
                    .setPosition(estimatedPosition)
                    .setDuration(lyric.getDuration())
                    .setLyric(currentLine);

                if (currentLine.hasTranslation()) {
                    progress.setTranslation(currentLine.getTranslationLine());
                }
                if (currentLine.hasSecondary()) {
                    progress.setSecondary(currentLine.getSecondaryLine());
                }

                AndroidLog.logD(TAG, "Advance line [" + newIndex + "/" + allLines.length + "]: "
                    + "pos=" + estimatedPosition + "ms, "
                    + "span=[" + currentLine.getStartTime() + ".." + currentLine.getEndTime() + "ms], "
                    + "text=\"" + currentLine.getText() + "\""
                    + (currentLine.hasTranslation() ? (", trans=\"" + currentLine.getTranslation() + "\"") : "")
                    + (currentLine.hasSecondary() ? (", sec=\"" + currentLine.getSecondary() + "\"") : "")
                    + (currentLine.getWords() != null && currentLine.getWords().length > 0 ? (", wordsCount=" + currentLine.getWords().length) : ""));

                SuperLyricData cleanProgress = LyricSanitizer.sanitizeData(progress);
                if (cleanProgress == null) return;

                OnProgressAdvanceListener listener = mListener;
                if (listener != null) {
                    listener.onProgressAdvance(newIndex, cleanProgress);
                } else {
                    AbsPublisher.publishLyricProgress(cleanProgress);
                }
            }
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Error in playback tracker loop", t);
        }

        if (mIsRunning && token == mLoopToken) {
            mHandler.postDelayed(() -> runLoop(token), LOOP_INTERVAL_MS);
        }
    }

    private static long estimatePosition(@NonNull PlaybackSnapshot playback, long now) {
        long base = Math.max(0L, playback.position);
        if (!Float.isFinite(playback.speed) || playback.speed < 0f || now <= playback.anchorTime) {
            return base;
        }
        long elapsed = now - playback.anchorTime;
        double delta = elapsed * (double) playback.speed;
        if (!Double.isFinite(delta) || delta >= Long.MAX_VALUE) return Long.MAX_VALUE;
        try {
            return Math.max(0L, Math.addExact(base, (long) delta));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static int findLineIndex(@NonNull SuperLyricLine[] lines, long position) {
        int low = 0;
        int high = lines.length - 1;
        int matched = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            SuperLyricLine line = lines[mid];
            if (line.getStartTime() <= position) {
                matched = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        if (matched >= 0 && matched < lines.length) {
            SuperLyricLine line = lines[matched];
            // 若包含明确结束时间且已跨过该行范围，则返回匹配行或等待下一行
            if (line.getEndTime() > 0 && position >= line.getEndTime()) {
                // 如果恰好在两行间奏空隙，保留当前行或推进到下一行
                return matched;
            }
            return matched;
        }
        return -1;
    }

    private static final class PlaybackSnapshot {
        static final PlaybackSnapshot INITIAL = new PlaybackSnapshot(PlaybackState.STATE_NONE, 0L, 0f, 0L);

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
}
