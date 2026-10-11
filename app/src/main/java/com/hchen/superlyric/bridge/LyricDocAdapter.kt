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
 * `:lyric-kernel` 的不可变模型与 `SuperLyricApi` 的可变 Parcelable 之间的**唯一**边界
 *
 * - **Parcelable 无法进入内核**：[SuperLyricData] 来自外部 Android 库，零依赖的不可变内核
 *   不能依赖它。本适配器是 `:app` 侧唯一 import 它的业务代码
 * - **深拷贝是硬性要求**：跨线程、跨 Binder 共享可变实例是诡异缺陷的温床。本适配器每次都
 *   从不可变模型**重新分配**全部 [SuperLyricLine] 与 [SuperLyricWord]，因此不可能出现共享实例
 *
 * 内核侧一律使用 [LyricDoc]，只有在跨 Binder 收发的那一刻才经过本适配器
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

        // 只有确实有行时才声明"当前行"：空文档上标 index=0 会让消费方以为存在第 0 行
        lines.firstOrNull()?.let { first ->
            data.setCurrentLyricIndex(0)
            data.setLyric(first)
        }
        return data
    }

    /**
     * 构造**单行增量**包（服务端时钟推进时使用）。
     *
     * 不携带全量歌词，是本项目 IPC 开销最小的包型。
     *
     * **译文与次歌词写两处，两条都在 wire 上**：每行的 [SuperLyricLine]（[toFullData] 带全量
     * 数组时随行过线——`SuperLyricData.writeToParcel` 会把 `allLyrics` 每行的译文/次歌词写成
     * 并列数组），以及顶层 [SuperLyricData.setTranslation] / [SuperLyricData.setSecondary]
     * （只带一行的增量包只有这一条通道）。消费端**两条都要读**，见 [toDoc]
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
     *
     * **全量行缺失时回退到单行通道**：只读 [SuperLyricData.getAllLyrics] 会让增量包解出空文档
     * （行、译文、次歌词全丢）。当前仓内喂进来的都是全量包，但公开 API 允许增量包，
     * 这个回退让调用方不必先弄清包型
     */
    fun toDoc(data: SuperLyricData?): LyricDoc {
        if (data == null) return LyricDoc.EMPTY

        val lines = data.allLyrics?.mapNotNull { toLyricLine(it) }.orEmpty()

        return LyricDoc(
            lines = if (lines.isNotEmpty()) lines else singleLineFallback(data),
            title = data.title.orEmpty(),
            artist = data.artist.orEmpty(),
            album = data.album.orEmpty(),
            durationMs = if (data.duration > 0L) data.duration else 0L,
            lyricId = data.lyricId,
        )
    }

    /** 从单行 + 顶层译文/次歌词拼出文档；单行通道为空时返回空列表。 */
    private fun singleLineFallback(data: SuperLyricData): List<LyricLine> {
        val line = data.lyric?.let { toLyricLine(it) } ?: return emptyList()
        return listOf(
            LyricLine(
                startMs = line.startMs,
                endMs = line.endMs,
                text = line.text,
                translation = data.translation?.text?.takeIf { it.isNotEmpty() },
                phonetic = data.secondary?.text?.takeIf { it.isNotEmpty() },
                words = line.words,
            )
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
