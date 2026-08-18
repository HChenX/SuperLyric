/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.data;

/**
 * 应用对网络歌词获取模式的支持程度。
 *
 * <p>用于 {@link SupportApps#sSupportNetworkApps}：键为包名，值为应用在
 * 离线 Hook 与在线网络两种获取模式间的能力。不在此映射中的应用不支持网络模式。
 *
 * @author 焕晨HChen
 */
public enum NetworkMode {
    /**
     * 默认使用 Hook 模式，但允许用户切换为网络获取（如网易云、荣耀音乐）。
     */
    OPTIONAL,

    /**
     * 仅支持网络获取，无法切换为 Hook 模式（如 Spotify）。
     */
    ONLY
}
