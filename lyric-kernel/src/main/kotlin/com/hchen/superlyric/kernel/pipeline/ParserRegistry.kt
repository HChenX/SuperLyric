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
import com.hchen.superlyric.kernel.merge.SidecarChannel
import com.hchen.superlyric.kernel.merge.TranslationMerger
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.parse.KuwoLrcxParser
import com.hchen.superlyric.kernel.parse.QrcParser
import com.hchen.superlyric.kernel.parse.SodaJsonParser
import com.hchen.superlyric.kernel.text.LrcOffset
import com.hchen.superlyric.kernel.text.LrcTimestamps
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

/** 按格式选择解析器，保持固定优先级 */
internal object ParserRegistry {

    private val autoParser = AutoParser()

    fun parse(
        format: LyricFormat,
        text: String,
        split: CompanionSplitter.Split?,
        stats: QrcParser.ParseStats?,
    ): LyricDoc? = when (format) {
        LyricFormat.QRC -> parseQrc(text, stats)
        LyricFormat.LRCX -> KuwoLrcxParser.parse(text) ?: parseByLibrary(text)
        LyricFormat.SODA_JSON -> SodaJsonParser.parse(text)
        LyricFormat.KRC, LyricFormat.GENERAL, LyricFormat.AUTO ->
            if (split != null) WordTagLrcParser.parse(text) ?: parseByLibrary(text)
            else parseByLibrary(text)
    }

    /**
     * QRC 端点也可能返回 LRC 形态的明文
     *
     * 此时 [QrcParser.parse] 一行都读不出来，整首会被上层判为取词失败。兜底必须
     * 使用已抽好的主体块（而不是原始 XML），并把三段时间戳归一化后交给通用解析器；
     * 译文块同样要挂回，否则这两类载荷的译文会整段消失。
     */
    private fun parseQrc(text: String, stats: QrcParser.ParseStats?): LyricDoc? {
        QrcParser.parse(text, stats)?.let { return it }

        val fallback = QrcParser.fallbackContent(text, stats) ?: return null
        val lrcOffset = LrcOffset.parse(LrcTimestamps.normalize(fallback.main))
        var doc = parseByLibrary(lrcOffset.text) ?: return null

        val translation = fallback.translation
        if (!translation.isNullOrBlank()) {
            val entries = QrcParser.parseTimeText(LrcTimestamps.normalize(translation))
            if (entries.isNotEmpty()) {
                doc = TranslationMerger.attach(doc, entries, SidecarChannel.TRANSLATION)
            }
        }
        // 译文先按未平移的主行对齐，再整体平移；QRC 原生 offset 不经过这里。
        return if (lrcOffset.offsetMs != 0L) LrcOffset.apply(doc, lrcOffset.offsetMs) else doc
    }

    private fun parseByLibrary(text: String): LyricDoc? {
        val parsed = autoParser.parse(text)
        if (parsed.lines.isEmpty()) return null
        return SyncedLyricsMapper.toDoc(parsed)
    }
}
