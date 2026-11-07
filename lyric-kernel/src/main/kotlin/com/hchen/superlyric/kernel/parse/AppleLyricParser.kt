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
import com.hchen.superlyric.kernel.text.LyricText

/**
 * Apple Music 宿主行模型的纯映射
 *
 * 宿主文本带 HTML 标记；先替换换行标签为空格再剥离其它标签，避免把两段文字粘连。
 */
object AppleLyricParser {

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
    )

    private val HTML_TAG = Regex("<[^>]*>")

    /** `<br>` / `<br/>` / `<br />`，大小写不敏感 */
    private val HTML_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    fun parse(
        lines: List<Line>,
        title: String,
        artist: String,
        durationMs: Long,
        lyricId: String?,
    ): LyricDoc? {
        val mapped = lines.mapNotNull { line ->
            val text = cleanHtml(line.text)
            if (text.isEmpty()) return@mapNotNull null

            LyricLine(
                startMs = line.startMs,
                endMs = line.endMs,
                text = text,
                translation = cleanHtml(line.translation).takeIf { it.isNotEmpty() },
                words = line.words.mapNotNull { word ->
                    val wordText = cleanHtml(word.text)
                    if (wordText.isEmpty()) null
                    else LyricWord(wordText, word.startMs, word.endMs)
                },
            )
        }.sortedBy { it.startMs }

        if (mapped.isEmpty()) return null
        return LyricDoc(
            lines = mapped,
            title = title,
            artist = artist,
            durationMs = durationMs,
            lyricId = lyricId,
        )
    }

    /**
     * 剥离 HTML 标记、还原实体，再清理零宽字符
     */
    fun cleanHtml(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val withBreaks = HTML_BREAK.replace(raw, " ")
        val withoutTags = HTML_TAG.replace(withBreaks, "")
        return LyricText.cleanInvisibleChars(LyricText.decodeEntities(withoutTags)).trim()
    }
}
