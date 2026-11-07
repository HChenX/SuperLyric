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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.kernel.parse

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.text.LrcTimestamps
import com.hchen.superlyric.kernel.text.LyricText
import kotlin.math.max

/**
 * 酷我 / 波点 LRCX 明文解析器
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

    private class Entry(val line: LyricLine, val timed: Boolean)

    /**
     * 判定文本是否为 LRCX
     */
    fun canParse(text: String?): Boolean =
        !text.isNullOrEmpty() && KUWO_HEADER.containsMatchIn(text)

    /**
     * 解析 LRCX 明文
     *
     * @return 解析结果；非 LRCX 或无有效歌词行时返回 null
     */
    fun parse(text: String?): LyricDoc? {
        if (text.isNullOrBlank()) return null

        val header = KUWO_HEADER.find(text) ?: return null
        val (c, d) = decodeKey(header.groupValues[1])

        var title = ""
        var artist = ""
        var album = ""

        val entries = ArrayList<Entry>()
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
            val lineStart = LrcTimestamps.timestampOf(
                lineMatch.groupValues[1],
                lineMatch.groupValues[2],
                lineMatch.groupValues[3],
            )
            val content = trimmed.substring(lineMatch.range.last + 1)
            if (content.isBlank()) {
                discardedStarts.add(lineStart)
                continue
            }

            val line = parseLine(content, lineStart, c, d) ?: run {
                discardedStarts.add(lineStart)
                continue
            }

            // 判据必须看原始标签值，不能看 line.words：`<0,0>` 这类全零标签产出
            // 零时长词，而 parseLine 会给零时长词兜一个 fallback 时长，
            // 于是"有没有逐字"无从判断。
            // 部分文件的译文行也带标签但全是 `<0,0>`，等于没有逐字数据；
            // 不识别这一点，译文行会被当成普通主行，译文无法配对。
            val hasTimedWords = WORD_TAG_PATTERN.findAll(content).any { tag ->
                val v1 = tag.groupValues[1].toLongOrNull() ?: 0L
                val v2 = tag.groupValues[2].toLongOrNull() ?: 0L
                v1 != 0L || v2 != 0L
            }
            entries += Entry(line, hasTimedWords)
        }

        val translationByMain = HashMap<Int, String>()
        val promoted = ArrayList<LyricLine>()

        for (index in entries.indices) {
            val entry = entries[index]
            if (entry.timed) continue

            val previousMain = (index - 1 downTo 0).firstOrNull { entries[it].timed }
            val nextMain = (index + 1 until entries.size).firstOrNull { entries[it].timed }
            val start = entry.line.startMs

            when {
                // 主行在前、时间戳指向下一条主行：译文属于前一条主行
                previousMain != null && nextMain != null &&
                        start == entries[nextMain].line.startMs &&
                        start != entries[previousMain].line.startMs ->
                    translationByMain.putIfAbsent(previousMain, entry.line.text)

                // 译文与主行同时间戳，出现在主行之前或之后
                previousMain != null && start == entries[previousMain].line.startMs ->
                    translationByMain.putIfAbsent(previousMain, entry.line.text)

                // 文件开头的译文，其主行紧随其后且时间戳相同
                previousMain == null && nextMain != null && start == entries[nextMain].line.startMs ->
                    translationByMain.putIfAbsent(nextMain, entry.line.text)

                // 同一时间戳上还有一条内容为空、被丢弃的主行：这条无标签行就是它的译文，
                // 只是主行没有内容可挂。提升为主行会把译文当歌词下发。
                // 文件末尾的译文例外：它后面只剩一条空行，前一条主行才是它的归属，
                // 丢弃会让末句永远没有译文
                start in discardedStarts ->
                    if (index == entries.lastIndex && previousMain != null &&
                        translationByMain[previousMain] == null
                    ) {
                        translationByMain[previousMain] = entry.line.text
                    }

                // 唯一时间戳：没有逐字数据的正常主行，不是译文
                else -> promoted += entry.line
            }
        }

        val lines = ArrayList<LyricLine>(entries.size)
        for (index in entries.indices) {
            val entry = entries[index]
            if (!entry.timed) continue
            val translation = translationByMain[index]
            lines += if (translation != null) entry.line.copy(translation = translation) else entry.line
        }
        lines += promoted

        if (lines.isEmpty()) return null
        lines.sortBy { it.startMs }
        assignEndTimes(lines)

        return LyricDoc(
            lines = lines,
            title = title,
            artist = artist,
            album = album,
        )
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

            val hardEnd = minOf(nextStart, current.startMs + MAX_LINE_DURATION_MS)
            val end = maxOf(current.startMs, minOf(lastWordEnd, hardEnd))
            lines[index] = current.copy(endMs = end)
        }
    }

}
