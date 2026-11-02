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
 * `:lyric-kernel` 的不可变模型与 `SuperLyricApi` 的线上模型之间的唯一边界
 *
 * - API 模型无法进入内核：[SuperLyricData] 与 [Lyric] 都来自外部 Android 库，零依赖的
 *   不可变内核不能依赖它们；本适配器是 `:app` 侧唯一做取舍的业务代码
 * - 深拷贝是硬性要求：跨线程、跨 Binder 共享可变实例会产生难以定位的缺陷，本适配器每次
 *   都从不可变模型重新分配全部行与逐字对象，不共享任何实例
 */
object LyricDocAdapter {

    /**
     * 构造全量歌词包
     *
     * 同时填充单行通道（首行 + 索引 0），以兼容只读 [SuperLyricData.getLyric] 的消费端。
     */
    fun toFullData(doc: LyricDoc): SuperLyricData = toFullData(doc, 0, 0L)

    /**
     * 构造带当前行与进度的全量歌词包
     *
     * 3.x 的 [SuperLyricData] 没有独立的播放状态通道，暂停与播完只能由「当前行 + 进度」
     * 表达；服务端向 3.x 订阅者投影全量数据时须带上真实行号。
     *
     * @param positionMs 当前播放位置；负数归一化为 0
     */
    fun toFullData(doc: LyricDoc, index: Int, positionMs: Long): SuperLyricData {
        val lines = doc.lines.map(::toLine)

        val data = SuperLyricData()
            .setTitle(doc.title)
            .setArtist(doc.artist)
            .setAlbum(doc.album)
            .setLyricId(doc.lyricId)
            .setDuration(doc.durationMs)
            .setAllLyrics(lines.toTypedArray())

        // 只有确实有行时才声明"当前行"：空文档上标 index=0 会让消费方以为存在第 0 行
        val current = lines.getOrNull(index)
        if (current != null) {
            data.setCurrentLyricIndex(index)
                .setPosition(positionMs.coerceAtLeast(0L))
                .setLyric(current)
            current.translation?.let {
                data.setTranslation(SuperLyricLine(it, current.startTime, current.endTime))
            }
            current.secondary?.let {
                data.setSecondary(SuperLyricLine(it, current.startTime, current.endTime))
            }
        }
        return data
    }

    /**
     * 构造单行增量包（服务端时钟推进时使用）
     *
     * 不携带全量歌词，是本项目 IPC 开销最小的包型。
     *
     * 译文与次歌词写两处，两条都在 wire 上：每行的 [SuperLyricLine]（[toFullData] 带全量
     * 数组时随行过线：`SuperLyricData.writeToParcel` 会把 `allLyrics` 每行的译文/次歌词写成
     * 并列数组），以及顶层 [SuperLyricData.setTranslation] / [SuperLyricData.setSecondary]
     * （只带一行的增量包只有这一条通道）。消费端两条都要读，见 [toDoc]
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
     * 将收到的 [SuperLyricData] 读入不可变模型
     *
     * 读取时逐字段复制值，不做任何引用共享；接收方随后持有的是一个与 Binder 入参完全解耦的快照。
     *
     * 全量行缺失时回退到单行通道：只读 [SuperLyricData.getAllLyrics] 会让增量包解出空文档
     * （行、译文、次歌词全部丢失）。公开 API 允许增量包，回退后调用方无需先区分包型
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

    /**
     * 将内核文档投影为 4.0 的歌词模型
     *
     * [Lyric] 的构造器要求 id 非空白、行非空，title/artist/album 用空串表示未知；
     * publish 时才要求 title 非空白。本方法只补齐 id，不伪造时长：
     * - id 缺失时用"标题 + 歌手"生成稳定键；它不是曲目身份判定，只用于客户端的帧缓存匹配
     * - 时长未知时原样保留 0，由服务端用 MediaMetadata 补齐；伪造末行结束时间会让客户端
     *   在末行终点提前结束进度
     *
     * @return 4.0 文档；没有可发布的行时为 null
     */
    fun toLyric(doc: LyricDoc): Lyric? {
        if (doc.isEmpty) return null

        val duration = doc.durationMs.coerceAtLeast(0L)
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
            duration,
            lines,
        )
    }

    /**
     * 用系统 MediaSession 的时长补齐文档
     *
     * 只在文档自己不知道时使用：系统是播放时长的权威，3.x 的 [SuperLyricData] 与 v4 的
     * [Lyric] 都允许 `duration = 0` 表示未知。补齐后 3.x 订阅者与进度钳制都能拿到真实值。
     */
    fun withDuration(doc: LyricDoc, durationMs: Long): LyricDoc =
        if (durationMs <= 0L || doc.durationMs > 0L) doc else doc.copy(durationMs = durationMs)

    /**
     * 将 4.0 的歌词模型读入不可变模型
     *
     * [Line.getPhonetic] 承载的是注音/罗马音（也可能是副歌词），对应内核的
     * [LyricLine.phonetic]；翻译与注音两条通道不能互换：互换后界面会把音译显示成译文。
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

    /** 从单行 + 顶层译文/次歌词拼出文档；单行通道为空时返回空列表 */
    private fun singleLineFallback(data: SuperLyricData): List<LyricLine> {
        val line = data.lyric?.let { toLyricLine(it) } ?: return emptyList()
        return listOf(
            LyricLine(
                startMs = line.startMs,
                endMs = line.endMs,
                text = line.text,
                // 顶层通道为空时保留行内值：SuperLyricLine 也支持承载译文/音译，
                // 直接用顶层覆盖会把它们丢成 null。
                translation = data.translation?.text?.takeIf { it.isNotEmpty() } ?: line.translation,
                phonetic = data.secondary?.text?.takeIf { it.isNotEmpty() } ?: line.phonetic,
                words = line.words,
            )
        )
    }

    /**
     * 单行模型 → Parcelable
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

    /**
     * 没有稳定标识时给 [Lyric] 的协议必填 id
     *
     * 只用于客户端按 id 区分帧缓存；同一首歌的两次发布必须得到同一个值，因此不能带随机量。
     */
    private fun fallbackId(doc: LyricDoc): String =
        "sl:${doc.title.trim()}##${doc.artist.trim()}"
}
