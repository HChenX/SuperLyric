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

/**
 * 把「主行 + 同时间戳伴随行」形态的载荷拆成**三个独立通道**
 *
 * ## 为什么必须在解析前拆
 * 部分宿主（实测椒盐）把一首曲目的三路内容以**同一时间戳重复出现**的形态给出：
 * ```
 * [00:20.699] <00:20.699>初<00:21.133>め…は <00:23.011>   ← 主歌词（带词标签）
 * [00:20.699]ha ji me te no ru u bu ru wa                    ← 罗马音
 * [00:20.699]第一次参观卢浮宫                                  ← 翻译
 * ```
 * 而通用解析库判定「哪一行是哪一行的伴随」用的是**时间区间重叠**，不是时间戳相等：
 * 主行内最后一个词标签会把行的结束时间撑到下一行起始之后，于是库会把
 * **相邻的两条主行**误配成「主行 + 译文」，真正的伴随行反而落单、被当成独立歌词行。
 * 一旦配对完成，后置处理无法还原（伴随行的原始时间戳已经丢失）。
 *
 * ## 判据
 * 带词标签（`<mm:ss.mmm>`）的行是**主行**；与某个主行**时间戳相同**、
 * 且自身没有词标签的行是**伴随行**。
 * 这只在本文件描述的形态下成立，因此由 [split] 显式探测——形态不匹配时原样返回 `null`。
 *
 * ## 拆完还要修什么
 * 即使伴随行被移走，主行之间仍可能互相重叠（上例 100~200 与 150）。
 * 因此重建主载荷时会把**超出下一主行起始的词标签钳到下一行起始**，
 * 让库看到一份区间互不重叠的载荷——这正是我们自己的 [LyricPipeline] 在规范化阶段
 * 早已执行的同一条约束，只是必须提前到库解析之前。
 *
 * @author 焕晨HChen
 */
internal object CompanionSplitter {

    /**
     * 拆分结果
     *
     * @property main        剔除伴随行、并修好区间重叠后的主载荷
     * @property phonetic    罗马音伴随行（无则为 null）
     * @property translation 翻译伴随行（无则为 null）
     */
    data class Split(val main: String, val phonetic: String?, val translation: String?)

    /** 行首时间戳：`[mm:ss]` / `[mm:ss.xx]` */
    private val LINE_HEAD = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** 词标签：`<mm:ss.mmm>` */
    private val WORD_TAG = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /** 元数据指令行：`[ti:xxx]` 之类，不参与拆分 */
    private val DIRECTIVE = Regex("""^\[[a-zA-Z]{1,8}:.*]$""")

    /** CJK 字符：汉字、平假名、片假名、谚文 */
    private val CJK = Regex("""[㐀-䶿一-鿿぀-ゟ゠-ヿ가-힯]""")

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

        // 先把**全部**主行登记完，再回头分类伴随行。必须与遍历顺序无关：伴随行可能写在
        // 它的主行**之前**，边走边登记的话那种排布下所有伴随行都找不到主人，
        // 于是没有任何行被拆出去，本方法返回 null，整份载荷退回通用解析库
        val mainBodyByStart = rows.filter { it.tagged }.associate { it.startMs to it.body }

        for (row in rows) {
            if (row.tagged) {
                mainLines += row
                continue
            }

            val ownerBody = mainBodyByStart[row.startMs]
            if (ownerBody == null) {
                // 没有同时间戳的主行——它是一条普通歌词行，原样保留
                mainLines += row
                continue
            }

            if (isRomanization(row.body, ownerBody)) phonetic += row.raw else translation += row.raw
        }

        if (phonetic.isEmpty() && translation.isEmpty()) return null

        return Split(
            main = rebuildMain(mainLines),
            phonetic = phonetic.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            translation = translation.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    private class Row(val raw: String, val startMs: Long, val body: String, val tagged: Boolean)

    private fun parseRows(text: String): List<Row> {
        val rows = ArrayList<Row>(text.length / 32)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || DIRECTIVE.matches(line)) continue

            val head = LINE_HEAD.find(line) ?: continue
            val body = line.substring(head.range.last + 1)
            rows += Row(
                raw = line,
                startMs = timestampOf(head.value),
                body = body,
                tagged = WORD_TAG.containsMatchIn(body),
            )
        }
        return rows
    }

    /**
     * 重建主载荷：把每条主行中**触及或超出下一主行起始**的词标签钳到 `下一行起始 - 1ms`
     *
     * ## 为什么必须错开 1ms 而不是正好对齐
     * 通用解析库判定「主行 + 译文」依据的是**上一行的结束时间等于下一行的起始时间**：
     * `[00:00.860]…<00:00.867>` 后跟 `[00:00.867]衔接行` 时，
     * 后者会被整体吞并成前者的译文（实测确认）。因此只有让前行结束**严格早于**
     * 后行起始，才能让两条主行各自独立。
     *
     * 最后一条主行没有下界，原样保留。
     */
    private fun rebuildMain(rows: List<Row>): String {
        val builder = StringBuilder(rows.sumOf { it.raw.length + 1 })
        for (index in rows.indices) {
            val row = rows[index]
            val nextStart = rows.getOrNull(index + 1)?.startMs
            builder.append(clampTags(row.raw, row.startMs, nextStart)).append('\n')
        }
        return builder.toString().trimEnd('\n')
    }

    private fun clampTags(raw: String, startMs: Long, nextStartMs: Long?): String {
        if (nextStartMs == null) return raw

        val limit = maxOf(startMs, nextStartMs - 1L)
        val replacement = "<${formatTimestamp(limit)}>"
        return WORD_TAG.replace(raw) { match ->
            if (timestampOf(match.value) < nextStartMs) match.value else replacement
        }
    }

    /**
     * 判断伴随行是否为**罗马音**。
     *
     * 纯拉丁文本且主行本身含 CJK 时才成立——主行没有可被罗马化的字符时，
     * 那条伴随只可能是译文。
     */
    private fun isRomanization(candidate: String, ownerBody: String): Boolean {
        if (candidate.isEmpty() || CJK.containsMatchIn(candidate)) return false
        return CJK.containsMatchIn(ownerBody)
    }

    /** 解析 `[mm:ss.mmm]` 或 `<mm:ss.mmm>` 形式的时间戳为毫秒 */
    internal fun timestampOf(token: String): Long {
        val match = LINE_HEAD.find(token) ?: WORD_TAG.find(token) ?: return 0L
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = match.groupValues[3]
        val millis = when {
            fraction.isEmpty() -> 0L
            fraction.length == 1 -> fraction.toLong() * 100L
            fraction.length == 2 -> fraction.toLong() * 10L
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun formatTimestamp(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val minutes = safe / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}
