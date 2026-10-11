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
import com.hchen.superlyric.kernel.text.LyricText

/**
 * QQ 音乐 QRC 明文解析器。
 *
 * <h3>输入形态</h3>
 * 本解析器接收的是 [com.hchen.superlyric.kernel.decode.QrcCipher] 解密后的**明文**，有两种形态：
 * <ol>
 *   <li><b>XML 包裹</b>：{@code <QrcInfos>} 内含 {@code <Lyric_1 LyricType="1" LyricContent="…"/>}
 *       与可选的 {@code <Lyric_2 LyricType="2" LyricContent="…"/>}。译文即内嵌于此；</li>
 *   <li><b>裸 QRC</b>：直接是 {@code [start,dur](wStart,wDur,flag)文字…} 行。
 *       此时译文通常由平台以**独立字段**下发（LRC 形态），由上层走伴随通道处理。</li>
 * </ol>
 *
 * <h3>比旧实现修正了什么</h3>
 * <ul>
 *   <li><b>译文形态全覆盖</b>：旧实现只认字面量 {@code <Lyric_2>}，当平台改用
 *       {@code <Lyric_1 LyricType="2">} 排布时译文会**整段丢失**。本实现按标签名与
 *       {@code LyricType} 属性双向识别，且不依赖属性顺序。</li>
 *   <li><b>译文对齐宽容</b>：旧实现用译文时间戳做 Map 键**精确匹配**，两段载荷相差 1ms 即静默丢弃；
 *       本实现统一走 [TranslationMerger] 的就近匹配。</li>
 *   <li><b>实体还原</b>：旧实现不还原 XML 实体，界面会出现 {@code &#39;} 之类的原始串。</li>
 * </ul>
 *
 * <h3>已由真机证实的两种形态</h3>
 * 均已对真实报文核实：
 * <ul>
 *   <li><b>文字位于时间标签之前</b>：{@code 文字(start,dur)}，不是标签在前。
 *       按"标签在前"解析会让每个词错配到前一个词的时间上，并丢掉每行第一个词。</li>
 *   <li><b>词起始有两种排布</b>：歌内**绝对毫秒**与行内**相对偏移**都真实存在
 *       （QQ 实测为绝对）。二者必须区分，否则整行逐字时间会整体偏移一个行起始的量。
 *       本解析器按首词逐行自动判定。</li>
 * </ul>
 *
 * > [!WARNING]
 * > 早期文档曾断言"QRC 用行内相对偏移、YRC 用绝对毫秒"，**该断言与实测不符**：
 * > 首个词的原始值在两种排布下恰好相同，只有第二个词才能区分。
 * > 判断某一批报文属于哪种排布时，务必看第二个词。
 *
 * @author 焕晨HChen
 */
object QrcParser {

    /** 行形式：{@code [start,dur]body} */
    private val LINE_PATTERN = Regex("""^\[(\d+),(\d+)](.*)$""")

    /**
     * 词形式：{@code 文字(start,dur)} 或 {@code 文字(start,dur,flag)}。
     *
     * **文字位于时间标签之前**——这一点已由真机报文证实：
     * {@code [138621,3336]我(138621,384)把(139005,305)…} 中，行起始 138621
     * 与首词起始 138621 相等（行内相对偏移为 0）。
     *
     * 若按"标签在前、文字在后"捕获，会导致每个词错配到前一个词的时间上，
     * 且**每行首个词被整段丢弃**（"后会无期" → "会无期"）。
     */
    private val WORD_PATTERN = Regex("""([^(]*)\((\d+),(\d+)(?:,\d+)?\)""")

    /** 仅匹配时间标签本身，用于剥离标签、保留文字。 */
    private val WORD_TAG_ONLY = Regex("""\(\d+,\d+(?:,\d+)?\)""")

    /** 标签形式：{@code [ti:xxx]} */
    private val TAG_PATTERN = Regex("""^\[([a-zA-Z]+):(.*)]$""")

    private val OFFSET_PATTERN = Regex("""\[offset:\s*(-?\d+)]""", RegexOption.IGNORE_CASE)

    /** 匹配整个 Lyric 标签：{@code <Lyric_n 属性...>} */
    private val LYRIC_TAG_PATTERN = Regex("""<Lyric_(\d)\b([^>]*)>""")

    /** 从属性串中取 LyricContent */
    private val LYRIC_CONTENT_ATTR = Regex("""LyricContent="([^"]*)"""")

    /** 从属性串中取 LyricType */
    private val LYRIC_TYPE_ATTR = Regex("""LyricType="(\d)"""")

    private const val DEFAULT_LINE_DURATION_MS = 1000L

    /**
     * 解析 QRC 明文。
     *
     * @param text 解密后的 QRC 明文
     * @return 解析结果；无有效歌词行时返回 null
     */
    fun parse(text: String?): LyricDoc? {
        if (text.isNullOrBlank()) return null

        val mainContent: String
        val translationContent: String?

        val blocks = extractXmlBlocks(text)
        if (blocks != null) {
            mainContent = blocks.main ?: return null
            translationContent = blocks.translation
        } else {
            mainContent = text
            translationContent = null
        }

        val doc = parseLines(mainContent) ?: return null
        if (translationContent.isNullOrBlank()) return doc

        val translationEntries = parsePlainTimeText(translationContent)
        if (translationEntries.isEmpty()) return doc

        return TranslationMerger.attach(doc, translationEntries, SidecarChannel.TRANSLATION)
    }

    /**
     * 解析**独立下发**的伴随载荷（平台以 LRC 形态给出的译文或罗马音）。
     *
     * @param text    伴随载荷明文
     * @return 「时间戳 → 文本」列表；无有效记录时返回空列表
     */
    fun parseSidecar(text: String?): List<TimeText> {
        if (text.isNullOrBlank()) return emptyList()
        val blocks = extractXmlBlocks(text)
        val content = if (blocks != null) (blocks.translation ?: blocks.main ?: return emptyList()) else text
        return parsePlainTimeText(content)
    }

    private class XmlBlocks(val main: String?, val translation: String?)

    /**
     * 识别并拆出 XML 中主歌词与译文两个块。
     *
     * 兼容两种真实排布：
     * <ul>
     *   <li>{@code <Lyric_1 LyricContent="main"/>} + {@code <Lyric_2 LyricContent="trans"/>}</li>
     *   <li>{@code <Lyric_1 LyricType="1" LyricContent="main"/>} + {@code <Lyric_1 LyricType="2" LyricContent="trans"/>}</li>
     * </ul>
     * 属性顺序不作要求。
     *
     * @return 拆解结果；文本不是 XML 包裹形态时返回 null
     */
    private fun extractXmlBlocks(text: String): XmlBlocks? {
        if (!text.contains("<Lyric_")) return null

        // 按 <Lyric_n ...> 整标签切块，再在属性串内独立判定 LyricType，
        // 因此 LyricContent 与 LyricType 的书写顺序不影响识别
        val mainParts = StringBuilder()
        val translationParts = StringBuilder()

        for (match in LYRIC_TAG_PATTERN.findAll(text)) {
            val tagIndex = match.groupValues[1]
            val attributes = match.groupValues[2]

            val content = LYRIC_CONTENT_ATTR.find(attributes)?.groupValues?.get(1) ?: continue
            val lyricType = LYRIC_TYPE_ATTR.find(attributes)?.groupValues?.get(1)
            val decoded = LyricText.decodeEntities(content)

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

    /**
     * 解析带逐字标签的主体歌词行。
     */
    private fun parseLines(content: String): LyricDoc? {
        val offset = OFFSET_PATTERN.find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val lines = ArrayList<LyricLine>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (TAG_PATTERN.matches(line)) continue

            val lineMatch = LINE_PATTERN.find(line) ?: continue
            val lineStart = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = lineMatch.groupValues[2].toLongOrNull() ?: 0L
            val body = lineMatch.groupValues[3]

            val startMs = lineStart + offset
            val durationMs = if (lineDuration > 0) lineDuration else DEFAULT_LINE_DURATION_MS
            val endMs = startMs + durationMs

            val parsed = parseBody(body, startMs, endMs)
            if (parsed.text.isBlank()) continue
            lines += parsed
        }

        if (lines.isEmpty()) return null
        lines.sortBy { it.startMs }
        return LyricDoc(lines = lines)
    }

    /**
     * 解析行体，产出逐字切片与重建后的行文本。
     */
    private fun parseBody(body: String, startMs: Long, endMs: Long): LyricLine {
        val words = ArrayList<LyricWord>()
        val textBuilder = StringBuilder()
        var consumed = 0

        val matches = WORD_PATTERN.findAll(body).toList()

        // 词起始有两种真实排布：**歌内绝对毫秒** 与 **行内相对偏移**。二者必须区分，
        // 否则整行逐字时间会整体偏移一个行起始的量。
        //
        // 判据取首词：绝对排布下首词原始值不会小于行起始；相对排布下它是个小偏移。
        // 必须**逐行**判定而非逐词——同一行内混用两种解释会自相矛盾。
        val absoluteTime = matches.firstOrNull()
            ?.groupValues?.get(2)?.toLongOrNull()
            ?.let { it >= startMs }
            ?: true

        for (match in matches) {
            val wordText = LyricText.decodeEntities(LyricText.cleanInvisibleChars(match.groupValues[1]))
            val rawStart = match.groupValues[2].toLongOrNull()
            val rawDuration = match.groupValues[3].toLongOrNull() ?: 0L
            consumed = match.range.last + 1

            if (rawStart == null || wordText.isEmpty()) continue

            val wordStart = if (absoluteTime) rawStart else startMs + rawStart
            val wordEnd = if (rawDuration > 0) wordStart + rawDuration else wordStart + LyricText.fallbackWordDuration(wordText)

            words += LyricWord(wordText, wordStart, wordEnd)
            textBuilder.append(wordText)
        }

        // 末尾没有任何时间标签的残余文字（畸形载荷）同样属于本行，不能丢
        if (consumed < body.length) {
            textBuilder.append(
                LyricText.decodeEntities(LyricText.cleanInvisibleChars(body.substring(consumed)))
            )
        }

        if (words.isEmpty()) {
            val plain = LyricText.decodeEntities(
                LyricText.cleanInvisibleChars(WORD_TAG_ONLY.replace(body, ""))
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

    /**
     * 解析不含逐字标签的伴随载荷（译文 / 罗马音）。
     */
    private fun parsePlainTimeText(content: String): List<TimeText> {
        val entries = ArrayList<TimeText>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (TAG_PATTERN.matches(line)) continue

            val lineMatch = LINE_PATTERN.find(line) ?: continue
            val startMs = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val text = LyricText.decodeEntities(
                LyricText.cleanInvisibleChars(WORD_TAG_ONLY.replace(lineMatch.groupValues[3], ""))
            ).trim()

            if (text.isEmpty()) continue
            entries += TimeText(startMs = startMs, text = text)
        }
        return entries
    }
}
