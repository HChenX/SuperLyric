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
package com.hchen.superlyric.publisher.model;

/**
 * 音乐提供者的能力特征枚举。
 *
 * @author 焕晨HChen
 */
public enum ProviderCapability {
    /**
     * 具备整首歌词 Hook 能力 + 网络 API 兜底能力（双轨协同，如网易云、Spotify）
     */
    FULL_HOOK_WITH_NETWORK,

    /**
     * 仅具备整首歌词 Hook 能力（绝大多数标准音乐软件）
     */
    FULL_HOOK_ONLY,

    /**
     * 仅具备在线网络接口拉取能力（无应用内部歌词 Hook，如网易云音乐）
     */
    NETWORK_ONLY,

    /**
     * 仅具备单句歌词 Hook 兜底能力（极端边缘应用）
     */
    SINGLE_HOOK_LEGACY
}
