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
package com.hchen.superlyric.kernel.decode;

/**
 * 载荷解码失败。
 * <p>
 * 本异常的存在是一条硬性架构约束：**解码层永不猜测**。
 * 一旦无法从给定字节流中确定性地还原出明文，就必须抛出本异常，
 * 由上层归类为「可重试失败」或「不支持」，而不是回退到某个"看起来能用"的错误缓冲区。
 *
 * @author 焕晨HChen
 */
public final class DecodeException extends Exception {
    public DecodeException(String message) {
        super(message);
    }

    public DecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
