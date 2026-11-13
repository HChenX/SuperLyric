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

import com.hchen.superlyric.kernel.map.SyncedLyricsMapper
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.text.LyricTextLimits
import com.mocharealm.accompanist.lyrics.core.parser.LyricifySyllableParser

/**
 * Lyricify Syllable 的项目侧检测与解析入口
 *
 * 库的检测器要求每个音节前有 Latin 字母，中文行 `[0]你(0,100)好(100,100)`
 * 会被判为不匹配。这里用结构判断补上 CJK；真正解析仍复用库，避免维护两套属性、
 * 对齐和伴奏行规则。
 */
internal object LyricifyTextParser {

    /** 行内音节：文字 + `(start,dur)`，文字允许空格和 CJK */
    private val SYLLABLE = Regex("""[^()\[\]\n]+\(\d+,\d+\)""")

    /** 普通 LRC 行首时间戳；这类行不属于 Lyricify */
    private val LRC_HEAD = Regex("""^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")

    fun canParse(text: String): Boolean {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            // 超长行只可能是畸形载荷：音节正则的回溯会按行宽平方增长
            if (line.isEmpty() || line.length > LyricTextLimits.MAX_LINE_CHARS) continue
            if (LRC_HEAD.containsMatchIn(line)) continue
            val count = SYLLABLE.findAll(line).count()
            // 单音节行只有带行属性时才足够可靠；普通文本里的一个括号数字对
            // 不足以判定整份载荷是 Lyricify。
            if (count >= 2 || (count == 1 && line.startsWith("["))) return true
        }
        return false
    }

    fun parse(text: String): LyricDoc? {
        if (!canParse(text)) return null
        val parsed = runCatching { LyricifySyllableParser.parse(text) }.getOrNull() ?: return null
        if (parsed.lines.isEmpty()) return null
        return SyncedLyricsMapper.toDoc(parsed)
    }
}
