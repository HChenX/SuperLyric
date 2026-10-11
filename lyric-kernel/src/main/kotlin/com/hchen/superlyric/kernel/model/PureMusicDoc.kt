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
 * 纯音乐占位文档
 *
 * 只有在**所有可用来源都一致确认该曲目没有歌词**时才允许构造并下发，即
 * [com.hchen.superlyric.kernel.outcome.LyricOutcome.NoLyrics] 或
 * [com.hchen.superlyric.kernel.outcome.LyricOutcome.Unsupported]。
 * 取词失败（[com.hchen.superlyric.kernel.outcome.LyricOutcome.TransientFailure]）
 * **绝不允许**落到这里 —— 那会让一次网络抖动被永久钉死为纯音乐
 *
 * @author 焕晨HChen
 */
object PureMusicDoc {

    /**
     * 曲目时长未知时**保持 0**（表示未知）
     *
     * 不要填一个具体值：那是在伪造事实，消费方无法区分"不知道"与"就是 5 分钟"，
     * 进度与百分比会跟着错到曲末。系统侧会用宿主元数据补齐真实时长
     */
    const val DEFAULT_DURATION_MS = 0L

    /** 占位文案 */
    const val TEXT = "纯音乐，请欣赏"

    /**
     * 构造占位文档
     *
     * @param title      曲名；为空时占位行仍可用于展示
     * @param artist     歌手
     * @param album      专辑
     * @param durationMs 曲目时长；非正数时使用 [DEFAULT_DURATION_MS]
     * @param lyricId    曲目唯一标识
     */
    fun create(
        title: String = "",
        artist: String = "",
        album: String = "",
        durationMs: Long = 0L,
        lyricId: String? = null,
    ): LyricDoc {
        val duration = maxOf(0L, durationMs)
        return LyricDoc(
            lines = listOf(
                LyricLine(startMs = 0L, endMs = duration, text = TEXT)
            ),
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            lyricId = lyricId,
        )
    }

    /** 判断文档是否为纯音乐占位 */
    fun isPlaceholder(doc: LyricDoc): Boolean =
        doc.lines.size == 1 && doc.lines[0].text == TEXT
}
