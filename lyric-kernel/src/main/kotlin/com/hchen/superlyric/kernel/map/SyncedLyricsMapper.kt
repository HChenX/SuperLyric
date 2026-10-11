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
package com.hchen.superlyric.kernel.map

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.text.LyricText
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/**
 * 将 `lyrics-core` 的统一模型 [SyncedLyrics] 映射为本模块的规范模型 [LyricDoc]。
 *
 * 这是"库世界"与"本模块世界"之间的**唯一**边界。库覆盖的格式
 * （TTML / 增强 LRC / KRC / YRC / Lyricify Syllable）经此转换；
 * 本模块自研的格式（QRC / LRCX / Soda）**直接产出** [LyricDoc]，不经过本映射，
 * 以避免无谓的有损往返。
 *
 * @author 焕晨HChen
 */
object SyncedLyricsMapper {

    /**
     * @param lyrics  库解析结果
     * @param lyricId 曲目唯一标识，由调用方注入
     */
    fun toDoc(lyrics: SyncedLyrics, lyricId: String? = null): LyricDoc {
        val lines = ArrayList<LyricLine>(lyrics.lines.size)
        for (line in lyrics.lines) {
            toLine(line)?.let { lines += it }
        }

        return LyricDoc(
            lines = lines,
            title = lyrics.title.trim(),
            artist = lyrics.artists.orEmpty().joinToString("/") { it.name }.trim(),
            lyricId = lyricId,
        )
    }

    private fun toLine(line: com.mocharealm.accompanist.lyrics.core.model.ISyncedLine): LyricLine? =
        when (line) {
            is SyncedLine -> plainLine(line)
            is KaraokeLine.MainKaraokeLine -> karaokeLine(line)
            is KaraokeLine.AccompanimentKaraokeLine -> accompanimentLine(line)
            else -> null
        }

    private fun plainLine(line: SyncedLine): LyricLine? {
        val text = LyricText.cleanInvisibleChars(line.content).trim()
        if (text.isEmpty()) return null
        return LyricLine(
            startMs = line.start.toLong(),
            endMs = line.end.toLong(),
            text = text,
            translation = line.translation?.let { LyricText.cleanInvisibleChars(it).trim() }?.ifEmpty { null },
            phonetic = line.phonetic?.let { LyricText.cleanInvisibleChars(it).trim() }?.ifEmpty { null },
        )
    }

    private fun karaokeLine(line: KaraokeLine.MainKaraokeLine): LyricLine? {
        val words = line.syllables.mapNotNull { syllable ->
            val text = LyricText.cleanInvisibleChars(syllable.content)
            if (text.isEmpty()) null
            else LyricWord(text, syllable.start.toLong(), syllable.end.toLong())
        }
        val text = words.joinToString("") { it.text }
        if (text.isEmpty()) return null

        return LyricLine(
            startMs = line.start.toLong(),
            endMs = line.end.toLong(),
            text = text,
            translation = line.translation?.let { LyricText.cleanInvisibleChars(it).trim() }?.ifEmpty { null },
            phonetic = line.phonetic?.let { LyricText.cleanInvisibleChars(it).trim() }?.ifEmpty { null },
            words = words,
        )
    }

    private fun accompanimentLine(line: KaraokeLine.AccompanimentKaraokeLine): LyricLine? {
        val words = line.syllables.mapNotNull { syllable ->
            val text = LyricText.cleanInvisibleChars(syllable.content)
            if (text.isEmpty()) null
            else LyricWord(text, syllable.start.toLong(), syllable.end.toLong())
        }
        val text = words.joinToString("") { it.text }
        if (text.isEmpty()) return null

        return LyricLine(
            startMs = line.start.toLong(),
            endMs = line.end.toLong(),
            text = text,
            translation = line.translation?.let { LyricText.cleanInvisibleChars(it).trim() }?.ifEmpty { null },
            words = words,
        )
    }
}
