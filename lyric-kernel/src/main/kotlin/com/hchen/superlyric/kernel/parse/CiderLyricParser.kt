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
package com.hchen.superlyric.kernel.parse

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.pipeline.LineNormalizer
import com.hchen.superlyric.kernel.text.LyricText

/**
 * Cider 宿主歌词模型映射；文本已由宿主 XML 解析器还原，逐字时间为绝对毫秒。
 */
object CiderLyricParser {

    data class Word(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    data class Line(
        val text: String,
        val translation: String,
        val startMs: Long,
        val endMs: Long,
        val words: List<Word>,
        val empty: Boolean = false,
        val background: Line? = null,
    )

    fun parse(lines: List<Line>, durationMs: Long): LyricDoc? {
        // 伴唱保持独立歌词行，不参与同起点行的译文折叠。
        val mapped = lines.flatMap { listOfNotNull(it, it.background) }.mapNotNull { line ->
            if (line.empty) return@mapNotNull null
            val text = LyricText.cleanInvisibleChars(line.text).trim()
            if (text.isEmpty()) return@mapNotNull null

            LyricLine(
                startMs = line.startMs,
                endMs = line.endMs,
                text = text,
                translation = LyricText.cleanInvisibleChars(line.translation).trim().ifEmpty { null },
                words = line.words.mapNotNull { word ->
                    val wordText = LyricText.cleanInvisibleChars(word.text)
                    if (wordText.isEmpty()) null
                    else LyricWord(wordText, word.startMs, word.endMs)
                },
            )
        }
        val normalized = LineNormalizer.normalize(mapped, durationMs)
        if (normalized.isEmpty()) return null
        return LyricDoc(lines = normalized, durationMs = durationMs)
    }
}
