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

import androidx.annotation.Nullable;

/**
 * Android 播放状态常量到 v4 模型的唯一映射
 *
 * <p>与 {@link PlaybackSignals} 回答的问题不同：那里决定"要不要排期停止、要不要撤销"，
 * 这里只决定"下发的 State 帧写什么状态"。{@code STATE_NONE} 因此映射为 {@code null}
 * （不知道）而不是 {@code STOPPED}，写成停止会让客户端冻结在一个并未观察到的结论上。
 *
 * @author 焕晨HChen
 */
public final class PlaybackStates {
    private PlaybackStates() {
    }

    /**
     * @param state {@link PlaybackState#getState()} 的取值
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
}
