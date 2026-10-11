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
import kotlin.math.max

/**
 * 酷我 / 波点 LRCX 明文解析器
 *
 * ## 格式
 * LRCX 以头部标签 `[kuwo:KEY]` 与普通 LRC 区分（KEY 为**八进制**整数）：
 * ```
 * c = KEY / 10      d = KEY % 10
 * 词起始（行内相对）= (v1 + v2) / (c * 2)
 * 词结束（行内相对）= ((v1 - v2) / (d * 2)) + 词起始
 * ```
 * 该算式与宿主自身解析器（`com.tme.push.a3.f`）逐式一致。
 * 文本归属规则也与之一致：**某个词标签之后、下一个词标签之前**的文字属于该标签。
 *
 * ## 译文判定
 * 只有当某行的时间戳与某条逐字行的时间戳**完全相同**时，才认定它是那条主行的译文——
 * 判据是"成对出现"这一结构事实，而非"缺少标签"这一缺席事实。若把任何不含 `<v1,v2>`
 * 标签的时间行都当作译文，没有逐字数据的**正常主行**会被挂到相邻行当译文（翻译错位），
 * 或找不到匹配时被静默丢弃（歌词丢失）。
 *
 * ## 缺密钥不猜
 * 必须存在 `[kuwo:]` 头部标签；没有头部就不是 LRCX，交给通用 LRC 解析器处理。
 * 加密的 `(v1,v2)` 与明文的 `(offset,dur)` 形式相近，强行误读会产出完全错误的逐字时序。
 *
 * @author 焕晨HChen
 */
object KuwoLrcxParser {

    /** LRCX 头部标签；其存在即判定为 LRCX */
    private val KUWO_HEADER = Regex("""\[kuwo:\s*([^]]*)]""", RegexOption.IGNORE_CASE)

    private val META_PATTERN = Regex("""\[(ti|ar|al):\s*(.+?)]""", RegexOption.IGNORE_CASE)

    /** 行首时间戳：`[mm:ss]` / `[mm:ss.xx]` / `[mm:ss:xx]` */
    private val LINE_TIME_PATTERN = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 逐字标签：`<v1,v2>` 或 `<v1,v2,v3>` */
    private val WORD_TAG_PATTERN = Regex("""<(-?\d+),(-?\d+)(?:,-?\d+)?>""")

    private const val MAX_LINE_DURATION_MS = 4000L

    /**
     * 判定文本是否为 LRCX
     */
    fun canParse(text: String?): Boolean =
        !text.isNullOrEmpty() && KUWO_HEADER.containsMatchIn(text)

    /**
     * 解析 LRCX 明文
     *
     * @param text 原始 LRCX 文本
     * @return 解析结果；非 LRCX 或无有效歌词行时返回 null
     */
    fun parse(text: String?): LyricDoc? {
        if (text.isNullOrBlank()) return null

        val header = KUWO_HEADER.find(text) ?: return null
        val (c, d) = decodeKey(header.groupValues[1])

        var title = ""
        var artist = ""
        var album = ""

        val taggedLines = ArrayList<LyricLine>()
        val untaggedLines = ArrayList<LyricLine>()

        // 被识别成"行"、却没能产出任何文本的时间戳。必须记下来：译文的配对要靠主行的时间戳，
        // 一条主行被丢弃而时间戳没留下痕迹，它的译文就会在下面被误判成独立主行
        val discardedStarts = HashSet<Long>()

        for (raw in text.lineSequence()) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue

            val meta = META_PATTERN.find(trimmed)
            if (meta != null && !LINE_TIME_PATTERN.containsMatchIn(trimmed)) {
                when (meta.groupValues[1].lowercase()) {
                    "ti" -> if (title.isEmpty()) title = meta.groupValues[2].trim()
                    "ar" -> if (artist.isEmpty()) artist = meta.groupValues[2].trim()
                    "al" -> if (album.isEmpty()) album = meta.groupValues[2].trim()
                }
                continue
            }

            val lineMatch = LINE_TIME_PATTERN.find(trimmed) ?: continue
            val lineStart = parseTime(
                lineMatch.groupValues[1],
                lineMatch.groupValues[2],
                lineMatch.groupValues[3],
            )
            val content = trimmed.substring(lineMatch.range.last + 1)
            if (content.isBlank()) {
                discardedStarts.add(lineStart)
                continue
            }

            val line = parseLine(content, lineStart, c, d)
            if (line == null) {
                discardedStarts.add(lineStart)
                continue
            }

            // 判据必须看**原始标签值**，不能看 line.words：`<0,0>` 这类全零标签产出的
            // 是零时长词，而 parseLine 会给零时长词兜一个 fallback 时长，
            // 于是"有没有逐字"就看不出来了。
            // 真机实测：部分文件的**译文行也带标签但全是 `<0,0>`**，那等于没有逐字数据；
            // 若不认这一点，译文行会被当成普通主行，译文永远配不上。
            val hasTimedWords = WORD_TAG_PATTERN.findAll(content).any { tag ->
                val v1 = tag.groupValues[1].toLongOrNull() ?: 0L
                val v2 = tag.groupValues[2].toLongOrNull() ?: 0L
                v1 != 0L || v2 != 0L
            }
            if (hasTimedWords) taggedLines.add(line) else untaggedLines.add(line)
        }

        // 字幕行：无逐字数据，且时间戳与某条逐字行完全一致 → 判定为那条行的译文
        val translationEntries = ArrayList<TimeText>()
        val mainLineStarts = taggedLines.mapTo(HashSet()) { it.startMs }
        for (untagged in untaggedLines) {
            when {
                untagged.startMs in mainLineStarts ->
                    translationEntries += TimeText(untagged.startMs, untagged.text)

                // 同一时间戳上还有一条内容为空、被丢弃的主行：这条无标签行就是它的译文，
                // 只是主行没内容可挂。提升为主行会把这句译文当歌词下发出去 ——
                // 真机实测（波点）末行正是如此：译文成了整首歌的最后一句"歌词"
                untagged.startMs in discardedStarts -> Unit

                else -> taggedLines.add(untagged)
            }
        }

        if (taggedLines.isEmpty()) return null
        taggedLines.sortBy { it.startMs }
        assignEndTimes(taggedLines)

        val doc = LyricDoc(
            lines = taggedLines,
            title = title,
            artist = artist,
            album = album,
        )

        return if (translationEntries.isEmpty()) {
            doc
        } else {
            TranslationMerger.attach(doc, translationEntries, SidecarChannel.TRANSLATION)
        }
    }

    /**
     * 解出八进制密钥：非法或退化（c/d 为 0）时回退为 1，
     * 与宿主 `s.a(str, 8)` 的行为保持一致。
     */
    private fun decodeKey(rawKey: String): Pair<Int, Int> {
        var key = rawKey
        if (key.contains("][")) key = key.substring(0, key.indexOf("]["))
        val value = key.trim().toIntOrNull(8) ?: return 1 to 1
        val c = value / 10
        val d = value % 10
        return (if (c > 0) c else 1) to (if (d > 0) d else 1)
    }

    private fun parseLine(content: String, lineStartMs: Long, c: Int, d: Int): LyricLine? {
        val tags = WORD_TAG_PATTERN.findAll(content).toList()
        if (tags.isEmpty()) {
            val text = LyricText.cleanInvisibleChars(content).trim()
            if (text.isEmpty()) return null
            return LyricLine(startMs = lineStartMs, endMs = lineStartMs, text = text)
        }

        val words = ArrayList<LyricWord>(tags.size)
        val textBuilder = StringBuilder()

        for ((index, tag) in tags.withIndex()) {
            val v1 = tag.groupValues[1].toLongOrNull() ?: continue
            val v2 = tag.groupValues[2].toLongOrNull() ?: continue

            val textEnd = if (index + 1 < tags.size) tags[index + 1].range.first else content.length
            val segment = content.substring(tag.range.last + 1, textEnd)
            // 首个标签之前的前导文字不能丢，归入第一个词
            val leading = if (index == 0) content.substring(0, tag.range.first) else ""
            val wordText = LyricText.cleanInvisibleChars(leading + segment)
            if (wordText.isEmpty()) continue

            val relStart = (v1 + v2) / (c * 2L)
            val relEnd = ((v1 - v2) / (d * 2L)) + relStart
            val wordStart = lineStartMs + max(0L, relStart)
            val wordEnd = lineStartMs + max(relStart, relEnd)

            words += LyricWord(
                text = wordText,
                startMs = wordStart,
                endMs = if (wordEnd > wordStart) wordEnd else wordStart + LyricText.fallbackWordDuration(wordText),
            )
            textBuilder.append(wordText)
        }

        if (words.isEmpty()) {
            val text = LyricText.cleanInvisibleChars(WORD_TAG_PATTERN.replace(content, "")).trim()
            if (text.isEmpty()) return null
            return LyricLine(startMs = lineStartMs, endMs = lineStartMs, text = text)
        }

        return LyricLine(
            startMs = lineStartMs,
            endMs = lineStartMs,
            text = textBuilder.toString(),
            words = words,
        )
    }

    /**
     * LRCX 不声明行结束时间，按下一行起始推导，并在最后一行上施加时长上限
     */
    private fun assignEndTimes(lines: MutableList<LyricLine>) {
        for (index in lines.indices) {
            val current = lines[index]
            val lastWordEnd = current.words.maxOfOrNull { it.endMs } ?: current.startMs
            val nextStart = if (index + 1 < lines.size) lines[index + 1].startMs
            else current.startMs + MAX_LINE_DURATION_MS

            val end = maxOf(
                current.startMs,
                lastWordEnd,
                minOf(nextStart, current.startMs + MAX_LINE_DURATION_MS),
            )
            lines[index] = current.copy(endMs = end)
        }
    }

    private fun parseTime(minStr: String, secStr: String, fracStr: String): Long {
        val minutes = minStr.toLongOrNull() ?: 0L
        val seconds = secStr.toLongOrNull() ?: 0L
        val fraction = when {
            fracStr.isEmpty() -> 0L
            fracStr.length == 1 -> fracStr.toLongOrNull()?.times(100) ?: 0L
            fracStr.length == 2 -> fracStr.toLongOrNull()?.times(10) ?: 0L
            else -> fracStr.take(3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1_000L + fraction
    }
}
