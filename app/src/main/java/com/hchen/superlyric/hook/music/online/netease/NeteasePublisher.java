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

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyric.patches.netease.NeteaseLogicHijacking;
import com.hchen.superlyric.utils.LyricCacheStore;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * 网易云 / 荣耀音乐共享的歌词网络路径基类。
 * <p>
 * hook 系统媒体会话（{@code MediaSession.setMetadata} / {@code setPlaybackState}），
 * 以 42ms 位置插值推进当前行；eapi 一次拉取全量歌词（原词 / 翻译 / 逐字 / 音译 /
 * 纯音乐标志），翻译与音译二选一进翻译槽位。纯音乐 / 无歌词静默，暂停保留末行、
 * 停止清空；请求失败保持空白并按指数退避有限次曲内重试。发布来源决策由纯状态机
 * {@link LyricSourceMachine} 决定，本类只做「事件 → 转移 → 副作用」映射。
 * <p>
 * 防热更新（Tinker 禁用 + wrapper bypassPackProtection）由 {@link NeteaseLogicHijacking}
 * 统一安装。
 * <p>
 * Inspired from tomakino/LyricProvider/163-music.
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
public abstract class NeteasePublisher extends AbsPublisher {
    private static final long LOOP_INTERVAL_MS = 42L;

    /**
     * App 内歌词显示设置：0=翻译，1=罗马音，-1=无（只发原词）。
     */
    private static final int LYRIC_SETTING_TRANSLATION = 0;
    private static final int LYRIC_SETTING_ROMA = 1;
    private static final int LYRIC_SETTING_OFF = -1;

    /**
     * 启动早期设置查找失败后的定时退避重试次数上限（首次切歌时另有惰性重试兜底）。
     */
    private static final int LYRIC_SETTING_RETRY_MAX = 6;

    /**
     * 曲内网络请求失败后的有限重试次数上限（指数退避，达到后最终放弃直到下次切歌）。
     */
    private static final int MAX_LYRIC_RETRIES = 3;
    private Context mAppContext;

    // 播放状态（setPlaybackState 写入，轮询线程读取）：
    // 单一不可变快照整体发布，避免 state/position/speed/锚点多次 volatile 写入的中间态
    private volatile PlaybackSnapshot mPlayback = PlaybackSnapshot.INITIAL;

    // 当前歌曲与歌词快照（不可变，整体发布）：
    // song + lyric + lastShownIndex 原子可见；歌词写入 / 清空 / 行推进均以
    // compareAndSet 原子替换，避免「读-改-写」覆盖并发更新的新快照
    private final AtomicReference<TrackSnapshot> mTrackRef = new AtomicReference<>();
    private final AtomicLong mTrackGeneration = new AtomicLong();

    // 42ms 行推进：mIsRunning 防重入；mLoopToken 使 stopLoop → startLoop 时序下旧轮询链立即失效
    private HandlerThread mLyricThread;
    private Handler mLyricHandler;
    private boolean mHooksInitialized;
    private volatile boolean mIsRunning = false;
    private volatile long mLoopToken = 0L;

    // App 内歌词显示设置联动（DexKit 偏好工厂；启动早期失败会延迟退避重试 + 首次切歌惰性重试，
    // 成功前按 -1 只发原词，成功后注册变更监听即时生效）
    private volatile int mLyricSetting = LYRIC_SETTING_OFF;
    private volatile boolean mLyricSettingLinked = false;
    private SharedPreferences mPreference;
    private SharedPreferences.OnSharedPreferenceChangeListener mPreferenceListener;
    private final Object mLyricSettingLock = new Object();
    private volatile long mLyricSettingRetryToken = 0L;

    // 歌词来源状态机（不可变快照，纯决策）：当前发布来源由事件驱动转移，
    // dispatchSourceEvent 是本类唯一的来源状态写入点，副作用显式映射
    private final Object mSourceStateLock = new Object();
    private volatile LyricSourceMachine.State mSourceState =
        LyricSourceMachine.initial(MAX_LYRIC_RETRIES);

    // 曲内有限重试：mRetryTrackId 标记当前 generation 的重试音轨；mRetryToken 使
    // 切歌 / 停止 / 暂停后的旧任务失效；锁内每个 trackId + generation 仅一个 pending Runnable
    private final Object mRetryLock = new Object();
    private volatile long mRetryTrackId = -1L;
    private long mRetryGeneration = -1L;
    private final AtomicLong mRetryToken = new AtomicLong();
    @Nullable
    private Runnable mPendingRetry;

    // 异步拉取（缓存线程池：快速切歌时新请求不被慢的旧请求阻塞）
    private final ThreadPoolExecutor mDownloadExecutor = new ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final Set<String> mDownloadingIds = ConcurrentHashMap.newKeySet();

    /**
     * 歌曲上下文快照：数字音轨标识 + 标题/歌手/专辑/时长。
     */
    protected static final class SongInfo {
        final long id;
        @NonNull
        final String title;
        @NonNull
        final String artist;
        @NonNull
        final String album;
        final long duration;

        SongInfo(long id, @NonNull String title, @NonNull String artist, @NonNull String album, long duration) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.duration = duration;
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
        final LyricSnapshot lyric;
        final int lastShownIndex;

        TrackSnapshot(long generation, @NonNull SongInfo song, @Nullable LyricSnapshot lyric,
                      int lastShownIndex) {
            this.generation = generation;
            this.song = song;
            this.lyric = lyric;
            this.lastShownIndex = lastShownIndex;
        }

        @NonNull
        TrackSnapshot withLyric(@NonNull LyricSnapshot newLyric) {
            return new TrackSnapshot(generation, song, newLyric, -1);
        }

        @NonNull
        TrackSnapshot clearLyric() {
            return new TrackSnapshot(generation, song, null, -1);
        }

        @NonNull
        TrackSnapshot withShownIndex(int index) {
            return new TrackSnapshot(generation, song, lyric, index);
        }
    }

    /**
     * 歌词快照：所属音轨标识 + 解析后的歌词数据，供行推进前与歌曲快照配对校验。
     */
    private static final class LyricSnapshot {
        final long id;
        @NonNull
        final NeteaseLyricAnalysis.LyricData data;

        LyricSnapshot(long id, @NonNull NeteaseLyricAnalysis.LyricData data) {
            this.id = id;
            this.data = data;
        }
    }

    @Override
    protected synchronized void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        if (mHooksInitialized) {
            logD(tag, "Netease network hooks already initialized for " + param.getPackageName());
            return;
        }
        mLyricThread = new HandlerThread("NeteasePublisherThread");
        mLyricThread.start();
        mLyricHandler = new Handler(mLyricThread.getLooper());

        NeteaseLogicHijacking.bypassPackProtection();
        hookMediaSession();
        hookPlaybackState();
        mHooksInitialized = true;
        logI(tag, "Netease network path hooks loaded (package: " + param.getPackageName() + ")");
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        mAppContext = context.getApplicationContext();

        linkLyricSetting();
        if (!mLyricSettingLinked) {
            scheduleLyricSettingRetry(1);
        }
    }

    // ------------------------------ App 内歌词显示设置联动 ------------------------------

    /**
     * 链接 App 内歌词显示设置：DexKit 查找静态偏好工厂并注册变更监听。
     * <p>
     * 启动早期（Application.attach 期间）偏好工厂可能因 App Context 未就绪而失败；
     * 失败不永久降级——由 {@link #scheduleLyricSettingRetry(int)} 定时退避重试，
     * 并在首次切歌时惰性重试兜底。链接成功前按 -1 只发原词，成功后即时生效。
     */
    private void linkLyricSetting() {
        if (mLyricSettingLinked) return;
        synchronized (mLyricSettingLock) {
            if (mLyricSettingLinked) return;
            try {
                Method method = DexkitCache.findMember("netease$lyric_setting", new IDexkit<MethodData>() {
                    @NonNull
                    @Override
                    public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                        return bridge.findClass(FindClass.create()
                                .searchPackages("com.netease.cloudmusic.utils")
                                .matcher(ClassMatcher.create()
                                    .usingStrings("com.netease.cloudmusic.preferences", "multiprocess_settings")
                                )
                            ).single()
                            .findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                    .returnType(SharedPreferences.class)
                                    .paramCount(0)
                                    .modifiers(Modifier.PUBLIC | Modifier.STATIC)
                                    .usingStrings("com.netease.cloudmusic.preferences")
                                )
                            ).single();
                    }
                });
                SharedPreferences prefs = (SharedPreferences) method.invoke(null);
                int value = prefs.getInt("showLyricSetting", LYRIC_SETTING_OFF);

                if (mPreferenceListener == null) {
                    mPreferenceListener = (sharedPreferences, key) -> {
                        if (TextUtils.equals("showLyricSetting", key)) {
                            mLyricSetting = sharedPreferences.getInt(key, LYRIC_SETTING_OFF);
                            logD(tag, "Lyric display setting changed: showLyricSetting=" + mLyricSetting);
                        }
                    };
                }
                if (mPreference != null && mPreference != prefs) {
                    mPreference.unregisterOnSharedPreferenceChangeListener(mPreferenceListener);
                }
                prefs.registerOnSharedPreferenceChangeListener(mPreferenceListener);
                mPreference = prefs;
                mLyricSetting = value;
                mLyricSettingLinked = true;
                // 失效未执行的定时重试：成功链接后不再重复尝试
                mLyricSettingRetryToken++;
                logI(tag, "Lyric display setting linked: showLyricSetting=" + mLyricSetting);
                logD(tag, "Lyric display setting diagnostic: lookup_failed=0, showLyricSetting=" + mLyricSetting);
            } catch (Throwable t) {
                // 启动早期 Context 未就绪等瞬时失败：保持只发原词并安排重试，不永久降级
                mLyricSetting = LYRIC_SETTING_OFF;
                logW(tag, "Lyric display setting lookup failed, keep original-only, will retry", t);
            }
        }
    }

    /**
     * 安排一次定时退避重试：指数退避，达到次数上限后停止（首次切歌的惰性重试仍会兜底）。
     */
    private void scheduleLyricSettingRetry(int attempt) {
        if (mLyricSettingLinked || attempt > LYRIC_SETTING_RETRY_MAX) return;

        long delayMs = Math.min(1000L << (attempt - 1), 30000L);
        final long token = mLyricSettingRetryToken;
        mLyricHandler.postDelayed(() -> {
            if (mLyricSettingLinked || token != mLyricSettingRetryToken) return;
            linkLyricSetting();
            if (!mLyricSettingLinked) {
                scheduleLyricSettingRetry(attempt + 1);
            }
        }, delayMs);
    }

    // ------------------------------ 网络数据路径（MediaSession） ------------------------------

    private void hookMediaSession() {
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

    private void onPlaybackStateChanged(@NonNull PlaybackState state) {
        mPlayback = new PlaybackSnapshot(
            state.getState(),
            state.getPosition(),
            state.getPlaybackSpeed(),
            SystemClock.elapsedRealtime()
        );

        logD(tag, "PlaybackState: state=" + mPlayback.state + ", position=" + mPlayback.position + ", speed=" + mPlayback.speed);

        switch (mPlayback.state) {
            case PlaybackState.STATE_PLAYING:
                dispatchSourceEvent(LyricSourceMachine.Event.playing());
                TrackSnapshot current = mTrackRef.get();
                long currentId = current == null ? -1L : current.song.id;
                if (current != null && LyricSourceMachine.shouldRetry(mSourceState)
                    && !mDownloadingIds.contains(taskKey(currentId, current.generation))) {
                    scheduleLyricRetry(currentId, current.generation);
                }
                startLoop();
                break;
            case PlaybackState.STATE_STOPPED:
                sendStop();
                stopLoop();
                cancelLyricRetry(currentSongId());
                dispatchSourceEvent(LyricSourceMachine.Event.stopped());
                break;
            case PlaybackState.STATE_BUFFERING:
                // BUFFERING 忽略：保留末行，不停止、不发布
                break;
            default:
                // 暂停等：停表保留最后一行；取消曲内重试，不残留任务
                stopLoop();
                cancelLyricRetry(currentSongId());
                break;
        }
    }

    private void onMetadataChanged(@NonNull MediaMetadata metadata) {
        long id = extractSongId(metadata);
        if (id <= 0L) {
            NeteaseLyricAnalysis.cancelExcept(-1L);
            logD(tag, "setMetadata: no valid numeric id, treat as ad/unknown, sendStop");
            // 广告 / 非歌曲：清空上下文与歌词快照，防止旧歌曲晚到响应或残留行误发
            cancelLyricRetry(currentSongId());
            dispatchSourceEvent(LyricSourceMachine.Event.adOrUnknown());
            mTrackRef.set(null);
            sendStop();
            stopLoop();
            return;
        }

        SongInfo song = currentSong();
        if (song != null && song.id == id) {
            logD(tag, "setMetadata: same track, ignore: " + id);
            return;
        }

        NeteaseLyricAnalysis.cancelExcept(id);
        // 切歌：立即清空旧歌词与显示；下次切歌重新尝试网络路径
        cancelLyricRetry(currentSongId());
        sendStop();
        stopLoop();

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long generation = mTrackGeneration.incrementAndGet();
        synchronized (mSourceStateLock) {
            mTrackRef.set(new TrackSnapshot(
                generation,
                new SongInfo(
                    id,
                    title != null ? title : "",
                    artist != null ? artist : "",
                    album != null ? album : "",
                    duration
                ),
                null,
                -1
            ));
            dispatchSourceEvent(LyricSourceMachine.Event.trackChanged(id));
        }
        logD(tag, "Track changed: id=" + id + ", retry network path");

        // 首次切歌惰性重试：即便定时重试全部失败，第一首歌前仍有机会完成设置联动
        linkLyricSetting();
        fetchLyricsForTrack(id, generation);
    }

    /**
     * 从 MEDIA_ID 解析网易云数字音轨标识；解析不出 → 广告 / 非歌曲，返回 -1。
     */
    private long extractSongId(@NonNull MediaMetadata metadata) {
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (mediaId == null) return -1L;
        try {
            return Long.parseLong(mediaId.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // ------------------------------ 42ms 行推进与发布 ------------------------------

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
            if (playback == null || playback.state != PlaybackState.STATE_PLAYING) {
                mIsRunning = false;
                return;
            }

            // 同一轮迭代取一致快照：歌词与歌曲上下文配对校验后再发送
            TrackSnapshot current = mTrackRef.get();
            if (current == null || current.lyric == null || !current.lyric.data.hasLyrics()) {
                mIsRunning = false;
                return;
            }
            NeteaseLyricAnalysis.LyricData data = current.lyric.data;

            long estimated = estimatePosition(playback, SystemClock.elapsedRealtime());
            int index = findLineIndex(data.lines, estimated);
            if (index >= 0 && index != current.lastShownIndex) {
                NeteaseLyricAnalysis.LyricLineData line = data.lines.get(index);
                // 播放中空文本行跳过：不发送、不计入已展示索引
                if (!line.text.isBlank()) {
                    // CAS 原子推进：切歌 / 重新拉取后旧轮询链的替换失败，立即放弃本轮
                    TrackSnapshot updated = current.withShownIndex(index);
                    if (mTrackRef.compareAndSet(current, updated)) {
                        sendCurrentLine(line, current.song);
                    }
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
     * 位置插值：上次采样位置 + 速度 × 流逝时间（灭屏不停表）。
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
     * 顺序查找 start ≤ 位置 < end 的行。
     */
    private static int findLineIndex(@NonNull List<NeteaseLyricAnalysis.LyricLineData> lines, long position) {
        int low = 0;
        int high = lines.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).start <= position) low = mid + 1;
            else high = mid;
        }
        for (int i = low - 1; i >= 0; i--) {
            NeteaseLyricAnalysis.LyricLineData line = lines.get(i);
            if (position >= line.start && position < line.end) return i;
        }
        return -1;
    }

    private void sendCurrentLine(@NonNull NeteaseLyricAnalysis.LyricLineData line, @NonNull SongInfo song) {
        // 互斥发布：仅状态机判定为网络来源时发布（无来源时不发布）
        if (!LyricSourceMachine.mayPublishNetwork(mSourceState)) return;

        SuperLyricData data = new SuperLyricData()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbum(song.album)
            .setLyric(new SuperLyricLine(line.text, line.words, line.start, line.end));

        String translationSlot = selectTranslationSlot(line);
        if (!TextUtils.isEmpty(translationSlot)) {
            data.setTranslation(new SuperLyricLine(translationSlot));
        }

        sendLyric(data);
        logD(tag, "sendLyric: track=" + song.id + ", start=" + line.start);
    }

    /**
     * 翻译槽位二选一：跟随 App 内设置；-1/未设置/查找失败 → 只发原词。
     */
    @Nullable
    private String selectTranslationSlot(@NonNull NeteaseLyricAnalysis.LyricLineData line) {
        int setting = mLyricSetting;
        if (setting == LYRIC_SETTING_TRANSLATION) return line.translation;
        if (setting == LYRIC_SETTING_ROMA) return line.roma;
        return null;
    }

    // ------------------------------ 拉取与缓存 ------------------------------

    private static String taskKey(long id, long generation) {
        return id + "#" + generation;
    }

    private boolean isCurrentTrack(long id, long generation) {
        TrackSnapshot current = mTrackRef.get();
        return current != null && current.generation == generation && current.song.id == id;
    }

    private void fetchLyricsForTrack(long id, long generation) {
        String taskKey = taskKey(id, generation);
        if (!mDownloadingIds.add(taskKey)) {
            logD(tag, "Lyric already loading for " + id);
            return;
        }
        try {
            mDownloadExecutor.execute(() -> loadLyricsForTrack(id, generation, taskKey));
        } catch (RejectedExecutionException e) {
            mDownloadingIds.remove(taskKey);
            handleRetryableFailure(id, generation, "Lyric worker queue full", e);
        }
    }

    private void loadLyricsForTrack(long id, long generation, @NonNull String taskKey) {
        String cacheProvider = lyricCacheProvider();
        String cacheKey = String.valueOf(id);
        try {
            if (!isCurrentTrack(id, generation)) return;

            String cached = LyricCacheStore.get(mAppContext, cacheProvider, cacheKey);
            if (cached != null) {
                NeteaseLyricAnalysis.LyricData cachedData = NeteaseLyricAnalysis.parseLyrics(cached);
                if (cachedData.hasLyrics()) {
                    logD(tag, "Lyric cache hit for " + id + ", no network request");
                    applyLyrics(id, generation, cachedData);
                    return;
                }
                LyricCacheStore.delete(mAppContext, cacheProvider, cacheKey);
            }

            String json = NeteaseLyricAnalysis.fetchLyricJson(id);
            if (!isCurrentTrack(id, generation)) return;
            NeteaseLyricAnalysis.LyricData data = NeteaseLyricAnalysis.parseLyrics(json);
            switch (data.type) {
                case READY:
                    if (applyLyrics(id, generation, data)) {
                        LyricCacheStore.put(mAppContext, cacheProvider, cacheKey, json);
                    }
                    break;
                case EMPTY:
                case PURE_MUSIC:
                    applyLyrics(id, generation, data);
                    break;
                case MALFORMED:
                    handleRetryableFailure(id, generation, "Malformed lyric response", null);
                    break;
                case API_ERROR:
                    handleRetryableFailure(id, generation, "Lyric API error code=" + data.code, null);
                    break;
            }
        } catch (NeteaseLyricAnalysis.OversizeException e) {
            finishEmpty(id, generation, "Lyric response exceeds budget for " + id);
        } catch (NeteaseLyricAnalysis.HttpStatusException e) {
            if (e.retryable) handleRetryableFailure(id, generation, "Retryable HTTP " + e.code, e);
            else finishEmpty(id, generation, "Non-retryable HTTP " + e.code + " for " + id);
        } catch (Exception e) {
            handleRetryableFailure(id, generation, "Failed to fetch lyric", e);
        } finally {
            mDownloadingIds.remove(taskKey);
        }
    }

    private void handleRetryableFailure(long id, long generation, @NonNull String message,
                                        @Nullable Throwable error) {
        synchronized (mSourceStateLock) {
            if (!isCurrentTrack(id, generation)) return;
            if (error == null) logW(tag, message + " for " + id);
            else logE(tag, message + " for " + id, error);
            dispatchSourceEvent(LyricSourceMachine.Event.fetchFailed(id));
        }
        scheduleLyricRetry(id, generation);
    }

    private void finishEmpty(long id, long generation, @NonNull String message) {
        synchronized (mSourceStateLock) {
            TrackSnapshot current = mTrackRef.get();
            if (current == null || current.generation != generation || current.song.id != id)
                return;
            if (!mTrackRef.compareAndSet(current, current.clearLyric())) return;
            clearRetryFor(id, generation);
            dispatchSourceEvent(LyricSourceMachine.Event.fetchEmpty(id));
        }
        logD(tag, message);
    }

    /**
     * 安排曲内有限重试：按状态机失败次数指数退避；失败次数达上限则最终放弃（保持空白）。
     * 所有调度经 {@code mLyricHandler}，{@code mRetryToken} 使切歌 / 停止 / 暂停后的
     * 旧任务立即失效，不残留。
     */
    private void scheduleLyricRetry(long id, long generation) {
        if (!isCurrentTrack(id, generation)) return;
        LyricSourceMachine.State state = mSourceState;
        if (state.trackId != id
            || state.contract != LyricSourceMachine.Contract.WAITING
            || state.failedAttempts <= 0
            || state.failedAttempts >= state.maxRetries) {
            clearRetryFor(id, generation);
            if (state.trackId == id && state.failedAttempts >= state.maxRetries) {
                logW(tag, "Lyric retry limit reached; keep blank until next track");
            }
            return;
        }
        // 暂停 / 停止等非播放状态下不安排新重试（与取消语义一致，不残留任务）
        if (mPlayback == null || mPlayback.state != PlaybackState.STATE_PLAYING) return;

        long delay = LyricSourceMachine.retryDelayMs(state.failedAttempts);
        synchronized (mRetryLock) {
            LyricSourceMachine.State currentState = mSourceState;
            PlaybackSnapshot playback = mPlayback;
            if (!isCurrentTrack(id, generation)
                || currentState.trackId != id
                || currentState.contract != LyricSourceMachine.Contract.WAITING
                || currentState.failedAttempts <= 0
                || currentState.failedAttempts >= currentState.maxRetries
                || playback == null
                || playback.state != PlaybackState.STATE_PLAYING) {
                return;
            }

            final long token = mRetryToken.get();
            if (mPendingRetry != null
                && mRetryTrackId == id
                && mRetryGeneration == generation) {
                return;
            }
            if (mPendingRetry != null) {
                mLyricHandler.removeCallbacks(mPendingRetry);
                mPendingRetry = null;
            }

            mRetryTrackId = id;
            mRetryGeneration = generation;
            Runnable retry = new Runnable() {
                @Override
                public void run() {
                    synchronized (mRetryLock) {
                        if (mPendingRetry != this) return;
                        // 执行前清引用，允许本次请求失败后安排下一次重试
                        mPendingRetry = null;
                    }
                    if (token != mRetryToken.get() || !isCurrentTrack(id, generation)) return;

                    LyricSourceMachine.State executingState = mSourceState;
                    PlaybackSnapshot executingPlayback = mPlayback;
                    if (executingState.trackId != id
                        || executingState.contract != LyricSourceMachine.Contract.WAITING
                        || executingState.failedAttempts <= 0
                        || executingState.failedAttempts >= executingState.maxRetries
                        || executingPlayback == null
                        || executingPlayback.state != PlaybackState.STATE_PLAYING) {
                        clearRetryFor(id, generation);
                        return;
                    }
                    fetchLyricsForTrack(id, generation);
                }
            };
            mPendingRetry = retry;
            mLyricHandler.postDelayed(retry, delay);
        }
    }

    /**
     * 取消待执行重试（切歌 / 广告 / 停止 / 暂停）：移除回调并使旧 token 失效。
     */
    private void cancelLyricRetry(long id) {
        synchronized (mRetryLock) {
            if (mPendingRetry != null) {
                mLyricHandler.removeCallbacks(mPendingRetry);
                mPendingRetry = null;
            }
            mRetryTrackId = -1L;
            mRetryGeneration = -1L;
            // 无条件递增：即使失败回调与取消并发，也失效所有旧调度和执行中任务
            mRetryToken.incrementAndGet();
        }
    }

    /**
     * 清除指定音轨代次的重试标记并使旧任务失效（成功 / 空结果 / 放弃共用）。
     */
    private void clearRetryFor(long id, long generation) {
        synchronized (mRetryLock) {
            if (mRetryTrackId != id || mRetryGeneration != generation) return;
            if (mPendingRetry != null) {
                mLyricHandler.removeCallbacks(mPendingRetry);
                mPendingRetry = null;
            }
            mRetryTrackId = -1L;
            mRetryGeneration = -1L;
            mRetryToken.incrementAndGet();
        }
    }

    private boolean isRetryFor(long id, long generation) {
        synchronized (mRetryLock) {
            return mRetryTrackId == id && mRetryGeneration == generation;
        }
    }

    /**
     * 当前歌曲音轨标识；广告 / 未初始化时返回 -1。
     */
    private long currentSongId() {
        SongInfo song = currentSong();
        return song == null ? -1L : song.id;
    }

    /**
     * 将事件送入歌词来源状态机并应用副作用（来源状态唯一写入点）。
     * <p>
     * 决策本身在 {@link LyricSourceMachine#transition(LyricSourceMachine.State, LyricSourceMachine.Event)}，为纯函数；
     * 本方法只负责「新旧状态差异 → 显式副作用」的映射：
     * 切歌清空来源与网络快照；广告清空来源、契约与网络快照；请求失败清空网络快照（保持空白等待重试）。
     */
    private void dispatchSourceEvent(@NonNull LyricSourceMachine.Event event) {
        synchronized (mSourceStateLock) {
            LyricSourceMachine.State oldState = mSourceState;
            LyricSourceMachine.State nextState = LyricSourceMachine.transition(oldState, event);
            if (nextState == oldState) return;

            if (event.type == LyricSourceMachine.EventType.TRACK_CHANGED
                || event.type == LyricSourceMachine.EventType.AD_OR_UNKNOWN
                || event.type == LyricSourceMachine.EventType.FETCH_FAILED) {
                // 切歌 / 广告：清空来源、契约与网络快照；
                // 请求失败：清空网络快照，保持空白等待曲内重试
                clearLyricSnapshot();
            }

            mSourceState = nextState;
        }
    }

    /**
     * 当前歌曲上下文；广告 / 未初始化时返回 {@code null}。
     */
    @Nullable
    protected SongInfo currentSong() {
        TrackSnapshot current = mTrackRef.get();
        return current == null ? null : current.song;
    }

    /**
     * 清空当前网络路径歌词快照与已展示行索引。
     */
    protected void clearLyricSnapshot() {
        TrackSnapshot current = mTrackRef.get();
        if (current != null) {
            // CAS：快照已换代时不覆盖新快照，由新状态机事件负责清空
            mTrackRef.compareAndSet(current, current.clearLyric());
        }
    }

    /**
     * 磁盘缓存命名空间：网易云为 {@code Netease}；荣耀音乐为 {@code Hihonor}。
     */
    protected abstract String lyricCacheProvider();

    /**
     * 应用歌词：解析并原子写入快照、走状态机事件；返回歌词是否已就绪并可用
     * （纯音乐 / 无歌词 / 过期响应返回 {@code false}，调用方据此决定是否写缓存）。
     */
    private boolean applyLyrics(long id, long generation,
                                @NonNull NeteaseLyricAnalysis.LyricData data) {
        synchronized (mSourceStateLock) {
            return applyLyricsLocked(id, generation, data);
        }
    }

    private boolean applyLyricsLocked(long id, long generation,
                                      @NonNull NeteaseLyricAnalysis.LyricData data) {
        TrackSnapshot current = mTrackRef.get();
        if (current == null || current.generation != generation || current.song.id != id) {
            logD(tag, "Stale lyric response for " + id + ", discard");
            return false;
        }

        if (data.type == NeteaseLyricAnalysis.ResultType.PURE_MUSIC || !data.hasLyrics()) {
            if (!mTrackRef.compareAndSet(current, current.clearLyric())) return false;
            clearRetryFor(id, generation);
            dispatchSourceEvent(LyricSourceMachine.Event.fetchEmpty(id));
            logD(tag, data.type == NeteaseLyricAnalysis.ResultType.PURE_MUSIC
                ? "Pure music flag for " + id + ", keep blank"
                : "No lyric lines for " + id + " (code=" + data.code + "), keep blank");
            return false;
        }

        boolean retrySuccess = isRetryFor(id, generation);
        int failedBefore = mSourceState.failedAttempts;
        if (!mTrackRef.compareAndSet(current, current.withLyric(new LyricSnapshot(id, data)))) {
            logD(tag, "Track changed while applying lyric for " + id + ", discard");
            return false;
        }
        if (retrySuccess) clearRetryFor(id, generation);
        dispatchSourceEvent(retrySuccess
            ? LyricSourceMachine.Event.retrySucceeded(id)
            : LyricSourceMachine.Event.fetchSucceeded(id));

        long wordLines = data.lines.stream().filter(line -> line.words != null).count();
        long translateLines = data.lines.stream().filter(line -> line.translation != null).count();
        long romaLines = data.lines.stream().filter(line -> line.roma != null).count();
        logD(tag, "Lyrics ready for " + id + ", lines=" + data.lines.size()
            + ", wordLines=" + wordLines + ", translateLines=" + translateLines
            + ", romaLines=" + romaLines);

        if (retrySuccess) {
            logI(tag, "Lyric retry succeeded for " + id + " after " + failedBefore
                + " failed attempt(s), switched back to network path");
        }

        PlaybackSnapshot playback = mPlayback;
        if (playback != null && playback.state == PlaybackState.STATE_PLAYING) {
            startLoop();
        }
        return true;
    }
}
