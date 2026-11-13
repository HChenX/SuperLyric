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

import com.hchen.superlyric.kernel.model.LyricDoc

/**
 * 歌词载荷分类规则
 *
 * 这些判断只回答"这份载荷像不像整首歌词"，不把失败解释成平台无歌词。
 */
object LyricPayloadHeuristics {

    /** 短文本判定阈值；短文本通常是宿主辅助方法的单行 */
    const val MIN_FULL_TEXT_LENGTH = 200

    private val LRC_TIME_TAG = Regex("""\[\d{1,3}:\d{1,2}""")

    fun isShortText(text: String): Boolean = text.length < MIN_FULL_TEXT_LENGTH

    fun hasLrcTimeTag(text: String): Boolean = LRC_TIME_TAG.containsMatchIn(text)

    /**
     * 至少两个不同的起始时间，或首行起始非零；否则整份载荷没有可用时间轴
     */
    fun hasUsableTiming(doc: LyricDoc?): Boolean {
        if (doc == null || doc.isEmpty) return false
        val starts = doc.lines.map { it.startMs }.distinct()
        return starts.size >= 2 || (starts.size == 1 && starts[0] > 0L)
    }


}
