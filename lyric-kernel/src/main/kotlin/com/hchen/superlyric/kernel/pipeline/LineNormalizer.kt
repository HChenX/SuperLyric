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

/** 同一时刻行折叠与最终时序归一化。 */
internal object LineNormalizer {

    private const val MAX_LINE_SPAN_MS = 300_000L
    private const val FALLBACK_LINE_MS = 5_000L

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
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val result = ArrayList<LyricLine>(sorted.size)
        for (index in sorted.indices) {
            val current = sorted[index]
            val startMs = maxOf(0L, current.startMs)
            val nextStart = if (index + 1 < sorted.size) sorted[index + 1].startMs else -1L
            val lastWordEnd = current.words.maxOfOrNull { it.endMs } ?: startMs
            val capByNext = if (nextStart > startMs) nextStart else Long.MAX_VALUE
            val endMs = minOf(maxOf(startMs, current.endMs, lastWordEnd), capByNext)
                .let { boundEnd(it, startMs, capByNext, durationMs) }
            val words = current.words.map { word ->
                val wordStart = word.startMs.coerceIn(startMs, maxOf(startMs, endMs))
                val wordEnd = word.endMs.coerceAtLeast(wordStart)
                if (word.startMs == wordStart && word.endMs == wordEnd) word
                else word.copy(startMs = wordStart, endMs = wordEnd)
            }
            result += current.copy(startMs = startMs, endMs = endMs, words = words)
        }
        return result
    }

    private fun boundEnd(endMs: Long, startMs: Long, capByNext: Long, durationMs: Long): Long {
        if (endMs - startMs <= MAX_LINE_SPAN_MS) return endMs
        val fallback = if (durationMs > startMs) durationMs else startMs + FALLBACK_LINE_MS
        return minOf(maxOf(startMs, fallback), capByNext)
    }
}
