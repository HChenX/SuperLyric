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
package com.hchen.superlyric.kernel.text

/**
 * 歌词文本净化与共用的时序兜底工具。
 *
 * @author 焕晨HChen
 */
object LyricText {

    /**
     * 单个逐字切片在宿主未给出可用时长时的兜底时长下限（毫秒）。
     */
    private const val MIN_WORD_DURATION_MS = 120L

    /**
     * 每个字符折算的兜底时长（毫秒）。
     */
    private const val MS_PER_CHAR = 150L

    /**
     * 需要剥离的零宽 / 控制字符的码点。
     *
     * 这些字符在 `String.length()` 中占位，但在底层排版引擎（HarfBuzz / Skia）的字符簇
     * 解析中不占视觉字位。下游按字符索引切片高亮时会越界并触发
     * `Canvas.drawTextRun` 的 `IndexOutOfBoundsException`，导致系统界面崩溃。
     *
     * 以码点而非字面量声明，是为了避免源码中混入不可见字符本身。
     *
     * 覆盖：零宽空格 U+200B、零宽不连字 U+200C、零宽连字 U+200D、BOM U+FEFF、
     * 回车 U+000D、换行 U+000A、NUL U+0000（可由 `&#0;` 实体解码得到，排版引擎同样不适）
     */
    private val INVISIBLE_CODE_POINTS = intArrayOf(
        0x200B, 0x200C, 0x200D, 0xFEFF, 0x000D, 0x000A, 0x0000,
    )

    private fun isInvisible(ch: Char): Boolean {
        val code = ch.code
        for (candidate in INVISIBLE_CODE_POINTS) {
            if (candidate == code) return true
        }
        return false
    }

    /**
     * 剥离上述零宽 / 控制字符。
     */
    fun cleanInvisibleChars(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        if (text.none(::isInvisible)) return text
        val builder = StringBuilder(text.length)
        for (ch in text) {
            if (!isInvisible(ch)) builder.append(ch)
        }
        return builder.toString()
    }

    /**
     * 宿主未提供可用时长时的逐字切片兜底时长。
     */
    fun fallbackWordDuration(text: String): Long =
        maxOf(MIN_WORD_DURATION_MS, text.length.toLong() * MS_PER_CHAR)

    private val ENTITY_PATTERN = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
    )

    /**
     * 还原 XML / HTML 实体。
     *
     * QRC 与 TTML 的文本内容以实体形式转义（尤其 `&#39;` 与 `&apos;`），
     * 不还原会在界面上出现原始实体串。
     */
    fun decodeEntities(text: String?): String {
        if (text.isNullOrEmpty() || !text.contains('&')) return text ?: ""
        return ENTITY_PATTERN.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.let { codePointToString(it) } ?: match.value

                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.let { codePointToString(it) } ?: match.value

                else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
            }
        }
    }

    /**
     * 非法码点返回 `null`，由调用方**原样保留实体文本**
     *
     * 若返回空串，那个实体连同它的字会一起消失且不留痕迹——用户看到的是"莫名少了一个字"，
     * 而我们连查都无从查起
     */
    private fun codePointToString(codePoint: Int): String? =
        if (codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
            String(Character.toChars(codePoint))
        } else {
            null
        }
}
