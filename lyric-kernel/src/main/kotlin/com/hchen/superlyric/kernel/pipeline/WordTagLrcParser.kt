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
import com.hchen.superlyric.kernel.text.LyricText

/**
 * 「时间标签在前」的逐字 LRC 解析器
 *
 * ## 输入形态
 * ```
 * [00:20.699] <00:20.699>初<00:21.133>め<00:22.298>は <00:23.011>
 * ```
 * 行首是行起始时间；其后是「`<时间>文字`」重复若干次。
 * **标签在文字之前**，且末尾常有一个**没有文字的标签**，仅作行结束标记。
 *
 * ## 为什么要自研，而不是继续用通用解析库
 * 该库判定「哪一行是哪一行的译文」用的是**相邻行的起始时间差**：
 * 实测两条**互相独立的主行**只要起始相差小于 200ms，后一条就会被整体吞并成
 * 前一条的 `translation`，且译文行本身从列表里消失。
 * 真机报文里这种紧邻的主行很常见（制作信息行、歌者标记行），于是会出现
 * 「主行被吞、头部行挂着一段莫名其妙的译文」。这是库的既定行为，
 * 无法通过喂给它的文本绕开（把行尾标签错开仍然被吞）。
 * 本格式本身结构简单，自己解析可以做到完全确定。
 *
 * ## 何时启用
 * 只在 [CompanionSplitter] 判定载荷带有「同时间戳伴随行」时启用
 * （见 [LyricPipeline.parse]），因此不会影响其它 Provider 的解析路径。
 *
 * @author 焕晨HChen
 */
internal object WordTagLrcParser {

    /** 行首时间戳：`[mm:ss]` / `[mm:ss.xx]` */
    private val LINE_HEAD = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** 词标签：`<mm:ss.mmm>` */
    private val WORD_TAG = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

    /** 元数据指令行：`[ti:xxx]` 之类 */
    private val DIRECTIVE = Regex("""^\[[a-zA-Z]{1,8}:.*]$""")

    private const val FALLBACK_LINE_MS = 1_000L

    /**
     * @return 解析结果；没有任何有效行时返回 null，调用方应回退到通用解析库
     */
    fun parse(text: String): LyricDoc? {
        val lines = ArrayList<LyricLine>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || DIRECTIVE.matches(line)) continue

            val head = LINE_HEAD.find(line) ?: continue
            val startMs = CompanionSplitter.timestampOf(head.value)
            val body = line.substring(head.range.last + 1)

            val tags = WORD_TAG.findAll(body).toList()
            if (tags.isEmpty()) {
                val plain = clean(body)
                if (plain.isNotEmpty()) {
                    lines += LyricLine(startMs = startMs, endMs = startMs, text = plain)
                }
                continue
            }

            val words = ArrayList<LyricWord>(tags.size)
            val builder = StringBuilder()
            var cursor = 0
            var lineEnd = startMs

            for (index in tags.indices) {
                val tag = tags[index]
                val wordStart = CompanionSplitter.timestampOf(tag.value)
                val textFrom = tag.range.last + 1
                val textTo = tags.getOrNull(index + 1)?.range?.first ?: body.length
                val wordEnd = tags.getOrNull(index + 1)
                    ?.let { CompanionSplitter.timestampOf(it.value) } ?: wordStart

                cursor = textTo
                lineEnd = maxOf(lineEnd, wordEnd)

                val wordText = decodeInline(body.substring(textFrom, textTo))

                // 只有**空**（长度为 0）才判为"末尾那个无文字的行结束标记"。
                // 不能判 isBlank()——英文歌词会把空格本身作为独立切片
                // （`<00:00.106> <00:00.212>`），跳过它会把 `We don't talk` 拼成 `Wedon'ttalk`。
                if (wordText.isEmpty()) continue

                words += LyricWord(wordText, wordStart, maxOf(wordStart, wordEnd))
                builder.append(wordText)
            }

            // 末尾没有标签兜住的残余文字也属于本行
            if (cursor < body.length) {
                val tail = clean(body.substring(cursor))
                if (tail.isNotEmpty()) builder.append(tail)
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
     * 逐字切片只做净化，**不 trim**。
     *
     * 切片之间靠空白分隔（英文歌词尤其明显），trim 掉会把 `We don't talk` 拼成
     * `Wedon'ttalk`。行级文本在最后统一 trim 一次即可。
     *
     * 顺序是**先解实体、后剥不可见字符**：反过来的话，`&#x200b;` / `&#10;` 这类实体编码的
     * 不可见字符会在剥离之后才被解出来，等于没剥
     */
    private fun decodeInline(raw: String): String =
        LyricText.cleanInvisibleChars(LyricText.decodeEntities(raw))
}
