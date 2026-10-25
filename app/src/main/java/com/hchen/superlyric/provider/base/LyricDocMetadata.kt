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
package com.hchen.superlyric.provider.base

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.outcome.LyricOutcome

/**
 * 用当前曲目身份补齐文档元数据；只填空，不覆盖解析器已有的非空值。
 */
fun LyricDoc.withMetadata(
    identity: TrackIdentity?,
    hostTitle: String = "",
    hostArtist: String = "",
): LyricDoc {
    if (identity == null) return this
    return copy(
        title = identity.title.ifBlank { hostTitle }.ifBlank { title },
        artist = identity.artist.ifBlank { hostArtist }.ifBlank { artist },
        durationMs = if (identity.durationMs > 0L) identity.durationMs else durationMs,
        lyricId = identity.stableId ?: lyricId,
    )
}

fun LyricOutcome.withMetadata(
    identity: TrackIdentity?,
    hostTitle: String = "",
    hostArtist: String = "",
): LyricOutcome = when (this) {
    is LyricOutcome.Success -> LyricOutcome.Success(doc.withMetadata(identity, hostTitle, hostArtist))
    else -> this
}
