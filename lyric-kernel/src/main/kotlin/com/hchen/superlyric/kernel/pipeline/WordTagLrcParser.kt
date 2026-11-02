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
 * 「时间标签在前」的逐字 LRC 解析器
 *
 * 输入形态
 * ```
 * [00:20.699] <00:20.699>初<00:21.133>め<00:22.298>は <00:23.011>
 * ```
 * 行首是行起始时间；其后是「`<时间>文字`」重复若干次。
 * 标签在文字之前，且末尾常有一个没有文字的标签，仅作行结束标记。
 */
internal object WordTagLrcParser {

    /**
     * @return 解析结果；没有任何有效行时返回 null，调用方应回退到通用解析库
     */
    fun parse(text: String): LyricDoc? {
        val lines = ArrayList<LyricLine>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || LrcTimestamps.DIRECTIVE.matches(line)) continue

            val head = LrcTimestamps.LINE_HEAD.find(line) ?: continue
            val startMs = LrcTimestamps.timestampOf(head.value)
            val body = line.substring(head.range.last + 1)

            val tags = LrcTimestamps.WORD_TAG.findAll(body).toList()
            if (tags.isEmpty()) {
                val plain = clean(body)
                if (plain.isNotEmpty()) {
                    lines += LyricLine(startMs = startMs, endMs = startMs, text = plain)
                }
                continue
            }

            val words = ArrayList<LyricWord>(tags.size)
            val builder = StringBuilder()
            var lineEnd = startMs

            // 第一个词标签之前的文字也是这一行：不追加会静默少一段
            // （[00:01.00]Hello <00:02.00>world）。
            val leadingRaw = decodeInline(body.substring(0, tags.first().range.first))
            if (leadingRaw.isNotEmpty()) {
                builder.append(leadingRaw)
                val leading = leadingRaw.trim()
                if (leading.isNotEmpty()) {
                    val leadingEnd = LrcTimestamps.timestampOf(tags.first().value)
                    words += LyricWord(leading, startMs, maxOf(startMs, leadingEnd))
                    lineEnd = maxOf(lineEnd, leadingEnd)
                }
            }

            for (index in tags.indices) {
                val tag = tags[index]
                val wordStart = LrcTimestamps.timestampOf(tag.value)
                val textFrom = tag.range.last + 1
                val textTo = tags.getOrNull(index + 1)?.range?.first ?: body.length
                val wordEnd = tags.getOrNull(index + 1)
                    ?.let { LrcTimestamps.timestampOf(it.value) } ?: wordStart

                lineEnd = maxOf(lineEnd, wordEnd)

                val wordText = decodeInline(body.substring(textFrom, textTo))

                // 只有空（长度为 0）才判为"末尾那个无文字的行结束标记"。
                // 不能判 isBlank()：英文歌词会把空格本身作为独立切片
                // （`<00:00.106> <00:00.212>`），跳过它会把 `We don't talk` 拼成 `Wedon'ttalk`。
                if (wordText.isEmpty()) continue

                // 没有尾标签时末词不能是零时长：按字数给一个兜底。
                val effectiveEnd = if (wordEnd > wordStart) wordEnd
                else wordStart + LyricText.fallbackWordDuration(wordText)
                lineEnd = maxOf(lineEnd, effectiveEnd)
                words += LyricWord(wordText, wordStart, effectiveEnd)
                builder.append(wordText)
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

    private fun clean(raw: String): String = decodeInline(raw).trim()

    /**
     * 逐字切片只做净化，不 trim
     *
     * 切片之间靠空白分隔（英文歌词尤其明显），trim 掉会把 `We don't talk` 拼成
     * `Wedon'ttalk`。行级文本在最后统一 trim 一次即可。
     *
     * 顺序是先解实体、后剥不可见字符：反过来的话，`&#x200b;` / `&#10;` 这类实体编码的
     * 不可见字符会在剥离之后才被解出来，等于没剥
     */
    private fun decodeInline(raw: String): String =
        LyricText.cleanInvisibleChars(LyricText.decodeEntities(raw))
}
