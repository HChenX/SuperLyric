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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hchen.superlyric.kernel.merge.SidecarChannel
import com.hchen.superlyric.kernel.merge.TimeText
import com.hchen.superlyric.kernel.merge.TranslationMerger
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.text.LyricText

/**
 * 汽水音乐（Soda / Luna）与波点 JSON 分支的原生逐字歌词解析器
 *
 * ## 结构
 * `sentences[]` 数组可能被包裹在多个层级下，逐层探测：
 * `sentences` → `lyrics.sentences` → `audioWithLyricsOption.*`
 * → `track_page.*` → `loaderData.*`。
 *
 * ```
 * sentence: { startMs, endMs, text, words: [ { text, startMs, endMs } ] }
 * 译文位于:  translations.cn
 * ```
 *
 * ## 三条判据
 * - **译文来源**：`translations.cn` 支持两种实际形态——与 `sentences` 平行的对象数组，
 *   或 LRC 形态的字符串——两种形态都归一为 [TimeText]，再统一走 [TranslationMerger] 对齐
 * - **相对时间判定**：以**逐句局部比较**判断词时间戳是否为行内相对值——仅当词的 `startMs`
 *   小于所属句的 `startMs` 时才视其为行内相对偏移。若改用 `lineStart > 1000` 这类**全局阈值**，
 *   曲目开头 1 秒内的行会全部误判，逐字时序坍缩到行首
 * - **句内插值**：词缺失时间戳时按句时长与字长比例插值，而不是一律堆到句首
 *
 * @author 焕晨HChen
 */
object SodaJsonParser {

    private const val DEFAULT_SENTENCE_DURATION_MS = 2000L

    /**
     * 句数上限
     *
     * 守的是**本层**的分配与逐句处理：`sentences` 的规模决定 `ArrayList` 的预分配，
     * 也决定下面这一轮要跑多少次解析。报文的 JSON 早已被上游物化成对象树，这个上限拦不住
     * 那一次分配——要防它得在**读取侧**设界（`ResponseBodies.readTextBounded`）。
     *
     * 正常曲目连千行都到不了，超限只可能是畸形载荷；代价是超过上限的**合法**载荷会被
     * 静默截断（内核没有日志出口，只能静默）
     */
    private const val MAX_SENTENCES = 4096

    /**
     * 判定文本是否为该 JSON 形态
     */
    fun canParse(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") && trimmed.contains("\"sentences\"")
    }

    /**
     * 解析 JSON 歌词正文
     *
     * @param text 原始 JSON 字符串
     * @return 解析结果；无有效歌词行时返回 null
     */
    fun parse(text: String?): LyricDoc? {
        if (!canParse(text)) return null

        // 只接**解析失败**这一类（JSON 语法错、形状不是对象），不接 Error：
        // 把 StackOverflowError / OutOfMemoryError 一起吞成 null，等于把"读不出来"
        // 伪装成"解析结果为空"，而下层把它当终态结论用
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val sentences = findSentencesArray(root, 0) ?: return null
        if (sentences.size() == 0) return null

        val limit = sentences.size().coerceAtMost(MAX_SENTENCES)
        val lines = ArrayList<LyricLine>(limit)
        for (index in 0 until limit) {
            val sentence = sentences.get(index) as? JsonObject ?: continue
            val line = parseSentence(sentence) ?: continue
            lines += line
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.startMs }

        val translations = parseTranslations(root)
        val doc = LyricDoc(lines = lines)
        return if (translations.isEmpty()) {
            doc
        } else {
            TranslationMerger.attach(doc, translations, SidecarChannel.TRANSLATION)
        }
    }

    private fun parseSentence(sentence: JsonObject): LyricLine? {
        val startMs = sentence.long("startMs", "start_ms") ?: 0L
        val rawEndMs = sentence.long("endMs", "end_ms") ?: 0L
        val endMs = if (rawEndMs > startMs) rawEndMs else startMs + DEFAULT_SENTENCE_DURATION_MS

        val wordsArray = sentence.array("words")
        val words = ArrayList<LyricWord>()
        val textBuilder = StringBuilder()

        if (wordsArray != null) {
            for (element in wordsArray) {
                val wordObject = element as? JsonObject ?: continue
                // 不可 trim：拉丁词元的尾随空格是文本的一部分，trim 会把 "Hello world" 变成 "Helloworld"
                val wordText = LyricText.cleanInvisibleChars(wordObject.string("text"))
                if (wordText.isBlank()) continue

                val rawWordStart = wordObject.long("startMs", "start_ms")
                val rawWordEnd = wordObject.long("endMs", "end_ms")

                val wordStart: Long
                val wordEnd: Long
                if (rawWordStart == null) {
                    wordStart = startMs
                    wordEnd = startMs + LyricText.fallbackWordDuration(wordText)
                } else {
                    // 仅当词起始早于所属句起始时，才认定为行内相对偏移
                    val isRelative = rawWordStart < startMs
                    wordStart = if (isRelative) startMs + rawWordStart else rawWordStart
                    val resolvedEnd = when {
                        rawWordEnd == null -> wordStart + LyricText.fallbackWordDuration(wordText)
                        isRelative -> startMs + rawWordEnd
                        else -> rawWordEnd
                    }
                    wordEnd = if (resolvedEnd > wordStart) resolvedEnd
                    else wordStart + LyricText.fallbackWordDuration(wordText)
                }

                words += LyricWord(wordText, wordStart, wordEnd)
                textBuilder.append(wordText)
            }
        }

        val text = if (words.isNotEmpty()) {
            textBuilder.toString()
        } else {
            LyricText.cleanInvisibleChars(sentence.string("text")).trim()
        }
        if (text.isEmpty()) return null

        return LyricLine(
            startMs = startMs,
            endMs = endMs,
            text = text,
            words = words,
        )
    }

    /**
     * 解析 `translations.cn`
     *
     * 该字段在真实载荷中有两种形态，均归一为 [TimeText]：
     * - 与 `sentences` 平行的对象数组，逐项读取 `startMs` 与 `text`
     * - LRC 形态字符串（`[mm:ss.xx]译文`）
     *
     * 形态无法识别时返回空列表——此时只丢失译文，绝不影响主歌词。
     */
    private fun parseTranslations(root: JsonObject): List<TimeText> {
        val translations = root.get("translations") as? JsonObject ?: return emptyList()
        val cn = translations.get("cn") ?: return emptyList()

        if (cn.isJsonArray) {
            val entries = ArrayList<TimeText>()
            for (element in cn.asJsonArray) {
                val item = element as? JsonObject ?: continue
                val startMs = item.long("startMs", "start_ms") ?: continue
                val text = LyricText.cleanInvisibleChars(item.string("text")).trim()
                if (text.isEmpty()) continue
                entries += TimeText(startMs, text)
            }
            return entries
        }

        if (cn.isJsonPrimitive) {
            return parseLrcLike(cn.asString)
        }
        return emptyList()
    }

    private val LRC_LINE = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]\s*(.*)$""")

    private fun parseLrcLike(raw: String): List<TimeText> {
        val entries = ArrayList<TimeText>()
        for (line in raw.lineSequence()) {
            val match = LRC_LINE.find(line.trim()) ?: continue
            val text = LyricText.cleanInvisibleChars(match.groupValues[4]).trim()
            if (text.isEmpty()) continue

            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val fractionRaw = match.groupValues[3]
            val fraction = when {
                fractionRaw.isEmpty() -> 0L
                fractionRaw.length == 1 -> fractionRaw.toLongOrNull()?.times(100) ?: 0L
                fractionRaw.length == 2 -> fractionRaw.toLongOrNull()?.times(10) ?: 0L
                else -> fractionRaw.take(3).toLongOrNull() ?: 0L
            }
            entries += TimeText(minutes * 60_000L + seconds * 1_000L + fraction, text)
        }
        return entries
    }

    /**
     * 容器嵌套深度上限
     *
     * 递归只沿固定几个键下降，但同一个键可以层层套（`{"lyrics":{"lyrics":{…}}}`），
     * 因此深度并不天然有界。真实载荷最多两层，超限即判形态不符
     */
    private const val MAX_CONTAINER_DEPTH = 4

    private fun findSentencesArray(root: JsonObject, depth: Int): JsonArray? {
        root.array("sentences")?.let { return it }
        if (depth >= MAX_CONTAINER_DEPTH) return null
        for (key in NESTED_CONTAINERS) {
            val nested = root.get(key) as? JsonObject ?: continue
            findSentencesArray(nested, depth + 1)?.let { return it }
        }
        return null
    }

    private val NESTED_CONTAINERS = listOf(
        "lyrics",
        "audioWithLyricsOption",
        "track_page",
        "loaderData",
    )

    private fun JsonObject.long(vararg keys: String): Long? {
        for (key in keys) {
            val element: JsonElement = get(key) ?: continue
            if (!element.isJsonPrimitive) continue
            val value = runCatching { element.asLong }.getOrNull() ?: continue
            return value
        }
        return null
    }

    private fun JsonObject.string(key: String): String {
        val element: JsonElement = get(key) ?: return ""
        if (!element.isJsonPrimitive) return ""
        return runCatching { element.asString }.getOrDefault("")
    }

    private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
}
