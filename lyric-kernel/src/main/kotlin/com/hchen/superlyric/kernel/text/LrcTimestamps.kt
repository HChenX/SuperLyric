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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.kernel.text

/**
 * LRC 时间戳归一化
 *
 * 平台偶尔下发 `[mm:ss:ff]` 三段时间戳（QQ 的纯音乐提示行即为此形态），
 * 通用解析库只接受 `[mm:ss.xx]`。把第三段当作百分秒改写，库才能识别。
 *
 * 只改行首时间戳形态，不触碰正文；不匹配的内容原样返回。
 *
 * @author 焕晨HChen
 */
object LrcTimestamps {

    private val THREE_PART = Regex("""\[(\d{1,3}):(\d{1,2}):(\d{1,3})]""")

    fun normalize(text: String): String {
        if (!THREE_PART.containsMatchIn(text)) return text
        return THREE_PART.replace(text) { match ->
            "[" + match.groupValues[1] + ":" + match.groupValues[2] + "." + match.groupValues[3] + "]"
        }
    }
}
