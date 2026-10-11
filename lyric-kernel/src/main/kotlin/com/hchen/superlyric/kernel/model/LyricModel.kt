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
package com.hchen.superlyric.kernel.model

/**
 * 逐字切片时序。
 *
 * 时间单位一律为毫秒。三分量模型（[LyricDoc] / [LyricLine] / [LyricWord]）全部**不可变**，
 * 这是承重设计：它使解析结果可以安全地跨线程、跨 Binder 传递而无需深拷贝防御。
 *
 * @property text    切片文本
 * @property startMs 起始毫秒
 * @property endMs   结束毫秒
 */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * 单行歌词。
 *
 * **翻译通道约定**：译文只允许通过 [translation] 承载，罗马音只允许通过 [phonetic] 承载。
 * 解析层**严禁**把译文行输出为独立的 [LyricLine]，否则界面会把翻译当成歌词显示
 *
 * @property text        主歌词文本
 * @property translation 翻译文本；无翻译时为 null
 * @property phonetic    罗马音 / 音译文本；无则为 null
 * @property words       逐字切片；无逐字数据时为空列表
 */
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val phonetic: String? = null,
    val words: List<LyricWord> = emptyList(),
) {
    val hasTranslation: Boolean get() = !translation.isNullOrEmpty()

    val hasPhonetic: Boolean get() = !phonetic.isNullOrEmpty()

    val hasWords: Boolean get() = words.isNotEmpty()
}

/**
 * 一首曲目的完整歌词文档，是 `:lyric-kernel` 的唯一输出形态。
 *
 * 该类型不依赖任何 Android / Parcelable 类型，因此本模块可以完全在 JVM 上做单元测试。
 *
 * @property lines      按 [LyricLine.startMs] 升序排列的歌词行
 * @property title      曲名
 * @property artist     歌手
 * @property album      专辑
 * @property durationMs 曲目总时长（毫秒）；未知时为 0
 * @property lyricId    曲目唯一标识（由调用方注入，用于跨包一致性校验）
 */
data class LyricDoc(
    val lines: List<LyricLine>,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val lyricId: String? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    val firstStartMs: Long get() = lines.firstOrNull()?.startMs ?: 0L

    val lastEndMs: Long get() = lines.lastOrNull()?.endMs ?: 0L

    companion object {
        @JvmField
        val EMPTY: LyricDoc = LyricDoc(lines = emptyList())
    }
}
