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

    internal val LINE_HEAD = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    internal val WORD_TAG = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")
    internal val DIRECTIVE = Regex("""^\[[a-zA-Z]{1,8}:.*]$""")

    private val THREE_PART = Regex("""\[(\d{1,3}):(\d{1,2}):(\d{1,3})]""")

    fun normalize(text: String): String {
        if (!THREE_PART.containsMatchIn(text)) return text
        return THREE_PART.replace(text) { match ->
            "[" + match.groupValues[1] + ":" + match.groupValues[2] + "." + match.groupValues[3] + "]"
        }
    }

    /** 解析行首或词标签中的时间戳为毫秒 */
    internal fun timestampOf(token: String): Long {
        val match = LINE_HEAD.find(token) ?: WORD_TAG.find(token) ?: return 0L
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = match.groupValues[3]
        val millis = when {
            fraction.isEmpty() -> 0L
            fraction.length == 1 -> fraction.toLong() * 100L
            fraction.length == 2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }
}
