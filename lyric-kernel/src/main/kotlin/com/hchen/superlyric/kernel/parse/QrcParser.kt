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

import com.hchen.superlyric.kernel.merge.SidecarChannel
import com.hchen.superlyric.kernel.merge.TimeText
import com.hchen.superlyric.kernel.merge.TranslationMerger
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.text.LrcTimestamps
import com.hchen.superlyric.kernel.text.LyricText
import com.hchen.superlyric.kernel.text.LyricTextLimits

/**
 * QQ 音乐 QRC 明文解析器。
 *
 * 支持 XML 包裹与裸 QRC 两种形态；译文按标签名与 LyricType 双向识别，实体需还原。
 * 行文本位于时间标签之前。词起始有绝对毫秒与行内偏移两种排布，按第二个词逐行判定。
 */
object QrcParser {

    /** 行形式：`[start,dur]body` */
    private val LINE_PATTERN = Regex("""^\[(\d+),(\d+)](.*)$""")

    /**
     * 词标签：`(start,dur)` 或 `(start,dur,flag)`
     *
     * 文字位于时间标签之前，例如 `[138621,3336]我(138621,384)把(139005,305)…`：
     * 行起始 138621 与首词起始 138621 相等（行内相对偏移为 0）。
     *
     * 按"标签在前、文字在后"捕获会让每个词错配到前一个词的时间上，
     * 且每行首个词被整段丢弃（"后会无期" → "会无期"）。
     */
    private val WORD_TAG_PATTERN = Regex("""\((\d+),(\d+)(?:,\d+)?\)""")

    /** 标签形式：`[ti:xxx]` */
    private val TAG_PATTERN = Regex("""^\[([a-zA-Z]+):(.*)]$""")

    /** LRC 行时间戳：`[mm:ss]` / `[mm:ss.xx]` / `[mm:ss:xx]` */
    private val LRC_TIME_PATTERN = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?](.*)$""")

    private val OFFSET_PATTERN = Regex("""\[offset:\s*(-?\d+)]""", RegexOption.IGNORE_CASE)

    /** 从属性串中取 LyricContent */
    private val LYRIC_CONTENT_ATTR = Regex("""LyricContent="([^"]*)"""")

    /** 从属性串中取 LyricType */
    private val LYRIC_TYPE_ATTR = Regex("""LyricType="(\d)"""")

    private const val DEFAULT_LINE_DURATION_MS = 1000L

    /** 整份载荷上限：正则/扫描都必须在有界输入上运行 */
    private const val MAX_INPUT_CHARS = 2_000_000

    /** XML 标签数量上限：限制标签扫描与后续处理的规模 */
    private const val MAX_XML_TAGS = 256

    /** 单份载荷的有效行上限；正常歌词远小于此值 */
    private const val MAX_LINES = 5_000

    /** 解析统计；仅在传入非空实例时累加 */
    data class ParseStats(
        var skippedLongLines: Int = 0,
        var skippedUnmatchedLines: Int = 0,
        var emptyWords: Int = 0,
        var skippedXmlTags: Int = 0,
        var truncatedLines: Boolean = false,
    ) {
        override fun toString(): String =
            "long=$skippedLongLines, unmatched=$skippedUnmatchedLines, " +
                    "emptyWords=$emptyWords, xmlTags=$skippedXmlTags, truncated=$truncatedLines"
    }

    /**
     * 解析 QRC 明文
     *
     * @return 解析结果；无有效歌词行时返回 null
     */
    fun parse(text: String?, stats: ParseStats? = null): LyricDoc? {
        if (text.isNullOrBlank()) return null
        if (text.length > MAX_INPUT_CHARS) return null
        if (countXmlTags(text) > MAX_XML_TAGS) return null

        val blocks = extractXmlBlocks(text, stats)
        val rawMain = if (blocks != null) blocks.main ?: return null else text
        val mainContent = normalizeEntityNewlines(rawMain)
        val translationContent = blocks?.translation?.let(::normalizeEntityNewlines)

        val doc = parseLines(mainContent, stats) ?: return null
        if (translationContent.isNullOrBlank()) return doc

        val offset = OFFSET_PATTERN.find(mainContent)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val translationEntries = parsePlainTimeText(translationContent, offset)
        if (translationEntries.isEmpty()) return doc

        return TranslationMerger.attach(doc, translationEntries, SidecarChannel.TRANSLATION)
    }

    /**
     * 解析独立下发的伴随载荷（平台以 LRC 形态给出的译文或罗马音）
     *
     * @return 「时间戳 → 文本」列表；无有效记录时返回空列表
     */
    fun parseSidecar(text: String?): List<TimeText> {
        if (text.isNullOrBlank()) return emptyList()
        val blocks = extractXmlBlocks(text, null)
        val content = if (blocks != null) (blocks.translation ?: blocks.main ?: return emptyList()) else text
        return parseTimeText(content, 0L)
    }

    /**
     * 解析独立的「时间戳 → 文本」载荷
     *
     * QRC 与 LRC 两种时间戳都接受：译文块既可能是 `[n,n]`，也可能是 `[mm:ss.xx]` / `[mm:ss:ff]`。
     */
    fun parseTimeText(text: String?, offsetMs: Long = 0L): List<TimeText> {
        if (text.isNullOrBlank()) return emptyList()
        return parsePlainTimeText(normalizeEntityNewlines(text), offsetMs)
    }

    /**
     * 从原始载荷中取出可交给通用解析器的主体与译文块
     *
     * QRC 端点可能返回 LRC 形态的明文（无逐字标签），[parse] 此时一行都读不出来。
     * 兜底必须使用这里已抽好的块：直接拿原始 XML 会丢掉被属性前缀粘住的第一个时间戳，
     * 并把结尾的标签记进最后一行。
     */
    fun fallbackContent(text: String?, stats: ParseStats? = null): Fallback? {
        if (text.isNullOrBlank()) return null
        if (text.length > MAX_INPUT_CHARS) return null
        val blocks = extractXmlBlocks(text, stats)
        val main = blocks?.main ?: if (blocks == null) text else null
        if (main.isNullOrBlank()) return null
        return Fallback(
            main = normalizeEntityNewlines(main),
            translation = blocks?.translation?.let(::normalizeEntityNewlines),
        )
    }

    /** 通用解析器兜底所需的两个块 */
    class Fallback(val main: String, val translation: String?)

    /**
     * XML 属性里的换行常被写成实体；不还原会把整块歌词当成一行，
     * 标签因此混进正文、后续行全部丢失
     */
    private fun normalizeEntityNewlines(text: String): String =
        text.replace("&#10;", "\n").replace("&#xA;", "\n")

    private class XmlBlocks(val main: String?, val translation: String?)

    /**
     * 拆出 XML 中的主歌词与译文块，兼容 Lyric_2 与 LyricType 两种排布，属性顺序不限。
     */
    private fun extractXmlBlocks(text: String, stats: ParseStats?): XmlBlocks? {
        if (!text.contains("<Lyric_")) return null

        // 按 <Lyric_n ...> 整标签切块，再在属性串内独立判定 LyricType，
        // 因此 LyricContent 与 LyricType 的书写顺序不影响识别
        val mainParts = StringBuilder()
        val translationParts = StringBuilder()

        // 线性扫描标签，不用 [^>]* 正则：大量未闭合的 <Lyric_ 会让正则按 k·n 回溯。
        var cursor = 0
        while (true) {
            val start = text.indexOf("<Lyric_", cursor)
            if (start < 0) break
            val end = text.indexOf('>', start)
            if (end < 0) break
            cursor = end + 1

            var nameEnd = start + 7
            while (nameEnd < end && text[nameEnd].isDigit()) nameEnd++
            val tagIndex = text.substring(start + 7, nameEnd)
            if (tagIndex.isEmpty()) continue
            val attributes = if (nameEnd < end) text.substring(nameEnd + 1, end) else ""

            val content = LYRIC_CONTENT_ATTR.find(attributes)?.groupValues?.get(1) ?: run {
                stats?.let { it.skippedXmlTags++ }
                continue
            }
            val lyricType = LYRIC_TYPE_ATTR.find(attributes)?.groupValues?.get(1)
            // 不在 XML 边界解码：行解析会统一 decodeEntities。这里先解一次会让
            // 双重转义文本多解一层，也会把 &#10; 变成真实换行、在按行切分前就把一行拆断。
            val decoded = content

            val isTranslation = when (lyricType) {
                "2", "3" -> true
                null -> tagIndex == "2"
                else -> false
            }

            if (isTranslation) {
                translationParts.append(decoded).append('\n')
            } else {
                mainParts.append(decoded).append('\n')
            }
        }

        if (mainParts.isEmpty() && translationParts.isEmpty()) {
            // 有 <Lyric_ 但没有可用的 LyricContent 属性：按裸文本处理
            return null
        }
        return XmlBlocks(
            main = mainParts.toString().ifBlank { null },
            translation = translationParts.toString().ifBlank { null },
        )
    }

    /** 统计 XML 标签数量，超过上限时提前返回 */
    private fun countXmlTags(text: String): Int {
        if (!text.contains("<Lyric_")) return 0
        var count = 0
        var index = text.indexOf("<Lyric_")
        while (index >= 0) {
            count++
            if (count > MAX_XML_TAGS) return count
            index = text.indexOf("<Lyric_", index + 7)
        }
        return count
    }

    private fun parseLines(content: String, stats: ParseStats?): LyricDoc? {
        val offset = OFFSET_PATTERN.find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val lines = ArrayList<LyricLine>()
        for (raw in content.lineSequence()) {
            if (lines.size >= MAX_LINES) {
                stats?.truncatedLines = true
                break
            }
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (TAG_PATTERN.matches(line)) continue

            // 与格式检测共用同一单行上限；内核无日志依赖，超限行静默跳过
            if (line.length > LyricTextLimits.MAX_LINE_CHARS) {
                stats?.let { it.skippedLongLines++ }
                continue
            }

            val lineMatch = LINE_PATTERN.find(line) ?: run {
                stats?.let { it.skippedUnmatchedLines++ }
                continue
            }
            val lineStart = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = lineMatch.groupValues[2].toLongOrNull() ?: 0L
            val body = lineMatch.groupValues[3]

            val startMs = (lineStart + offset).coerceAtLeast(0L)
            val durationMs = if (lineDuration > 0) lineDuration else DEFAULT_LINE_DURATION_MS
            val endMs = startMs + durationMs

            val parsed = parseBody(body, startMs, endMs, offset, lineStart, stats)
            if (parsed.text.isBlank()) continue
            lines += parsed
        }

        if (lines.isEmpty()) return null
        lines.sortBy { it.startMs }
        return LyricDoc(lines = lines)
    }

    private fun parseBody(
        body: String,
        startMs: Long,
        endMs: Long,
        offset: Long,
        rawLineStart: Long,
        stats: ParseStats?,
    ): LyricLine {
        val words = ArrayList<LyricWord>()
        val textBuilder = StringBuilder()
        var cursor = 0

        val tags = WORD_TAG_PATTERN.findAll(body).toList()

        // 词起始有两种真实排布：歌内绝对毫秒 与 行内相对偏移。二者必须区分，
        // 否则整行逐字时间会整体偏移一个行起始的量。
        //
        // 判据取首词：绝对排布下首词原始值不会小于行起始；相对排布下它是个小偏移。
        // 必须逐行判定而非逐词：同一行内混用两种解释会自相矛盾。
        // 用未加 offset 的行起始做判定：若拿 startMs（已加 offset）来比，
        // 正 offset 会把绝对排布误判成相对排布，词时间被二次叠加行起始。
        val absoluteTime = tags.firstOrNull()
            ?.groupValues?.get(1)?.toLongOrNull()
            ?.let { it >= rawLineStart }
            ?: true

        // 词文本位于其时间标签之前：按位置顺序把标签前的全部文字并入该词，
        // 因此正文里的 ASCII 括号是普通文本，不会吞进词文本或整段丢失。
        for (tag in tags) {
            val wordText = LyricText.cleanInvisibleChars(
                LyricText.decodeEntities(body.substring(cursor, tag.range.first)),
            )
            val rawStart = tag.groupValues[1].toLongOrNull()
            val rawDuration = tag.groupValues[2].toLongOrNull() ?: 0L
            cursor = tag.range.last + 1

            if (rawStart == null || wordText.isEmpty()) {
                if (wordText.isEmpty()) stats?.let { it.emptyWords++ }
                continue
            }

            val wordStart = if (absoluteTime) rawStart + offset else startMs + rawStart
            val wordEnd = if (rawDuration > 0) wordStart + rawDuration else wordStart + LyricText.fallbackWordDuration(wordText)

            words += LyricWord(wordText, wordStart, wordEnd)
            textBuilder.append(wordText)
        }

        // 最后一个标签之后没有任何标签的残余文字（畸形载荷）同样属于本行，不能丢
        if (cursor < body.length) {
            textBuilder.append(
                LyricText.cleanInvisibleChars(LyricText.decodeEntities(body.substring(cursor))),
            )
        }

        if (words.isEmpty()) {
            val plain = LyricText.cleanInvisibleChars(
                LyricText.decodeEntities(WORD_TAG_PATTERN.replace(body, "")),
            ).trim()
            return LyricLine(startMs = startMs, endMs = endMs, text = plain)
        }

        return LyricLine(
            startMs = startMs,
            endMs = endMs,
            text = textBuilder.toString(),
            words = words,
        )
    }

    private fun parsePlainTimeText(
        content: String,
        offsetMs: Long = 0L,
    ): List<TimeText> {
        val entries = ArrayList<TimeText>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (TAG_PATTERN.matches(line)) continue

            val qrc = LINE_PATTERN.find(line)
            val lrc = if (qrc == null) LRC_TIME_PATTERN.find(line) else null
            if (qrc == null && lrc == null) continue
            val startMs = if (qrc != null) {
                qrc.groupValues[1].toLongOrNull() ?: continue
            } else {
                LrcTimestamps.timestampOf(
                    lrc!!.groupValues[1], lrc.groupValues[2], lrc.groupValues[3]
                )
            }
            val body = if (qrc != null) qrc.groupValues[3] else lrc!!.groupValues[4]
            val text = LyricText.cleanInvisibleChars(
                LyricText.decodeEntities(WORD_TAG_PATTERN.replace(body, "")),
            ).trim()

            if (text.isEmpty()) continue
            entries += TimeText(startMs = (startMs + offsetMs).coerceAtLeast(0L), text = text)
        }
        return entries
    }

}
