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
package com.hchen.superlyric.utils;

import android.media.session.PlaybackState;

import androidx.annotation.NonNull;

import com.hchen.superlyric.kernel.orchestrate.PlaybackSignal;

/**
 * 宿主播放状态常量到内核语义信号的**唯一**映射。
 *
 * <p>两侧共用这一份：宿主进程内的 Provider 与 system_server 内的服务。此前两侧各写一套，
 * 而 {@link PlaybackState#STATE_NONE} 在两套里被映射成了相反的含义（一侧"没停"、一侧"停了"），
 * 那正是分叉的起点。
 *
 * <p>映射与判定分开是有意的：内核只认语义（见 {@link PlaybackSignal}），
 * android 的常量解码留在 {@code :app}。
 *
 * @author 焕晨HChen
 */
public final class PlaybackSignals {
    private PlaybackSignals() {
    }

    /**
     * 把 {@link PlaybackState#getState()} 的取值映射为内核语义信号。
     *
     * @param state {@link PlaybackState} 的状态常量
     * @return 对应的语义信号；未知取值按"仍在播放流程中"处理
     */
    @NonNull
    public static PlaybackSignal of(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                return PlaybackSignal.PLAYING;
            case PlaybackState.STATE_PAUSED:
                return PlaybackSignal.PAUSED;
            case PlaybackState.STATE_STOPPED:
                return PlaybackSignal.STOPPED;
            case PlaybackState.STATE_ERROR:
                return PlaybackSignal.ERROR;
            case PlaybackState.STATE_NONE:
                // "尚未设置过播放状态"，不是"已停止"
                return PlaybackSignal.UNKNOWN;
            default:
                // 缓冲、快进、后退、跳转等：都在播放流程之内，只撤销待确认的停止
                return PlaybackSignal.ACTIVE;
        }
    }
}
