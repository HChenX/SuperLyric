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
import androidx.annotation.Nullable;

import com.hchen.superlyric.kernel.orchestrate.PlaybackSignal;

/**
 * Android 播放状态常量的两种映射
 *
 * <p>{@link #toV4(int)} 决定下发的 State 帧写什么状态；{@link #toSignal(int)} 决定
 * 是否排期停止、是否撤销。{@code STATE_NONE} 在两者中都表示"不知道"：前者返回
 * {@code null}，后者返回 {@link PlaybackSignal#UNKNOWN}。
 *
 */
public final class PlaybackStates {
    private PlaybackStates() {
    }

    /**
     * @return v4 状态；未知或未设置时为 null
     */
    @Nullable
    public static com.hchen.superlyricapi.model.PlaybackState toV4(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                return com.hchen.superlyricapi.model.PlaybackState.PLAYING;

            case PlaybackState.STATE_PAUSED:
                return com.hchen.superlyricapi.model.PlaybackState.PAUSED;

            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
                return com.hchen.superlyricapi.model.PlaybackState.BUFFERING;

            case PlaybackState.STATE_STOPPED:
                return com.hchen.superlyricapi.model.PlaybackState.STOPPED;

            case PlaybackState.STATE_ERROR:
                return com.hchen.superlyricapi.model.PlaybackState.ERROR;

            default:
                // STATE_NONE 是"尚未设置过"，不是"已停止"
                return null;
        }
    }

    /**
     * 映射为停止判定使用的语义信号
     *
     * <p>未知取值按"仍在播放流程中"处理；只有明确的终态才返回停止信号。
     */
    @NonNull
    public static PlaybackSignal toSignal(int state) {
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
                return PlaybackSignal.UNKNOWN;
            default:
                return PlaybackSignal.ACTIVE;
        }
    }
}
