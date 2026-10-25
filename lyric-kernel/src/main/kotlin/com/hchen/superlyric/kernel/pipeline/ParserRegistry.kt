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
package com.hchen.superlyric.kernel.pipeline

import com.hchen.superlyric.kernel.map.SyncedLyricsMapper
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.parse.KuwoLrcxParser
import com.hchen.superlyric.kernel.parse.QrcParser
import com.hchen.superlyric.kernel.parse.SodaJsonParser
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

/** 一种文本格式的解析器；输入已解密、已拆分伴随通道。 */
internal fun interface LyricParser {
    fun parse(text: String, stats: QrcParser.ParseStats?): LyricDoc?
}

/** 按格式选择解析器，保持与旧 LyricPipeline 相同的优先级。 */
internal object ParserRegistry {

    private val autoParser = AutoParser()

    fun parse(
        format: LyricFormat,
        text: String,
        split: CompanionSplitter.Split?,
        stats: QrcParser.ParseStats?,
    ): LyricDoc? = when (format) {
        LyricFormat.QRC -> QrcParser.parse(text, stats)
        LyricFormat.LRCX -> KuwoLrcxParser.parse(text) ?: parseByLibrary(text)
        LyricFormat.SODA_JSON -> SodaJsonParser.parse(text)
        LyricFormat.KRC, LyricFormat.GENERAL, LyricFormat.AUTO ->
            if (split != null) WordTagLrcParser.parse(text) ?: parseByLibrary(text)
            else parseByLibrary(text)
    }

    private fun parseByLibrary(text: String): LyricDoc? {
        val parsed = autoParser.parse(text)
        if (parsed.lines.isEmpty()) return null
        return SyncedLyricsMapper.toDoc(parsed)
    }
}
