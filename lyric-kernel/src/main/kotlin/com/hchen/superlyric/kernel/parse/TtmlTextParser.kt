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
import com.mocharealm.accompanist.lyrics.core.parser.TTMLParser

/**
 * TTML 文本的项目侧检测与解析入口
 *
 * 库的检测器只认标准 TTML 命名空间；部分 Apple/AMLL 变体省略命名空间但仍带
 * `<tt>` 与 `begin=`。这里放宽检测，解析仍交给库，保留其对译文、音译、
 * 对唱和伴奏行的全部处理。
 */
internal object TtmlTextParser {

    fun canParse(text: String): Boolean =
        text.contains("<tt") && text.contains("begin=")

    fun parse(text: String): LyricDoc? {
        if (!canParse(text)) return null
        val parsed = runCatching { TTMLParser().parse(text) }.getOrNull() ?: return null
        if (parsed.lines.isEmpty()) return null
        return SyncedLyricsMapper.toDoc(parsed)
    }
}
