/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.kernel.decode;

/**
 * 单份歌词载荷上限。
 *
 * <p>解码输出、网络响应、磁盘缓存和宿主文件读取都受同一上限约束，避免任一路径
 * 把畸形载荷放大到内存中。
 */
public final class PayloadLimits {
    public static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    private PayloadLimits() {
    }
}
