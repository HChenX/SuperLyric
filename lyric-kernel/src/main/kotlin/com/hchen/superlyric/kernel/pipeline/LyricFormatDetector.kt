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

import com.hchen.superlyric.kernel.decode.QrcCipher
import com.hchen.superlyric.kernel.parse.KuwoLrcxParser
import com.hchen.superlyric.kernel.parse.LyricifyTextParser
import com.hchen.superlyric.kernel.parse.SodaJsonParser
import com.hchen.superlyric.kernel.parse.TtmlTextParser
import com.hchen.superlyric.kernel.text.LyricTextLimits

/**
 * 歌词文本的严格格式检测
 *
 * 只判断载荷的结构签名，不做全文解析。检测顺序先结构化格式，再逐字 LRC，
 * 最后普通 LRC；这样 Salt 的 trailing timestamp 不会被通用库当成普通 LRC。
 */
internal enum class LyricTextKind {
    SODA_JSON,
    QRC,
    KRC,
    LRCX,
    TTML,
    YRC,
    LYRICIFY,
    SALT,
    LRC,
    UNKNOWN,
}

internal object LyricFormatDetector {

    private val QRC_LINE = Regex("""\[\d+,\d+][^\n]*\(\d+,\d+\)""")
    private val YRC_LINE = Regex("""\[\d+,\d+][^\n]*\(\d+,\d+,\d+\)""")
    private val KRC_LINE = Regex("""\[\d+,\d+][^\n]*<\d+,\d+,\d+>""")
    private val LRC_LINE = Regex("""^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""", RegexOption.MULTILINE)

    fun detect(text: String): LyricTextKind {
        if (SodaJsonParser.canParse(text)) return LyricTextKind.SODA_JSON
        if (QrcCipher.isPlainQrc(text)) return LyricTextKind.QRC
        if (KuwoLrcxParser.canParse(text)) return LyricTextKind.LRCX
        if (LyricTextLimits.anyLineMatches(text, KRC_LINE)) return LyricTextKind.KRC
        if (LyricTextLimits.anyLineMatches(text, YRC_LINE)) return LyricTextKind.YRC
        if (LyricTextLimits.anyLineMatches(text, QRC_LINE)) return LyricTextKind.QRC
        if (TtmlTextParser.canParse(text)) return LyricTextKind.TTML
        if (TrailingTimestampLrcParser.canParse(text)) return LyricTextKind.SALT
        if (LyricifyTextParser.canParse(text)) return LyricTextKind.LYRICIFY
        if (LRC_LINE.containsMatchIn(text)) return LyricTextKind.LRC
        return LyricTextKind.UNKNOWN
    }
}
