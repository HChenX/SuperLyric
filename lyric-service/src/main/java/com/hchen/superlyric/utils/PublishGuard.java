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

import androidx.annotation.Nullable;

/**
 * 发布内容与系统当前曲目的匹配判定
 *
 * <p>只用于拒绝明显属于另一首曲目的歌词（例如切歌竞态下晚到的上一首全文），不做曲目
 * 身份判定：没有稳定标识时，标题与时长都可能被宿主改写或缺失，任一侧不知道时不做否决。
 * 时长保留 {@link #DURATION_TOLERANCE_MS} 的容差（4.0 契约为 2 秒）：元数据在播放过程中
 * 会被刷新，同一首歌的不同剪辑通常相差数秒以上。
 *
 */
public final class PublishGuard {
    /**
     * 时长比对的容差，毫秒
     */
    public static final long DURATION_TOLERANCE_MS = 2_000L;

    private PublishGuard() {
    }

    /**
     * @param title           发布内容里的曲名，可为空
     * @param durationMs      发布内容里的时长，未知时为 0
     * @param sessionTitle    系统 MediaSession 的曲名，可为空
     * @param sessionDuration 系统 MediaSession 的时长，未知时为 0
     * @return 两者不矛盾时为 true
     */
    public static boolean matches(@Nullable String title, long durationMs,
                                  @Nullable String sessionTitle, long sessionDuration) {
        if (!isBlank(title) && !isBlank(sessionTitle)
            && !title.trim().equals(sessionTitle.trim())) {
            return false;
        }
        if (durationMs > 0L && sessionDuration > 0L
            && Math.abs(durationMs - sessionDuration) > DURATION_TOLERANCE_MS) {
            return false;
        }
        return true;
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }
}
