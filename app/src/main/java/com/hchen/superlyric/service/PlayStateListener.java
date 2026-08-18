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
package com.hchen.superlyric.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyricapi.SuperLyricData;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放状态监听
 *
 * @author 焕晨HChen
 */
public final class PlayStateListener {
    @NonNull
    private final Context mContext;
    @NonNull
    private final SuperLyricService mService;
    @NonNull
    private final MediaSessionManager mMediaSessionManager;
    @NonNull
    private final ConcurrentHashMap<MediaController, MediaControllerCallback> mCallbacks = new ConcurrentHashMap<>();
    @NonNull
    private final MediaSessionManager.OnActiveSessionsChangedListener mListener = new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override
        public void onActiveSessionsChanged(@Nullable List<MediaController> controllers) {
            if (controllers == null) {
                return;
            }

            mCallbacks.forEach(MediaController::unregisterCallback);
            mCallbacks.clear();
            for (MediaController controller : controllers) {
                registerMediaControllerCallback(controller);
            }
        }
    };

    public PlayStateListener(@NonNull Context context, @NonNull SuperLyricService service) {
        mContext = context;
        mService = service;
        mMediaSessionManager = (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    public void register() {
        // system_server 上下文中无具体 NotificationListenerService 组件，传 null 获取全部 active sessions
        for (MediaController controller : mMediaSessionManager.getActiveSessions(null)) {
            registerMediaControllerCallback(controller);
        }

        mMediaSessionManager.addOnActiveSessionsChangedListener(mListener, null);
    }

    private void registerMediaControllerCallback(@NonNull MediaController controller) {
        MediaControllerCallback callback = mCallbacks.get(controller);
        if (callback != null) {
            controller.unregisterCallback(callback);
            mCallbacks.remove(controller);
        }

        callback = new MediaControllerCallback(controller);
        controller.registerCallback(callback);
        mCallbacks.put(controller, callback);
    }

    private class MediaControllerCallback extends MediaController.Callback {
        @NonNull
        private final MediaController mController;

        private MediaControllerCallback(@NonNull MediaController controller) {
            mController = controller;
        }

        @Override
        @SuppressLint("SwitchIntDef")
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (state == null) return;
            if (SuperLyricService.isSystemPlayStateListenerDisabled(mController.getPackageName())) {
                return;
            }

            if (isPublisher()) {
                switch (state.getState()) {
                    case PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> {
                        mService.sendSystemEvent(
                            mController.getPackageName(),
                            new SuperLyricData()
                        );
                    }
                    default -> {
                    }
                }
            }
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            // Do Nothing
        }

        private boolean isPublisher() {
            return SuperLyricService.isPublisher(mController.getPackageName());
        }
    }
}
