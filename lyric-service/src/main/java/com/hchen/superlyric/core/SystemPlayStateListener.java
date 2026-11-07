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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统级播放状态监听器
 *
 * <p>在 system_server 内部监听全局活动的 MediaSession，把系统级播放状态与元数据变更同步至
 * {@link LyricRuntime}。回调经 {@code mMainHandler} 派发到主线程，运行时方法因此不能
 * 在该线程上阻塞。
 *
 * @author 焕晨HChen
 */
public final class SystemPlayStateListener {
    private static final String TAG = "SystemPlayStateListener";

    @NonNull
    private final Context mContext;
    @NonNull
    private final LyricRuntime mRuntime;
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
                // 锁内只做 map 维护。注册/反注册会对宿主做 Binder 调用并可能同步回调进
                // LyricRuntime；放到锁外，避免主线程持锁等待宿主 Binder。
                List<Map.Entry<MediaController, MediaControllerCallback>> toUnregister;
                List<MediaController> toRegister;
                synchronized (mCallbacks) {
                    // controller 与回调对象必须成对取出：清空 map 后再 get 只会得到 null
                    toUnregister = new ArrayList<>(mCallbacks.entrySet());
                    mCallbacks.clear();
                    toRegister = new ArrayList<>(controllers);
                }
                for (Map.Entry<MediaController, MediaControllerCallback> entry : toUnregister) {
                    entry.getKey().unregisterCallback(entry.getValue());
                }
                for (MediaController controller : toRegister) {
                    registerMediaControllerCallback(controller);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    public SystemPlayStateListener(@NonNull Context context, @NonNull LyricRuntime runtime) {
        mContext = context;
        mRuntime = runtime;
        mMediaSessionManager = (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    /**
     * 注册全局 MediaSession 监听
     *
     * @return 是否成功注册到系统 MediaSessionManager；为 false 时调用方不得记录初始化成功
     */
    public boolean register() {
        long token = Binder.clearCallingIdentity();
        try {
            for (MediaController controller : mMediaSessionManager.getActiveSessions(null)) {
                registerMediaControllerCallback(controller);
            }
            mMediaSessionManager.addOnActiveSessionsChangedListener(mListener, null);
            return true;
        } catch (Throwable t) {
            XposedLog.logE(TAG, "Failed to register SystemPlayStateListener", t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * 主动向指定包名的活跃 MediaSession 查询并同步当前播放状态与元数据
     */
    public void syncCurrentStateForPackage(@NonNull String packageName) {
        // 与 onPlaybackStateChanged/onMetadataChanged 使用同一判定：否则按包禁用只挡住
        // 后续回调，主动 sync 仍会把状态喂给服务端。
        if (mRuntime.isSystemPlayStateListenerDisabled(packageName)) return;
        long token = Binder.clearCallingIdentity();
        try {
            for (MediaController controller : mCallbacks.keySet()) {
                if (TextUtils.equals(controller.getPackageName(), packageName)) {
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null) {
                        mRuntime.onSystemPlaybackStateChanged(packageName, state);
                    }
                    MediaMetadata metadata = controller.getMetadata();
                    if (metadata != null) {
                        mRuntime.onSystemMetadataChanged(packageName, metadata);
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
            XposedLog.logW(TAG, "Failed to sync state for package " + packageName, t);
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

            // 主动同步初始状态；走回调以复用按包禁用判定
            PlaybackState state = controller.getPlaybackState();
            if (state != null) {
                callback.onPlaybackStateChanged(state);
            }
            MediaMetadata metadata = controller.getMetadata();
            if (metadata != null) {
                callback.onMetadataChanged(metadata);
            }
        } catch (Throwable t) {
            XposedLog.logW(TAG, "Failed to register callback for controller: "
                + controller.getPackageName(), t);
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
            if (mRuntime.isSystemPlayStateListenerDisabled(pkg)) {
                return;
            }

            if (mRuntime.isPublisher(pkg)) {
                mRuntime.onSystemPlaybackStateChanged(pkg, state);
            }
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (metadata == null) return;
            String pkg = mController.getPackageName();
            if (mRuntime.isSystemPlayStateListenerDisabled(pkg)) {
                return;
            }

            if (mRuntime.isPublisher(pkg)) {
                mRuntime.onSystemMetadataChanged(pkg, metadata);
            }
        }

        /**
         * 会话被销毁
         *
         * <p>宿主释放会话而进程仍在时，这是唯一能到达本模块的通知：播放状态不会再变，
         * {@code onActiveSessionsChanged} 只摘掉回调，不会报告该包停止。缺少这条通知时，
         * 界面会停在最后一行歌词上。
         */
        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
            String pkg = mController.getPackageName();
            if (mRuntime.isSystemPlayStateListenerDisabled(pkg)) {
                return;
            }

            if (mRuntime.isPublisher(pkg)) {
                mRuntime.onSystemSessionDestroyed(pkg);
            }
        }
    }
}
