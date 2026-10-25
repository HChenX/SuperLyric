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

import com.hchen.superlyric.kernel.merge.SidecarChannel
import com.hchen.superlyric.kernel.merge.TimeText
import com.hchen.superlyric.kernel.merge.TranslationMerger
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.parse.QrcParser
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

/** 伴随通道的解析、对齐与空值清理。 */
internal object SidecarMerger {

    private val autoParser = AutoParser()
    private val millisecondLine = Regex("""\[\d+,\d+]""")
    private const val NO_TRANSLATION_MARKER = "//"
    private val CJK = Regex("""[㐀-䶿一-鿿぀-ゟ゠-ヿ가-힯]""")

    fun attach(doc: LyricDoc, text: String?, channel: SidecarChannel): LyricDoc? {
        if (text.isNullOrBlank()) return null
        val entries = parse(text)
        if (entries.isEmpty()) return null
        return TranslationMerger.attach(doc, entries, channel)
    }

    fun withoutEmptySidecars(line: LyricLine): LyricLine {
        val translation = line.translation?.takeIf { it.isMeaningful() }
        val phonetic = line.phonetic?.takeIf { it.isMeaningful() }
        if (translation == line.translation && phonetic == line.phonetic) return line
        return line.copy(translation = translation, phonetic = phonetic)
    }

    fun isRomanization(candidate: String, mainText: String): Boolean {
        if (candidate.isEmpty() || CJK.containsMatchIn(candidate)) return false
        return CJK.containsMatchIn(mainText)
    }

    private fun parse(text: String): List<TimeText> {
        if (millisecondLine.containsMatchIn(text)) return QrcParser.parseSidecar(text)
        val parsed = autoParser.parse(text)
        if (parsed.lines.isEmpty()) return emptyList()
        return parsed.lines.map { TimeText(it.start.toLong(), lineText(it)) }
            .filter { it.text.isNotEmpty() }
    }

    private fun String.isMeaningful(): Boolean {
        val trimmed = trim()
        return trimmed.isNotEmpty() && trimmed != NO_TRANSLATION_MARKER
    }

    private fun lineText(line: ISyncedLine): String = when (line) {
        is SyncedLine -> line.content.trim()
        is KaraokeLine -> line.syllables.joinToString("") { it.content }.trim()
        else -> ""
    }
}
