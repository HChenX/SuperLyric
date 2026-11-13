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
package com.hchen.superlyric.kernel.text

/**
 * 歌词文本的单行长度上限
 *
 * 结构正则与逐字标签扫描都以单行为单位；单行超限时回溯会按行宽平方增长，
 * 而正常歌词行远小于此上限，超限只可能是畸形载荷。检测与解析共用同一上限。
 */
internal object LyricTextLimits {

    /** 单行字符数上限 */
    const val MAX_LINE_CHARS = 4 * 1024

    /** 逐行应用结构正则；超长行整行跳过，不交给正则 */
    fun anyLineMatches(text: String, pattern: Regex): Boolean {
        for (line in text.lineSequence()) {
            if (line.length > MAX_LINE_CHARS) continue
            if (pattern.containsMatchIn(line)) return true
        }
        return false
    }
}
