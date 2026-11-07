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
package com.hchen.superlyric.shared

import com.hchen.superlyric.kernel.identity.TrackIdentity

/**
 * 一次宿主身份捕获的完整伴生数据
 *
 * 曲库取词 ID 由 [identity] 的 catalogId 承载，不再单独保存。
 */
data class HostTrackFacts(
    val identity: TrackIdentity,
    val album: String = "",
    val secondaryId: Long = 0L,
)

/**
 * 宿主快照是否属于当前曲目
 */
fun HostTrackFacts.matches(current: TrackIdentity): Boolean = current.matches(identity)

/**
 * 用宿主快照的干净曲名、歌手与时长校正当前身份；稳定标识保持不变。
 */
fun HostTrackFacts.merge(current: TrackIdentity): TrackIdentity = current.copy(
    title = identity.title.ifBlank { current.title },
    artist = identity.artist.ifBlank { current.artist },
    durationMs = if (identity.durationMs > 0L) identity.durationMs else current.durationMs,
    catalogId = identity.catalogId ?: current.catalogId,
)
