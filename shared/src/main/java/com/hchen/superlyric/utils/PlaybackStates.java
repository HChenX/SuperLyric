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
        return switch (state) {
            case PlaybackState.STATE_PLAYING,
                 PlaybackState.STATE_FAST_FORWARDING,
                 PlaybackState.STATE_REWINDING,
                 PlaybackState.STATE_SKIPPING_TO_NEXT,
                 PlaybackState.STATE_SKIPPING_TO_PREVIOUS ->
                com.hchen.superlyricapi.model.PlaybackState.PLAYING;

            case PlaybackState.STATE_PAUSED -> com.hchen.superlyricapi.model.PlaybackState.PAUSED;

            case PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING ->
                com.hchen.superlyricapi.model.PlaybackState.BUFFERING;

            case PlaybackState.STATE_STOPPED -> com.hchen.superlyricapi.model.PlaybackState.STOPPED;

            case PlaybackState.STATE_ERROR -> com.hchen.superlyricapi.model.PlaybackState.ERROR;

            // STATE_NONE 是"尚未设置过"，不是"已停止"
            default -> null;
        };
    }

    /**
     * 映射为停止判定使用的语义信号
     *
     * <p>未知取值按"仍在播放流程中"处理；只有明确的终态才返回停止信号。
     */
    @NonNull
    public static PlaybackSignal toSignal(int state) {
        return switch (state) {
            case PlaybackState.STATE_PLAYING -> PlaybackSignal.PLAYING;
            case PlaybackState.STATE_PAUSED -> PlaybackSignal.PAUSED;
            case PlaybackState.STATE_STOPPED -> PlaybackSignal.STOPPED;
            case PlaybackState.STATE_ERROR -> PlaybackSignal.ERROR;
            case PlaybackState.STATE_NONE -> PlaybackSignal.UNKNOWN;
            default -> PlaybackSignal.ACTIVE;
        };
    }
}
