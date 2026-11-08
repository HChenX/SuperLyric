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

/**
 * 独立歌手标签行合并
 *
 * 只处理 `男：` / `女：` / `合：` / `合唱：` 这类独立标签行，把它们并入 1.5 秒内
 * 的下一条非标签行。section 标签和普通冒号歌词不合并。
 */
internal object SpeakerLabelMerger {

    private const val MAX_LABEL_LEAD_MS = 1_500L

    private val COMMON_LABELS = setOf(
        "男", "女", "合", "齐", "男声", "女声", "合唱", "齐唱", "独白", "旁白",
        "主唱", "和声", "说唱", "rap", "rapper", "vocal", "vocals", "singer",
    )

    private val SECTION_LABELS = setOf(
        "intro", "outro", "verse", "chorus", "pre-chorus", "prechorus", "bridge",
        "hook", "refrain", "interlude",
    )

    private val SHORT_LATIN_SLOT = Regex("""^[A-Za-z0-9Ａ-Ｚａ-ｚ０-９]{1,2}$""")

    private val SINGER_PREFIX = Regex("""^歌手\s*[\p{L}\p{N}]{1,16}$""", RegexOption.IGNORE_CASE)

    fun merge(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines
        val sorted = lines.withIndex()
            .sortedWith(compareBy({ it.value.startMs }, { it.index }))
            .map { it.value }
            .toMutableList()
        val merged = ArrayList<LyricLine>(sorted.size)
        for (index in sorted.indices) {
            val current = sorted[index]
            val label = speakerLabel(current.text)
            if (label == null) {
                merged += current
                continue
            }
            val targetIndex = findTarget(sorted, index)
            if (targetIndex < 0) {
                merged += current
                continue
            }
            val target = sorted[targetIndex]
            sorted[targetIndex] = target.copy(
                text = label + target.text.trimStart(),
                words = mergeWords(label, target.words),
            )
        }
        return merged
    }

    private fun findTarget(lines: List<LyricLine>, speakerIndex: Int): Int {
        val speaker = lines[speakerIndex]
        for (index in speakerIndex + 1 until lines.size) {
            val candidate = lines[index]
            val lead = candidate.startMs - speaker.startMs
            if (lead < 0L || lead > MAX_LABEL_LEAD_MS) break
            if (candidate.text.isBlank()) continue
            if (speakerLabel(candidate.text) != null) continue
            if (isSectionLabel(candidate.text)) continue
            return index
        }
        return -1
    }

    private fun mergeWords(label: String, words: List<LyricWord>): List<LyricWord> {
        if (words.isEmpty()) return words
        val first = words.first()
        val result = ArrayList<LyricWord>(words.size)
        // 只去掉首词的前导空白来吸收标签；后续切片的内部空格和时序原样保留。
        result += first.copy(text = label + first.text.trimStart())
        result.addAll(words.subList(1, words.size))
        return result
    }

    private fun speakerLabel(text: String): String? {
        val name = labelName(text) ?: return null
        if (name.length !in 1..16) return null
        if (isSectionName(name)) return null
        val isSpeaker = COMMON_LABELS.any { name.equals(it, ignoreCase = true) } ||
            (name.length <= 8 && name.any { it in "男女合唱" }) ||
            SHORT_LATIN_SLOT.matches(name) ||
            SINGER_PREFIX.matches(name)
        return if (isSpeaker) name + "：" else null
    }

    private fun isSectionLabel(text: String): Boolean {
        val name = labelName(text) ?: return false
        return isSectionName(name)
    }

    private fun isSectionName(name: String): Boolean =
        SECTION_LABELS.any { name.equals(it, ignoreCase = true) }

    private fun labelName(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.endsWith(':') && !trimmed.endsWith('：')) return null
        return trimmed.dropLast(1).trim()
            .trim('[', ']', '(', ')', '（', '）', '【', '】', '「', '」')
    }
}
