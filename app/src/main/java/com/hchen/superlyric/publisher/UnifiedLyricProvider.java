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

import android.media.MediaMetadata;
import android.media.session.PlaybackState;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.engine.ILegacyLyricEngine;
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;

import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 统一音乐歌词提供者抽象基类。
 * <p>
 * 整合切歌感知、双轨协同状态机 (LyricOrchestrator)、高精度播放进度追踪 (PlaybackTracker)，
 * 供各音乐应用（网易云、QQ音乐、酷狗、Spotify等）统一继承实现。
 *
 * @author 焕晨HChen
 */
public abstract class UnifiedLyricProvider extends AbsPublisher {
    protected final PlaybackTracker mTracker = new PlaybackTracker();
    protected LyricOrchestrator mOrchestrator;
    protected final AtomicLong mTrackGeneration = new AtomicLong(0);

    @NonNull
    public abstract ProviderCapability capability();

    @Nullable
    protected abstract IHookLyricEngine createHookEngine();

    @Nullable
    protected INetworkLyricEngine createNetworkEngine() {
        return null;
    }

    @Nullable
    protected ILegacyLyricEngine createLegacyEngine() {
        return null;
    }

    @CallSuper
    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);

        IHookLyricEngine hookEngine = createHookEngine();
        INetworkLyricEngine networkEngine = createNetworkEngine();
        ILegacyLyricEngine legacyEngine = createLegacyEngine();

        mOrchestrator = new LyricOrchestrator(hookEngine, networkEngine, legacyEngine, mTracker);

        if (hookEngine != null) {
            hookEngine.initHooks();
        }
        if (networkEngine != null) {
            networkEngine.initEngine();
        }

        hookMediaSession();
        logI(tag, "UnifiedLyricProvider initialized for: " + param.getPackageName() + ", cap=" + capability());
    }

    /**
     * 默认通过 Hook 系统的 MediaSession 捕获基础元数据与播放状态。
     */
    protected void hookMediaSession() {
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

    protected void onMetadataChanged(@NonNull MediaMetadata metadata) {
        String trackId = extractTrackId(metadata);
        if (trackId == null || trackId.isEmpty()) {
            logD(tag, "MediaSession metadata cleared or empty trackId, stopping playback");
            mOrchestrator.onPlaybackStopped();
            return;
        }

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        logD(tag, "MediaSession onMetadataChanged: trackId=" + trackId
            + ", title=" + title
            + ", artist=" + artist
            + ", album=" + album
            + ", duration=" + duration + "ms");

        long gen = mTrackGeneration.incrementAndGet();
        TrackContext context = new TrackContext(gen, trackId, title, artist, album, duration);
        mOrchestrator.onTrackChanged(context);
    }

    protected void onPlaybackStateChanged(@NonNull PlaybackState state) {
        logD(tag, "MediaSession onPlaybackStateChanged: state=" + stateToString(state.getState())
            + ", pos=" + state.getPosition() + "ms"
            + ", speed=" + state.getPlaybackSpeed());
        mTracker.onPlaybackStateChanged(state);
        if (state.getState() == PlaybackState.STATE_STOPPED) {
            mOrchestrator.onPlaybackStopped();
        }
    }

    private static String stateToString(int state) {
        return switch (state) {
            case PlaybackState.STATE_NONE -> "NONE";
            case PlaybackState.STATE_STOPPED -> "STOPPED";
            case PlaybackState.STATE_PAUSED -> "PAUSED";
            case PlaybackState.STATE_PLAYING -> "PLAYING";
            case PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING";
            case PlaybackState.STATE_REWINDING -> "REWINDING";
            case PlaybackState.STATE_BUFFERING -> "BUFFERING";
            case PlaybackState.STATE_ERROR -> "ERROR";
            case PlaybackState.STATE_CONNECTING -> "CONNECTING";
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_PREV";
            case PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_NEXT";
            default -> "STATE_" + state;
        };
    }

    /**
     * 提取音轨唯一标识，默认使用 MEDIA_ID 或 title+artist 哈希。
     */
    @Nullable
    protected String extractTrackId(@NonNull MediaMetadata metadata) {
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (mediaId != null && !mediaId.trim().isEmpty()) {
            return mediaId.trim();
        }
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (title != null && !title.trim().isEmpty()) {
            return (title + "_" + (artist != null ? artist : "")).hashCode() + "";
        }
        return null;
    }
}
