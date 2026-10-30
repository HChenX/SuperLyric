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

import java.util.Objects;

/**
 * 系统 MediaSession 对当前曲目的事实
 *
 * <p>曲名不能无条件用于校验发布内容：部分宿主会把当前歌词行写进
 * {@code METADATA_KEY_TITLE}，同一首歌内该字段每次回调都可能变化。把它直接当作
 * 当前曲目的标题会拒掉合法的重新发布。
 *
 * <p>因此在同一个曲目内观察标题是否变化：变过即标记为不可信，校验时退化为只比时长。
 * 只观察到一个取值同样不算证据，那可能恰好是被改写的歌词行；至少连续两次取到同一个
 * 标题才允许用它做校验。
 *
 * <p>判定"同一个曲目"优先用 {@code METADATA_KEY_MEDIA_ID}，没有标识时看时长是否在
 * {@link PublishGuard#DURATION_TOLERANCE_MS} 内。这是启发式而非身份判定：错误只会让
 * 校验更宽松，不会拒掉合法歌词。
 *
 * @author 焕晨HChen
 */
public final class MediaFacts {
    @Nullable
    private String mediaId;
    @Nullable
    private String title;
    private long durationMs;
    private boolean titleUnstable;
    private int observations;

    /**
     * 记录一次元数据快照
     */
    public synchronized void update(@Nullable String mediaId, @Nullable String title, long durationMs) {
        boolean sameTrack = Objects.equals(this.mediaId, mediaId)
            && (durationMs <= 0L || this.durationMs <= 0L
            || Math.abs(durationMs - this.durationMs) <= PublishGuard.DURATION_TOLERANCE_MS);

        if (!sameTrack) {
            this.mediaId = mediaId;
            this.title = title;
            this.durationMs = durationMs;
            this.titleUnstable = false;
            this.observations = 1;
            return;
        }

        if (!Objects.equals(this.title, title)) {
            // 同一曲目内的标题变化只可能来自宿主改写，不是切歌
            this.titleUnstable = true;
        }
        this.title = title;
        if (durationMs > 0L) {
            this.durationMs = durationMs;
        }
        if (observations < 2) {
            observations++;
        }
    }

    /**
     * @return 曲名可用于校验时为 true
     */
    public synchronized boolean isTitleUsable() {
        return !titleUnstable && observations >= 2;
    }

    @Nullable
    public synchronized String title() {
        return title;
    }

    public synchronized long durationMs() {
        return durationMs;
    }
}
