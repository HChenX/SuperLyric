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

import com.google.gson.JsonParser
import com.hchen.superlyric.kernel.merge.TimeText

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * KRC 头部的 `[language:...]` 伴随块
 *
 * 平台把译文与罗马音放在 base64 JSON 里，数组按主歌词行序一一对应；主行本身
 * 不带通道标记。必须在主歌词解析前拆出，再按行首时间挂回，否则伴随块会被
 * 当作未知指令丢弃。
 */
internal object KrcLanguage {

    data class Sidecars(
        val main: String,
        val translation: List<TimeText>,
        val phonetic: List<TimeText>,
    )

    private const val LANGUAGE_PREFIX = "[language:"
    private const val MAX_TAG_CHARS = 1_000_000
    private const val MAX_LINES = 5_000
    private val KRC_LINE = Regex("""^\[(\d+),(\d+)]""")

    /**
     * 拆出主歌词与两条伴随通道；没有伴随块时返回 null
     *
     * 伴随数组长度与主歌词行数不一致时整条丢弃：错位的译文会被挂到别的行上，
     * 比没有译文更糟。
     */
    fun extract(text: String): Sidecars? {
        if (!text.contains(LANGUAGE_PREFIX)) return null

        val starts = ArrayList<Long>()
        val payloads = ArrayList<String>()
        val main = StringBuilder(text.length)
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(LANGUAGE_PREFIX) && trimmed.endsWith("]")) {
                payloads += trimmed.substring(LANGUAGE_PREFIX.length, trimmed.length - 1)
                continue
            }
            main.append(line).append('\n')
            KRC_LINE.find(trimmed)?.let { starts += it.groupValues[1].toLong() }
        }
        if (payloads.isEmpty()) return null
        if (starts.size > MAX_LINES) return null

        val translation = LinkedHashSet<TimeText>()
        val phonetic = LinkedHashSet<TimeText>()
        for (payload in payloads) {
            decode(payload, starts, translation, phonetic)
        }
        if (translation.isEmpty() && phonetic.isEmpty()) {
            return Sidecars(main.toString().trimEnd('\n'), emptyList(), emptyList())
        }
        return Sidecars(
            main = main.toString().trimEnd('\n'),
            translation = translation.toList(),
            phonetic = phonetic.toList(),
        )
    }

    private fun decode(
        payload: String,
        starts: List<Long>,
        translation: MutableSet<TimeText>,
        phonetic: MutableSet<TimeText>,
    ) {
        if (payload.isEmpty() || payload.length > MAX_TAG_CHARS) return
        val json = runCatching {
            String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
        }.getOrNull() ?: return
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        val content = runCatching { root.getAsJsonArray("content") }.getOrNull() ?: return

        for (element in content) {
            val item = runCatching { element.asJsonObject }.getOrNull() ?: continue
            // 实测 type 0 是罗马音、type 1 是译文；language 字段在样本中恒为 0，
            // 不能用来区分通道。类型未知时宁可不挂，也不猜错通道。
            val type = runCatching { item.get("type").asInt }.getOrNull() ?: continue
            val channel = when (type) {
                0 -> phonetic
                1 -> translation
                else -> continue
            }
            val lines = runCatching { item.getAsJsonArray("lyricContent") }.getOrNull() ?: continue
            if (lines.size() != starts.size) continue

            for (index in starts.indices) {
                val text = runCatching {
                    lines[index].asJsonArray.joinToString("") { part -> part.asString }
                }.getOrNull()?.trim() ?: continue
                if (text.isEmpty() || text == "//") continue
                channel += TimeText(starts[index], text)
            }
        }
    }
}
