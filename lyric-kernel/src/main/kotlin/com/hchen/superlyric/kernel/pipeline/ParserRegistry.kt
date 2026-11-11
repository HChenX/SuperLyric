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
import com.hchen.superlyric.kernel.parse.LyricifyTextParser
import com.hchen.superlyric.kernel.parse.QrcParser
import com.hchen.superlyric.kernel.parse.SodaJsonParser
import com.hchen.superlyric.kernel.parse.TtmlTextParser
import com.hchen.superlyric.kernel.text.LrcOffset
import com.hchen.superlyric.kernel.text.LrcTimestamps
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

/**
 * 歌词文本候选链
 *
 * 每个候选只负责一种形态；解析、finalize 和结构校验由 LyricPipeline 逐候选执行。
 * 候选顺序固定：明确格式优先，专用解析器失败后才进入通用文本兜底。
 */
internal object ParserRegistry {

    private val autoParser = AutoParser()

    data class Candidate(
        val id: String,
        val canParse: () -> Boolean,
        val parse: () -> LyricDoc?,
    )

    fun candidates(
        kind: LyricTextKind,
        text: String,
        split: CompanionSplitter.Split?,
        stats: QrcParser.ParseStats?,
    ): List<Candidate> {
        val result = ArrayList<Candidate>()
        var libraryAdded = false

        fun add(id: String, canParse: () -> Boolean, parse: () -> LyricDoc?) {
            result += Candidate(id, canParse, parse)
        }

        when (kind) {
            LyricTextKind.SODA_JSON -> add(
                "soda",
                { SodaJsonParser.canParse(text) },
                { SodaJsonParser.parse(text) },
            )

            LyricTextKind.QRC -> {
                add("qrc", { true }, { QrcParser.parse(text, stats) })
                add("qrc-lrc", { true }, { parseQrcFallback(text, stats) })
            }

            LyricTextKind.KRC -> {
                add("krc", { true }, { parseByLibrary(text) })
                libraryAdded = true
            }

            LyricTextKind.LRCX -> add(
                "lrcx",
                { KuwoLrcxParser.canParse(text) },
                { KuwoLrcxParser.parse(text) },
            )

            LyricTextKind.TTML -> {
                add(
                    "ttml",
                    { TtmlTextParser.canParse(text) },
                    { TtmlTextParser.parse(text) },
                )
                libraryAdded = true
            }

            LyricTextKind.LYRICIFY -> {
                add(
                    "lyricify",
                    { LyricifyTextParser.canParse(text) },
                    { LyricifyTextParser.parse(text) },
                )
                libraryAdded = true
            }

            LyricTextKind.YRC -> {
                add("yrc", { true }, { parseByLibrary(text) })
                libraryAdded = true
            }

            LyricTextKind.SALT -> add(
                "salt",
                { TrailingTimestampLrcParser.canParse(text) },
                { TrailingTimestampLrcParser.parse(text) },
            )

            LyricTextKind.LRC,
            LyricTextKind.UNKNOWN,
            -> Unit
        }

        // 结构化 JSON 没有通用文本兜底；其余格式失败后仍尝试其他明确结构和通用库。
        if (kind != LyricTextKind.SODA_JSON) {
            if (kind != LyricTextKind.TTML && TtmlTextParser.canParse(text)) {
                add("ttml-fallback", { true }, { TtmlTextParser.parse(text) })
            }
            if (kind != LyricTextKind.LYRICIFY && LyricifyTextParser.canParse(text)) {
                add("lyricify-fallback", { true }, { LyricifyTextParser.parse(text) })
            }
            if (kind != LyricTextKind.SALT && TrailingTimestampLrcParser.canParse(text)) {
                add("salt-fallback", { true }, { TrailingTimestampLrcParser.parse(text) })
            }
            if (split != null) {
                add("word-tag", { true }, { WordTagLrcParser.parse(text) })
            }
            if (!libraryAdded) {
                add("library", { true }, { parseByLibrary(text) })
            }
        }

        return result
    }

    /**
     * QRC 端点也可能返回 LRC 形态的明文
     *
     * 此时 [QrcParser.parse] 一行都读不出来，整首会被上层判为取词失败。兜底必须
     * 使用已抽好的主体块（而不是原始 XML），并把三段时间戳归一化后交给通用解析器；
     * 译文块同样要挂回，否则这两类载荷的译文会整段消失。
     */
    private fun parseQrcFallback(text: String, stats: QrcParser.ParseStats?): LyricDoc? {
        val fallback = QrcParser.fallbackContent(text, stats) ?: return null
        val lrcOffset = LrcOffset.parse(LrcTimestamps.canonicalize(fallback.main))
        var doc = parseByLibrary(lrcOffset.text) ?: return null

        val translation = fallback.translation
        if (!translation.isNullOrBlank()) {
            val entries = QrcParser.parseTimeText(LrcTimestamps.canonicalize(translation))
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
