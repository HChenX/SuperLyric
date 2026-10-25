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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.kernel.parse

import com.hchen.superlyric.kernel.merge.SidecarChannel
import com.hchen.superlyric.kernel.merge.TimeText
import com.hchen.superlyric.kernel.merge.TranslationMerger
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord

/**
 * 咪咕原生 LRC / MRC 快照的纯模型映射
 *
 * MRC 词时长是从行起点累计的间隔，偏移以宿主 `getPlayOffset` 的语义扣除。
 * TRC 在原始时间轴上交给 [TranslationMerger] 对齐，避免偏移裁切后把多行折叠到零点
 */
object MobileMusicLyricParser {

    /** 允许丢弃的坏行比例（%）；超过则整首拒绝，避免修出一张错误时间轴 */
    private const val MAX_BAD_LINE_PERCENT = 20

    data class NativeLine(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val translation: String?,
        val wordTexts: List<String?>?,
        val wordDurationsMs: List<Long>?,
    )

    @JvmStatic
    fun parse(
        lines: List<NativeLine>,
        translations: List<TimeText>,
        durationMs: Long,
        offsetMs: Long,
    ): LyricDoc? {
        if (lines.isEmpty()) return null
        // 单行负时间/乱序不应拒绝整首：过滤坏行并按起始排序，只有坏行占比过高才判定
        // 载荷不可用。阈值防止把“大部分时间轴都坏了”的载荷硬修成一张错图。
        val usable = lines.filter { it.startMs >= 0L }.sortedBy { it.startMs }
        if (usable.isEmpty()) return null
        if ((lines.size - usable.size) * 100 > lines.size * MAX_BAD_LINE_PERCENT) return null

        val mapped = usable.mapIndexed { index, line ->
            val nextStart = usable.getOrNull(index + 1)?.startMs ?: (durationMs + offsetMs)
            val end = if (line.endMs > line.startMs) line.endMs else maxOf(line.startMs, nextStart)
            val text = clean(line.text)
            LyricLine(
                startMs = line.startMs,
                endMs = end,
                text = text,
                translation = line.translation?.let(::clean)?.takeIf { it.isNotBlank() },
                words = readWords(line, text, end),
            )
        }
        val merged = TranslationMerger.attach(
            LyricDoc(mapped),
            translations.map { it.copy(text = clean(it.text)) },
            SidecarChannel.TRANSLATION,
        )
        return merged.copy(
            durationMs = durationMs.coerceAtLeast(0L),
            lines = merged.lines.map { line ->
                line.copy(
                    startMs = (line.startMs - offsetMs).coerceAtLeast(0L),
                    endMs = (line.endMs - offsetMs).coerceAtLeast(0L),
                    words = line.words.mapNotNull { word ->
                        val start = (word.startMs - offsetMs).coerceAtLeast(0L)
                        val end = (word.endMs - offsetMs).coerceAtLeast(0L)
                        if (end <= start) null else word.copy(startMs = start, endMs = end)
                    },
                )
            },
        )
    }

    private fun readWords(line: NativeLine, text: String, endMs: Long): List<LyricWord> {
        val texts = line.wordTexts ?: return emptyList()
        val durations = line.wordDurationsMs ?: return emptyList()
        if (texts.isEmpty() || texts.size != durations.size) return emptyList()

        val result = ArrayList<LyricWord>(texts.size)
        val combined = StringBuilder()
        var start = line.startMs
        for (index in texts.indices) {
            val word = texts[index]?.let(::clean) ?: return emptyList()
            val duration = durations[index]
            if (duration < 0L || (word.isNotEmpty() && duration == 0L)) return emptyList()
            val end = start + duration
            if (end < start || end > endMs) return emptyList()
            combined.append(word)
            if (word.isNotEmpty()) result += LyricWord(word, start, end)
            start = end
        }
        return if (combined.toString() == text && start > line.startMs) result else emptyList()
    }

    private fun clean(text: String): String = text.replace("\r", "").replace("\n", "")
}
