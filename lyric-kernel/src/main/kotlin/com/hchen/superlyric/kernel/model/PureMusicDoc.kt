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
 * 纯音乐占位文档。
 *
 * <h3>何时才允许使用</h3>
 * 只有在**所有可用来源都一致确认该曲目没有歌词**（[com.hchen.superlyric.kernel.outcome.LyricOutcome.NoLyrics]
 * 或 [com.hchen.superlyric.kernel.outcome.LyricOutcome.Unsupported]）时才允许构造并下发。
 * 取词失败（[com.hchen.superlyric.kernel.outcome.LyricOutcome.TransientFailure]）**绝不允许**落到这里——
 * 那正是重构前"任何一次网络抖动都被永久钉死为纯音乐"的根因。
 *
 * 占位时长在此集中定义，消除了重构前编排器用 300000ms、网易云引擎用 3600000ms 的不一致。
 *
 * @author 焕晨HChen
 */
object PureMusicDoc {

    /** 曲目时长未知时的占位时长。 */
    const val DEFAULT_DURATION_MS = 300_000L

    /** 占位文案。 */
    const val TEXT = "纯音乐，请欣赏"

    /**
     * 构造占位文档。
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
        val duration = if (durationMs > 0L) durationMs else DEFAULT_DURATION_MS
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

    /** 判断一个文档是否为纯音乐占位。 */
    fun isPlaceholder(doc: LyricDoc): Boolean =
        doc.lines.size == 1 && doc.lines[0].text == TEXT
}
