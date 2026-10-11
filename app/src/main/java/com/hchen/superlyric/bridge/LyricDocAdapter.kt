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

/**
 * `:lyric-kernel` 的不可变模型与 `SuperLyricApi` 的可变 Parcelable 之间的**唯一**边界。
 *
 * <h3>为什么必须集中在这一处</h3>
 * <ol>
 *   <li><b>Parcelable 无法进入内核</b>：[SuperLyricData] 来自外部 Android 库，
 *       不可变内核模块不能依赖它。本适配器是 `:app` 侧唯一 import 它的业务代码。</li>
 *   <li><b>深拷贝是硬性要求</b>：重构前，同一份 [SuperLyricData] 既被发布到 Binder、
 *       又被服务端会话与进度追踪器同时持有，而 `LyricSanitizer` 还会**原地修改并返回同一实例**
 *       （且被重复调用两次）。跨线程、跨 Binder 共享可变对象是那一类诡异缺陷的温床。
 *       本适配器**每次都从不可变模型重新分配**全部 [SuperLyricLine] 与 [SuperLyricWord]，
 *       因此不可能出现共享可变实例。</li>
 * </ol>
 *
 * 内核侧一律使用 [LyricDoc]；只有在跨 Binder 收发的那一刻才经过本适配器。
 *
 * @author 焕晨HChen
 */
object LyricDocAdapter {

    /**
     * 构造**全量**歌词包。
     *
     * 同时填充单行通道（首行 + 索引 0），以兼容只读 [SuperLyricData.getLyric] 的消费端。
     */
    fun toFullData(doc: LyricDoc): SuperLyricData {
        val lines = doc.lines.map(::toLine)

        val data = SuperLyricData()
            .setTitle(doc.title)
            .setArtist(doc.artist)
            .setAlbum(doc.album)
            .setLyricId(doc.lyricId)
            .setDuration(doc.durationMs)
            .setAllLyrics(lines.toTypedArray())
            .setCurrentLyricIndex(0)

        lines.firstOrNull()?.let { data.setLyric(it) }
        return data
    }

    /**
     * 构造**单行增量**包（服务端时钟推进时使用）。
     *
     * 不携带全量歌词，是本项目 IPC 开销最小的包型。
     *
     * @param index      行下标；越界时返回 null
     * @param positionMs 当前播放位置
     */
    fun toLineData(doc: LyricDoc, index: Int, positionMs: Long): SuperLyricData? {
        val line = doc.lines.getOrNull(index) ?: return null

        val data = SuperLyricData()
            .setTitle(doc.title)
            .setArtist(doc.artist)
            .setAlbum(doc.album)
            .setLyricId(doc.lyricId)
            .setDuration(doc.durationMs)
            .setCurrentLyricIndex(index)
            .setPosition(positionMs)
            .setLyric(toLine(line))

        line.translation?.let { data.setTranslation(SuperLyricLine(it, line.startMs, line.endMs)) }
        line.phonetic?.let { data.setSecondary(SuperLyricLine(it, line.startMs, line.endMs)) }

        return data
    }

    /**
     * 将收到的 [SuperLyricData] 读入不可变模型。
     *
     * 读取时逐字段复制值，不做任何引用共享；接收方随后持有的是一个与 Binder 入参完全解耦的快照。
     */
    fun toDoc(data: SuperLyricData?): LyricDoc {
        if (data == null) return LyricDoc.EMPTY

        val lines = data.allLyrics?.mapNotNull { toLyricLine(it) }.orEmpty()

        return LyricDoc(
            lines = lines,
            title = data.title.orEmpty(),
            artist = data.artist.orEmpty(),
            album = data.album.orEmpty(),
            durationMs = if (data.duration > 0L) data.duration else 0L,
            lyricId = data.lyricId,
        )
    }

    /**
     * 单行模型 → Parcelable。
     */
    fun toLine(line: LyricLine): SuperLyricLine {
        val words = line.words
            .map { SuperLyricWord(it.text, it.startMs, it.endMs) }
            .toTypedArray()
            .takeIf { it.isNotEmpty() }

        return SuperLyricLine(
            line.text,
            words,
            line.translation,
            line.phonetic,
            line.startMs,
            line.endMs,
        )
    }

    private fun toLyricLine(line: SuperLyricLine): LyricLine? {
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
}
