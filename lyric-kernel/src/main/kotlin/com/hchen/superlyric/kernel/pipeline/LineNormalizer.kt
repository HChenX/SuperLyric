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

import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord

/** kernel 侧的行折叠与最终时序归一化；发布边界的防御由共享层 LyricSanitizer 负责 */
internal object LineNormalizer {

    private const val MAX_LINE_SPAN_MS = 300_000L
    private const val FALLBACK_LINE_MS = 5_000L

    /** 行起始超出曲目时长的容差；平台会把推广行写成 99:00，但宿主时长偶尔偏短，容差取 2 分钟 */
    private const val MAX_LINE_OVER_DURATION_MS = 120_000L

    private val WHITESPACE = Regex("""\s+""")

    fun collapseSimultaneous(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines
        val sorted = lines.sortedBy { it.startMs }
        val result = ArrayList<LyricLine>(sorted.size)
        var index = 0
        while (index < sorted.size) {
            var end = index + 1
            while (end < sorted.size && sorted[end].startMs == sorted[index].startMs) end++
            if (end - index == 1) {
                result += sorted[index]
                index = end
                continue
            }
            val group = sorted.subList(index, end)
            if (group.count { it.hasWords } > 1) {
                result += group
                index = end
                continue
            }
            val mainIndex = group.indexOfFirst { it.hasWords }.let { if (it >= 0) it else 0 }
            var main = group[mainIndex]
            val owned = main.translation
            if (main.phonetic.isNullOrEmpty() && !owned.isNullOrEmpty() &&
                SidecarMerger.isRomanization(owned, main.text)
            ) {
                main = main.copy(translation = null, phonetic = owned)
            }
            for (i in group.indices) {
                if (i == mainIndex) continue
                val text = group[i].text.trim()
                if (text.isEmpty()) continue
                if (SidecarMerger.isRomanization(text, main.text)) {
                    if (main.phonetic.isNullOrEmpty()) main = main.copy(phonetic = text)
                } else {
                    if (main.translation.isNullOrEmpty()) main = main.copy(translation = text)
                }
            }
            result += main
            index = end
        }
        return result
    }

    fun normalize(lines: List<LyricLine>, durationMs: Long): List<LyricLine> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.filter {
            it.text.isNotBlank() &&
                    (durationMs <= 0L || it.startMs <= durationMs + MAX_LINE_OVER_DURATION_MS)
        }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val result = ArrayList<LyricLine>(sorted.size)
        for (index in sorted.indices) {
            val current = sorted[index]
            val startMs = maxOf(0L, current.startMs)
            val nextStart = if (index + 1 < sorted.size) sorted[index + 1].startMs else -1L
            val lastWordEnd = current.words.maxOfOrNull { it.endMs } ?: startMs
            val capByNext = if (nextStart > startMs) nextStart else Long.MAX_VALUE
            var endMs = minOf(maxOf(startMs, current.endMs, lastWordEnd), capByNext)
                .let { boundEnd(it, startMs, capByNext, durationMs) }
            // 宿主会给版权/制作人这类行单点时间戳（start == end）。文本行必须有正时长，
            // 在下一行开始前留出 1ms；下一行同时开始则无空位可让，保持原样。
            if (endMs <= startMs && capByNext > startMs) {
                endMs = minOf(capByNext, startMs + 1)
            }
            val words = repairWords(current.words, startMs, endMs)
            result += current.copy(
                startMs = startMs,
                endMs = endMs,
                text = normalizeText(current.text),
                words = words,
            )
        }
        return result
    }

    /**
     * 修复逐字切片
     *
     * KRC/QRC 的空格 token 常与后一字重叠，个别标题行还会给出互相覆盖的切片。
     * 裁剪任一侧都会改变可见字的高亮边界，因此把重叠的相邻切片合并：文本按原顺序拼接，
     * 区间取并集。零长切片并入相邻切片，保证发布模型中的词均为正时长。
     */
    private fun repairWords(words: List<LyricWord>, lineStart: Long, lineEnd: Long): List<LyricWord> {
        if (words.isEmpty()) return words
        val merged = ArrayList<LyricWord>(words.size)
        for (word in words) {
            val start = word.startMs.coerceIn(lineStart, lineEnd)
            val end = word.endMs.coerceIn(start, lineEnd)
            val last = merged.lastOrNull()
            when {
                last == null -> merged += word.copy(startMs = start, endMs = end)
                start < last.endMs || end <= start -> merged[merged.lastIndex] = last.copy(
                    text = last.text + word.text,
                    startMs = minOf(last.startMs, start),
                    endMs = maxOf(last.endMs, end),
                )

                else -> merged += word.copy(startMs = start, endMs = end)
            }
        }

        val result = ArrayList<LyricWord>(merged.size)
        for (word in merged) {
            var start = word.startMs.coerceIn(lineStart, lineEnd)
            var end = word.endMs.coerceIn(start, lineEnd)
            if (end <= start) end = minOf(lineEnd, start + 1)
            if (end <= start) start = maxOf(lineStart, end - 1)

            val last = result.lastOrNull()
            if (last != null && start < last.endMs) {
                result[result.lastIndex] = last.copy(
                    text = last.text + word.text,
                    startMs = last.startMs,
                    endMs = maxOf(last.endMs, end),
                )
            } else {
                result += word.copy(startMs = start, endMs = end)
            }
        }
        return result
    }

    private fun normalizeText(text: String): String = text.trim().replace(WHITESPACE, " ")

    private fun boundEnd(endMs: Long, startMs: Long, capByNext: Long, durationMs: Long): Long {
        if (endMs - startMs <= MAX_LINE_SPAN_MS) return endMs
        val fallback = if (durationMs > startMs) durationMs else startMs + FALLBACK_LINE_MS
        return minOf(maxOf(startMs, fallback), capByNext)
    }
}
