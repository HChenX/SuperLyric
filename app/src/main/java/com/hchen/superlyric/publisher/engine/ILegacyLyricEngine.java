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
package com.hchen.superlyric.publisher.engine;

import androidx.annotation.NonNull;

import com.hchen.superlyric.publisher.model.TrackContext;

/**
 * 极端场景单句歌词 Hook 兜底引擎契约。
 *
 * @author 焕晨HChen
 */
public interface ILegacyLyricEngine {
    /**
     * 启用单句兜底 Hook 拦截通道。
     */
    void enableLegacyHook();

    /**
     * 关闭或暂停单句兜底拦截。
     */
    void disableLegacyHook();

    /**
     * 切歌回调。
     */
    void onTrackChanged(@NonNull TrackContext context);
}
