@file:Suppress("DEPRECATION")

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
package com.hchen.superlyric.bridge

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.Lyric
import com.hchen.superlyricapi.model.Word

/**
 * 内核模型与 API 模型的唯一转换边界。
 *
 * 核心会话只保存 [Lyric]。[SuperLyricData] 仅在 3.x Binder 入站或出站时出现：
 * [fromLegacy] 负责入站，[toLegacyFull] 与 [toLegacyLine] 负责出站。
 */
object LyricDocAdapter {

    /**
     * 将内核文档投影为 4.0 模型。
     *
     * [Lyric] 构造器要求 id 非空白、行非空；title、artist、album 用空串表示未知。
     * id 缺失时用标题和歌手生成稳定键；时长未知时保留 0，由服务端用系统元数据补齐。
     */
    fun toLyric(doc: LyricDoc): Lyric? {
        if (doc.isEmpty) return null

        val lines = doc.lines.map { line ->
            Line(
                line.text,
                line.startMs,
                line.endMs,
                line.words.takeIf { it.isNotEmpty() }
                    ?.map { Word(it.text, it.startMs, it.endMs) },
                line.translation?.takeIf { it.isNotEmpty() },
                line.phonetic?.takeIf { it.isNotEmpty() },
            )
        }
        return Lyric(
            doc.lyricId?.takeIf { it.isNotBlank() } ?: fallbackId(doc),
            doc.title.orEmpty(),
            doc.artist.orEmpty(),
            doc.album.orEmpty(),
            doc.durationMs.coerceAtLeast(0L),
            lines,
        )
    }

    /**
     * 将 4.0 模型读入内核模型；仅用于解析工具与测试，不进入核心会话。
     */
    fun fromLyric(lyric: Lyric?): LyricDoc {
        if (lyric == null) return LyricDoc.EMPTY

        return LyricDoc(
            lines = lyric.lines.map { line ->
                LyricLine(
                    startMs = line.start,
                    endMs = line.end,
                    text = line.text,
                    translation = line.translation?.takeIf { it.isNotEmpty() },
                    phonetic = line.phonetic?.takeIf { it.isNotEmpty() },
                    words = line.words?.map { LyricWord(it.text, it.start, it.end) }.orEmpty(),
                )
            },
            title = lyric.title,
            artist = lyric.artist,
            album = lyric.album,
            durationMs = lyric.duration,
            lyricId = lyric.id.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 用系统 MediaSession 的时长补齐未知时长；已知时长保持不变。
     */
    fun withDuration(lyric: Lyric, durationMs: Long): Lyric {
        if (durationMs <= 0L || lyric.duration > 0L) return lyric
        return Lyric(
            lyric.id,
            lyric.title,
            lyric.artist,
            lyric.album,
            durationMs,
            lyric.lines,
        )
    }

    /**
     * 3.x 入站：把宿主或旧发布端的数据转换为 4.0 模型。
     *
     * 有 allLyrics 时保留全量行；只有当前行时生成单行文档。
     */
    fun fromLegacy(data: SuperLyricData?): Lyric? = toLyric(fromLegacyDoc(data))

    /**
     * 3.x 出站：首句前只携带曲目信息，开始后投影全量歌词与当前行。
     *
     * 不能提前携带 allLyrics；旧接收端可能忽略当前行号而直接展示首句。
     */
    fun toLegacyFull(lyric: Lyric, index: Int, positionMs: Long): SuperLyricData {
        val data = legacyMetadata(lyric, positionMs)
        if (index < 0) return data

        val lines = lyric.lines.map(::toParcelableLine)
        data.setAllLyrics(lines.toTypedArray())
        val current = lines.getOrNull(index) ?: return data
        data.setCurrentLyricIndex(index)
            .setLyric(current)
        current.translation?.let { data.setTranslation(SuperLyricLine(it, current.startTime, current.endTime)) }
        current.secondary?.let { data.setSecondary(SuperLyricLine(it, current.startTime, current.endTime)) }
        return data
    }

    /**
     * 3.x 出站：只投影当前行，不携带 allLyrics。
     */
    fun toLegacyLine(lyric: Lyric, index: Int, positionMs: Long): SuperLyricData? {
        val current = lyric.lines.getOrNull(index) ?: return null
        val line = toParcelableLine(current)

        val data = legacyMetadata(lyric, positionMs)
            .setCurrentLyricIndex(index)
            .setLyric(line)

        line.translation?.let { data.setTranslation(SuperLyricLine(it, line.startTime, line.endTime)) }
        line.secondary?.let { data.setSecondary(SuperLyricLine(it, line.startTime, line.endTime)) }
        return data
    }

    private fun legacyMetadata(lyric: Lyric, positionMs: Long): SuperLyricData = SuperLyricData()
        .setTitle(lyric.title)
        .setArtist(lyric.artist)
        .setAlbum(lyric.album)
        .setLyricId(lyric.id)
        .setDuration(lyric.duration.coerceAtLeast(0L))
        .setCurrentLyricIndex(-1)
        .setPosition(positionMs.coerceAtLeast(0L))

    private fun fromLegacyDoc(data: SuperLyricData?): LyricDoc {
        if (data == null) return LyricDoc.EMPTY

        val lines = data.allLyrics?.mapNotNull(::fromParcelableLine).orEmpty()
        return LyricDoc(
            lines = if (lines.isNotEmpty()) lines else singleLineFallback(data),
            title = data.title.orEmpty(),
            artist = data.artist.orEmpty(),
            album = data.album.orEmpty(),
            durationMs = if (data.duration > 0L) data.duration else 0L,
            lyricId = data.lyricId,
        )
    }

    private fun singleLineFallback(data: SuperLyricData): List<LyricLine> {
        val line = data.lyric?.let(::fromParcelableLine) ?: return emptyList()
        return listOf(
            LyricLine(
                startMs = line.startMs,
                endMs = line.endMs,
                text = line.text,
                translation = data.translation?.text?.takeIf { it.isNotEmpty() } ?: line.translation,
                phonetic = data.secondary?.text?.takeIf { it.isNotEmpty() } ?: line.phonetic,
                words = line.words,
            )
        )
    }

    private fun toParcelableLine(line: Line): SuperLyricLine {
        val words = line.words
            ?.map { SuperLyricWord(it.text, it.start, it.end) }
            ?.toTypedArray()
            ?.takeIf { it.isNotEmpty() }

        return SuperLyricLine(
            line.text,
            words,
            line.translation?.takeIf { it.isNotEmpty() },
            line.phonetic?.takeIf { it.isNotEmpty() },
            line.start,
            line.end,
        )
    }

    private fun fromParcelableLine(line: SuperLyricLine): LyricLine? {
        val text = line.text
        if (text.isEmpty()) return null

        return LyricLine(
            startMs = line.startTime,
            endMs = line.endTime,
            text = text,
            translation = line.translation?.takeIf { it.isNotEmpty() },
            phonetic = line.secondary?.takeIf { it.isNotEmpty() },
            words = line.words?.map { LyricWord(it.word, it.startTime, it.endTime) }.orEmpty(),
        )
    }

    private fun fallbackId(doc: LyricDoc): String =
        "sl:${doc.title.trim()}##${doc.artist.trim()}"
}
