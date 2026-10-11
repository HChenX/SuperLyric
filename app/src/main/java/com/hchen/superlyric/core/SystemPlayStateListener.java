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
package com.hchen.superlyric.core;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统级播放状态监听器。
 * <p>
 * 在 system_server 内部监听全局活动的 MediaSession，将系统级播放状态与元数据变更实时同步至 SuperLyricService。
 *
 * @author 焕晨HChen
 */
public final class SystemPlayStateListener {
    private static final String TAG = "SystemPlayStateListener";

    @NonNull
    private final Context mContext;
    @NonNull
    private final SuperLyricService mService;
    @NonNull
    private final MediaSessionManager mMediaSessionManager;
    @NonNull
    private final ConcurrentHashMap<MediaController, MediaControllerCallback> mCallbacks = new ConcurrentHashMap<>();
    @NonNull
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull
    private final MediaSessionManager.OnActiveSessionsChangedListener mListener = new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override
        public void onActiveSessionsChanged(@Nullable List<MediaController> controllers) {
            if (controllers == null) return;

            long token = Binder.clearCallingIdentity();
            try {
                // 锁内**只**做 map 维护，注册与初始状态同步一律放到锁外：
                // registerMediaControllerCallback 会回调进 SuperLyricService，后者要取
                // mSnapshotLock —— 而 sendLyric 是「先取 mSnapshotLock、再进本类的 mCallbacks」，
                // 锁内回调就构成 AB-BA 死锁（主线程会被卡死到 watchdog）。
                // 重复注册与泄漏由 registerMediaControllerCallback 内部的锁 + 去重负责
                List<MediaController> toRegister;
                synchronized (mCallbacks) {
                    mCallbacks.forEach(MediaController::unregisterCallback);
                    mCallbacks.clear();
                    toRegister = new ArrayList<>(controllers);
                }
                for (MediaController controller : toRegister) {
                    registerMediaControllerCallback(controller);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    public SystemPlayStateListener(@NonNull Context context, @NonNull SuperLyricService service) {
        mContext = context;
        mService = service;
        mMediaSessionManager = (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    public void register() {
        long token = Binder.clearCallingIdentity();
        try {
            for (MediaController controller : mMediaSessionManager.getActiveSessions(null)) {
                registerMediaControllerCallback(controller);
            }
            mMediaSessionManager.addOnActiveSessionsChangedListener(mListener, null);
        } catch (Throwable t) {
            XposedLog.logW(TAG, "Failed to register SystemPlayStateListener: " + t.getMessage());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * 主动向指定包名的活跃 MediaSession 查询并同步当前播放状态与元数据。
     */
    public void syncCurrentStateForPackage(@NonNull String packageName) {
        long token = Binder.clearCallingIdentity();
        try {
            for (MediaController controller : mCallbacks.keySet()) {
                if (TextUtils.equals(controller.getPackageName(), packageName)) {
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null) {
                        mService.onSystemPlaybackStateChanged(packageName, state);
                    }
                    MediaMetadata metadata = controller.getMetadata();
                    if (metadata != null) {
                        mService.onSystemMetadataChanged(packageName, metadata);
                    }
                    return;
                }
            }

            for (MediaController controller : mMediaSessionManager.getActiveSessions(null)) {
                if (TextUtils.equals(controller.getPackageName(), packageName)) {
                    registerMediaControllerCallback(controller);
                    return;
                }
            }
        } catch (Throwable t) {
            XposedLog.logD(TAG, "Failed to sync state for package " + packageName + ": " + t.getMessage());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void registerMediaControllerCallback(@NonNull MediaController controller) {
        long token = Binder.clearCallingIdentity();
        try {
            final MediaControllerCallback callback;
            synchronized (mCallbacks) {
                MediaControllerCallback existing = mCallbacks.get(controller);
                if (existing != null) {
                    controller.unregisterCallback(existing);
                    mCallbacks.remove(controller);
                }

                callback = new MediaControllerCallback(controller);
                controller.registerCallback(callback, mMainHandler);
                mCallbacks.put(controller, callback);
            }

            // 主动同步当前初始状态（走回调以便复用"按包禁用"判定）
            PlaybackState state = controller.getPlaybackState();
            if (state != null) {
                callback.onPlaybackStateChanged(state);
            }
            MediaMetadata metadata = controller.getMetadata();
            if (metadata != null) {
                callback.onMetadataChanged(metadata);
            }
        } catch (Throwable t) {
            XposedLog.logD(TAG, "Failed to register callback for controller: " + controller.getPackageName() + ", " + t.getMessage());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private class MediaControllerCallback extends MediaController.Callback {
        @NonNull
        private final MediaController mController;

        private MediaControllerCallback(@NonNull MediaController controller) {
            mController = controller;
        }

        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (state == null) return;
            String pkg = mController.getPackageName();
            if (SuperLyricService.isSystemPlayStateListenerDisabled(pkg)) {
                return;
            }

            if (SuperLyricService.isPublisher(pkg)) {
                mService.onSystemPlaybackStateChanged(pkg, state);
            }
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (metadata == null) return;
            String pkg = mController.getPackageName();
            if (SuperLyricService.isSystemPlayStateListenerDisabled(pkg)) {
                return;
            }

            if (SuperLyricService.isPublisher(pkg)) {
                mService.onSystemMetadataChanged(pkg, metadata);
            }
        }

        /**
         * 会话被销毁
         *
         * <p>宿主**释放会话而进程仍在**时，这是唯一能到达本模块的通知：播放状态不会再变
         * （已经没有会话了），而 `onActiveSessionsChanged` 只会把回调摘掉，不会告诉服务
         * "这个包停了"。少了这条路，界面会一直停在最后一句上。
         */
        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
            String pkg = mController.getPackageName();
            if (SuperLyricService.isSystemPlayStateListenerDisabled(pkg)) {
                return;
            }

            if (SuperLyricService.isPublisher(pkg)) {
                mService.onSystemSessionDestroyed(pkg);
            }
        }
    }
}
