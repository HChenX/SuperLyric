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

import com.hchen.superlyric.kernel.text.LrcTimestamps

import java.util.Locale

internal object CompanionSplitter {

    /**
     * 拆分结果
     *
     * @property main        剔除伴随行、并修好区间重叠后的主载荷
     * @property phonetic    罗马音伴随行（无则为 null）
     * @property translation 翻译伴随行（无则为 null）
     */
    data class Split(val main: String, val phonetic: String?, val translation: String?)

    /**
     * 尝试拆分：形态不匹配时返回 `null`，调用方应原样使用原载荷
     */
    fun split(text: String): Split? {
        val rows = parseRows(text)
        if (rows.size < 2) return null

        val mainStarts = rows.filter { it.tagged }.map { it.startMs }.toHashSet()
        if (mainStarts.isEmpty()) return null

        // 必须确实存在"与某条主行时间戳相同的裸行"才动手，否则不是本形态
        if (rows.none { !it.tagged && it.startMs in mainStarts }) return null

        val mainLines = ArrayList<Row>(rows.size)
        val phonetic = ArrayList<String>()
        val translation = ArrayList<String>()

        // 先把全部主行登记完，再回头分类伴随行。必须与遍历顺序无关：伴随行可能写在
        // 它的主行之前，边走边登记的话那种排布下所有伴随行都找不到主人，
        // 于是没有任何行被拆出去，本方法返回 null，整份载荷退回通用解析库
        val mainBodyByStart = rows.filter { it.tagged }.associate { it.startMs to it.body }

        for (row in rows) {
            if (row.tagged) {
                mainLines += row
                continue
            }

            val ownerBody = mainBodyByStart[row.startMs]
            if (ownerBody == null) {
                // 没有同时间戳的主行：它是一条普通歌词行，原样保留
                mainLines += row
                continue
            }

            if (SidecarMerger.isRomanization(row.body, ownerBody)) phonetic += row.raw else translation += row.raw
        }

        if (phonetic.isEmpty() && translation.isEmpty()) return null

        // 指令行（[offset:]、[ti:]、[ar:] 等）不参与行分类，但必须原样回到主载荷：
        // 丢掉 [offset:] 会让整首时间平移，丢掉 [ti:]/[ar:] 会丢元数据。
        val directives = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && LrcTimestamps.DIRECTIVE.matches(it) }
            .toList()

        return Split(
            main = rebuildMain(mainLines, directives),
            phonetic = phonetic.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            translation = translation.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    private class Row(val raw: String, val startMs: Long, val body: String, val tagged: Boolean)

    private fun parseRows(text: String): List<Row> {
        val rows = ArrayList<Row>(text.length / 32)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || LrcTimestamps.DIRECTIVE.matches(line)) continue

            val head = LrcTimestamps.LINE_HEAD.find(line) ?: continue
            val body = line.substring(head.range.last + 1)
            rows += Row(
                raw = line,
                startMs = LrcTimestamps.timestampOf(head.value),
                body = body,
                tagged = LrcTimestamps.WORD_TAG.containsMatchIn(body),
            )
        }
        return rows
    }

    /**
     * 重建主载荷：把每条主行中触及或超出下一主行起始的词标签钳到 `下一行起始 - 1ms`
     *
     * 必须错开 1ms 而不是正好对齐
     * 通用解析库判定「主行 + 译文」依据的是上一行的结束时间等于下一行的起始时间：
     * `[00:00.860]…<00:00.867>` 后跟 `[00:00.867]衔接行` 时，
     * 后者会被整体吞并成前者的译文。只有让前行结束严格早于后行起始，
     * 两条主行才能各自独立。
     *
     * 最后一条主行没有下界，原样保留。
     */
    private fun rebuildMain(rows: List<Row>, directives: List<String>): String {
        val builder = StringBuilder(rows.sumOf { it.raw.length + 1 } + directives.sumOf { it.length + 1 })
        for (directive in directives) builder.append(directive).append('\n')
        for (index in rows.indices) {
            val row = rows[index]
            val nextStart = rows.getOrNull(index + 1)?.startMs
            builder.append(clampTags(row.raw, row.startMs, nextStart)).append('\n')
        }
        return builder.toString().trimEnd('\n')
    }

    private fun clampTags(raw: String, startMs: Long, nextStartMs: Long?): String {
        if (nextStartMs == null) return raw
        // 两条主行起始完全相同（对唱/双语同瞬）时，nextStart-1 会把整行所有词标签
        // 压到行首；这种排布不是“主行 + 伴随行”，应原样保留。
        if (nextStartMs <= startMs) return raw

        val limit = maxOf(startMs, nextStartMs - 1L)
        val replacement = "<${formatTimestamp(limit)}>"
        return LrcTimestamps.WORD_TAG.replace(raw) { match ->
            if (LrcTimestamps.timestampOf(match.value) < nextStartMs) match.value else replacement
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val minutes = safe / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        // 必须锁 Locale.ROOT：默认 Locale 下 `%d` 会按区域产出数字（阿语/波斯语区是
        // 阿拉伯-印度数字）。这个串是 `<mm:ss.mmm>` 词标签，会被拼回主载荷，
        // 而下游 `WORD_TAG` 用 `\d` 解析：非 ASCII 数字匹配不上，整段逐字时序失效
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}
