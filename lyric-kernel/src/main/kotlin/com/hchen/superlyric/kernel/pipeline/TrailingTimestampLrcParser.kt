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
package com.hchen.superlyric.kernel.pipeline

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.text.LrcTimestamps
import com.hchen.superlyric.kernel.text.LyricText

/**
 * 「文字在前、时间标签在后」的逐字 LRC 解析器
 *
 * 输入形态
 * ```
 * [00:29.280]孤[00:29.665]单[00:30.040]的[00:30.433]今[00:30.737]后[00:31.457]
 * ```
 * 行首标签是行起始；其后每个文字片段后面跟一个时间标签，标签是该片段的结束
 * 时间，也是下一片段的起始时间。通用解析库会把每个标签当作新行，导致每行
 * 首个字符被归到前一标签而丢失。
 */
internal object TrailingTimestampLrcParser {

    /** 行内时间标签；行首标签由 [LrcTimestamps.LINE_HEAD] 单独处理 */
    private val INLINE_TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /**
     * 是否为「文字在前、时间标签在后」的形态
     *
     * 行首标签之后必须先有文字，且文字之后还要有至少一个行内标签；
     * 行首直接跟第二个标签是普通 LRC 的多时间戳行，不属于本形态。
     */
    fun canParse(text: String): Boolean {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || LrcTimestamps.DIRECTIVE.matches(line)) continue
            val head = LrcTimestamps.LINE_HEAD.find(line) ?: continue
            val body = line.substring(head.range.last + 1)
            if (hasTrailingTags(body)) return true
        }
        return false
    }

    /**
     * 行内时间标签之前是否已有文字
     *
     * 普通 LRC 的同一行多时间戳（`[00:01.00][00:05.00]正文`）第一个标签紧贴行首，
     * 不属于本形态
     */
    internal fun hasTrailingTags(body: String): Boolean =
        INLINE_TAG.find(body)?.range?.first?.let { it > 0 } == true

    /**
     * @return 解析结果；不是本形态时返回 null，调用方应回退到通用解析库
     */
    fun parse(text: String): LyricDoc? {
        val lines = ArrayList<LyricLine>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || LrcTimestamps.DIRECTIVE.matches(line)) continue

            val head = LrcTimestamps.LINE_HEAD.find(line) ?: continue
            val startMs = LrcTimestamps.timestampOf(head.value)
            val body = line.substring(head.range.last + 1)
            // 行首后没有文字、直接跟第二个标签，是同一行多时间戳的普通 LRC 形态，
            // 不能按逐字格式解析。
            if (!hasTrailingTags(body)) continue
            val tags = INLINE_TAG.findAll(body).toList()

            val words = ArrayList<LyricWord>(tags.size)
            val builder = StringBuilder()
            var previous = startMs
            var lineEnd = startMs
            var cursor = 0

            for (tag in tags) {
                val segment = decode(body.substring(cursor, tag.range.first))
                val tagTime = LrcTimestamps.timestampOf(tag.value)
                if (segment.isNotEmpty()) {
                    val end = if (tagTime > previous) tagTime
                    else previous + LyricText.fallbackWordDuration(segment)
                    words += LyricWord(segment, previous, end)
                    builder.append(segment)
                    lineEnd = maxOf(lineEnd, end)
                }
                previous = tagTime
                lineEnd = maxOf(lineEnd, tagTime)
                cursor = tag.range.last + 1
            }

            // 末尾没有结束标签的文字同样是这一行；按字数补一个正时长。
            val trailing = decode(body.substring(cursor))
            if (trailing.isNotEmpty()) {
                val end = previous + LyricText.fallbackWordDuration(trailing)
                words += LyricWord(trailing, previous, end)
                builder.append(trailing)
                lineEnd = maxOf(lineEnd, end)
            }

            val lineText = builder.toString().trim()
            if (lineText.isEmpty()) continue

            lines += LyricLine(
                startMs = startMs,
                endMs = maxOf(startMs, lineEnd),
                text = lineText,
                words = words,
            )
        }

        if (lines.isEmpty()) return null
        lines.sortBy { it.startMs }
        return LyricDoc(lines = lines)
    }

    private fun decode(raw: String): String =
        LyricText.cleanInvisibleChars(LyricText.decodeEntities(raw))
}
