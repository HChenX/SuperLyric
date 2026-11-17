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

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.text.LyricText
import com.hchen.superlyric.kernel.text.LyricTextLimits
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.math.MathContext

/** Musixmatch richsync 原始秒时间轴；词偏移相对所在行，词间空格属于正文。 */
internal object RichSyncJsonParser {

    private val ARRAY_OBJECT = Regex("""^\s*\[\s*\{""")
    private val START = Regex(""""ts"\s*:\s*-?\d""")
    private val END = Regex(""""te"\s*:\s*-?\d""")
    private val WORDS = Regex(""""l"\s*:\s*\[""")
    private val MIN_SECONDS = BigDecimal.valueOf(Long.MIN_VALUE, 3)
    private val MAX_SECONDS = BigDecimal.valueOf(Long.MAX_VALUE, 3)

    private data class Word(val text: String, val offsetSeconds: BigDecimal)

    fun canParse(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        // JSON 常整首压在一行；仅用有界前缀找结构，不让单行正则扫描全文。
        val prefix = text.take(LyricTextLimits.MAX_LINE_CHARS)
        return ARRAY_OBJECT.containsMatchIn(prefix) && START.containsMatchIn(prefix) &&
                END.containsMatchIn(prefix) && WORDS.containsMatchIn(prefix)
    }

    fun parse(text: String?): LyricDoc? {
        if (!canParse(text)) return null
        val input = text ?: return null
        return try {
            JsonReader(StringReader(input)).use { reader ->
                reader.strictness = Strictness.STRICT
                if (reader.peek() != JsonToken.BEGIN_ARRAY) return null
                reader.beginArray()
                val lines = ArrayList<LyricLine>()
                while (reader.hasNext()) {
                    readLine(reader)?.let { lines += it }
                }
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT || lines.isEmpty()) return null
                LyricDoc(lines = lines)
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun readLine(reader: JsonReader): LyricLine? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var startSeconds: BigDecimal? = null
        var endSeconds: BigDecimal? = null
        var words: List<Word>? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "ts" -> startSeconds = readSeconds(reader)
                "te" -> endSeconds = readSeconds(reader)
                "l" -> words = readWords(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val start = startSeconds ?: return null
        val end = endSeconds ?: return null
        val slices = words ?: return null
        val text = slices.joinToString("") { it.text }
        if (text.isBlank()) return null
        return LyricLine(
            startMs = millis(start),
            endMs = millis(end),
            text = text,
            words = slices.mapIndexed { index, word ->
                LyricWord(
                    text = word.text,
                    startMs = millis(start.add(word.offsetSeconds, MathContext.DECIMAL128)),
                    endMs = slices.getOrNull(index + 1)
                        ?.let { millis(start.add(it.offsetSeconds, MathContext.DECIMAL128)) } ?: millis(end),
                )
            },
        )
    }

    private fun readWords(reader: JsonReader): List<Word>? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }
        val words = ArrayList<Word>()
        var valid = true
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                valid = false
                continue
            }
            var text: String? = null
            var offsetSeconds: BigDecimal? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "c" -> if (reader.peek() == JsonToken.STRING) {
                        text = LyricText.cleanInvisibleChars(reader.nextString())
                    } else {
                        reader.skipValue()
                    }

                    "o" -> offsetSeconds = readSeconds(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val content = text
            val offset = offsetSeconds
            if (content == null || offset == null) {
                valid = false
            } else if (content.isNotEmpty()) {
                words += Word(content, offset)
            }
        }
        reader.endArray()
        return words.takeIf { valid }
    }

    private fun readSeconds(reader: JsonReader): BigDecimal? {
        if (reader.peek() != JsonToken.NUMBER) {
            reader.skipValue()
            return null
        }
        return BigDecimal(reader.nextString(), MathContext.DECIMAL128)
    }

    private fun millis(seconds: BigDecimal): Long {
        require(seconds >= MIN_SECONDS && seconds <= MAX_SECONDS) { "Richsync time exceeds milliseconds range" }
        return seconds.movePointRight(3).toLong()
    }
}
