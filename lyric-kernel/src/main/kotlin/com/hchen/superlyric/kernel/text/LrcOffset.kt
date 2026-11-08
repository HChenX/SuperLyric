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

import com.hchen.superlyric.kernel.model.LyricDoc

/**
 * 通用 LRC 的 `[offset:±ms]` 指令
 *
 * 只负责通用 LRC 路径：QRC 的时间轴已有自己的 offset，KRC 不使用该指令。
 * 指令行从正文移除，解析完成后再统一平移，避免解析库与这里各应用一次。
 */
internal object LrcOffset {

    private val DIRECTIVE = Regex("""^\s*\[offset:\s*([+-]?\d+)]\s*$""", RegexOption.IGNORE_CASE)

    data class Parsed(val offsetMs: Long, val text: String)

    /**
     * 提取最后一个 offset 并从正文删除所有合法 offset 指令行。
     * 其它 `[ti:]` / `[ar:]` 等 metadata 原样保留。
     */
    fun parse(text: String): Parsed {
        var offsetMs = 0L
        var found = false
        val lines = ArrayList<String>()
        for (line in text.lineSequence()) {
            val match = DIRECTIVE.matchEntire(line)
            val value = match?.groupValues?.get(1)?.toLongOrNull()
            if (value != null) {
                offsetMs = value
                found = true
            } else {
                lines += line
            }
        }
        return Parsed(offsetMs, if (found) lines.joinToString("\n") else text)
    }

    /**
     * 平移所有行与逐字切片的起止时间，任一侧都不低于 0。
     * 行/词的结束时间同时保证不早于对应的起始时间。
     */
    fun apply(doc: LyricDoc, offsetMs: Long): LyricDoc {
        if (offsetMs == 0L || doc.isEmpty) return doc
        val lines = doc.lines.map { line ->
            val start = (line.startMs + offsetMs).coerceAtLeast(0L)
            val end = (line.endMs + offsetMs).coerceAtLeast(start)
            val words = line.words.map { word ->
                val wordStart = (word.startMs + offsetMs).coerceAtLeast(0L)
                val wordEnd = (word.endMs + offsetMs).coerceAtLeast(wordStart)
                word.copy(startMs = wordStart, endMs = wordEnd)
            }
            line.copy(startMs = start, endMs = end, words = words)
        }
        return doc.copy(lines = lines)
    }
}
