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
 * 宿主曲目快照与音轨身份的对应关系。
 *
 * `SongInfo` 更新早于 `MediaSession`，回调也可能与切歌交错。快照只在身份匹配时采信，
 * 否则等待下一个，避免把 A 的歌词发布给 B。
 *
 * @author 焕晨HChen
 */
internal object MiuiSongInfo {

    /**
     * 快照是否属于当前音轨。
     *
     * 快照没有稳定标识，按标题、歌手和时长匹配；双方时长已知时偏差不超过 1 秒。
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
     * 用宿主快照校正音轨身份。
     *
     * 曲名、歌手和时长以宿主对象模型为准；稳定标识保持不变，只用于判定切歌。
     */
    fun merge(identity: TrackIdentity, snapshot: MiuiAnchors.SongSnapshot): TrackIdentity =
        identity.copy(
            title = snapshot.title.ifBlank { identity.title },
            artist = snapshot.artist.ifBlank { identity.artist },
            durationMs = if (snapshot.durationMs > 0L) snapshot.durationMs else identity.durationMs,
        )
}
