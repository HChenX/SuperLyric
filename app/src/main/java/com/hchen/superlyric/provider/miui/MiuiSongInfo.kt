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
package com.hchen.superlyric.provider.miui

import com.hchen.superlyric.kernel.identity.TrackIdentity

/**
 * 宿主曲目快照与音轨身份的对应关系
 *
 * ## 为什么必须比对
 * 宿主把 `SongInfo` 的更新放在 `MediaSession` 元数据**之前**，回调也可能与切歌交错。
 * 只要有一次把另一首歌的 songID 当成当前曲目，就会把 A 的歌词发布给 B。
 * 因此快照只有在与当前身份指向同一首曲目时才可以采信；对不上就等下一个快照。
 *
 * @author 焕晨HChen
 */
internal object MiuiSongInfo {

    /**
     * 快照是否就是当前音轨
     *
     * 快照不带稳定标识，因此这里走 [TrackIdentity.matches] 的“标题 + 歌手 + 时长”路径：
     * 标题归一化后必须相同，歌手须相容，双方时长都已知时偏差不超过 1 秒
     */
    fun matches(identity: TrackIdentity, snapshot: MiuiAnchors.SongSnapshot): Boolean {
        if (snapshot.id <= 0L) return false
        return identity.matches(
            TrackIdentity(
                source = identity.source,
                stableId = null,
                title = snapshot.title,
                artist = snapshot.artist,
                durationMs = snapshot.durationMs,
            )
        )
    }

    /**
     * 用宿主快照校正音轨身份
     *
     * 曲名与歌手以宿主自己的对象模型为准：`MediaMetadata` 是不可信的（宿主可能写入当前
     * 歌词行或改写歌手），而快照与 songID 同属一次上报。稳定标识保持不变——它由
     * `MediaSession` 的 `MEDIA_ID` 提供，只用于判定切歌
     */
    fun merge(identity: TrackIdentity, snapshot: MiuiAnchors.SongSnapshot): TrackIdentity =
        identity.copy(
            title = snapshot.title.ifBlank { identity.title },
            artist = snapshot.artist.ifBlank { identity.artist },
            durationMs = if (snapshot.durationMs > 0L) snapshot.durationMs else identity.durationMs,
        )
}
