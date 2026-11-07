/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

import android.media.MediaMetadata
import com.hchen.superlyric.kernel.catalog.CatalogId
import com.hchen.superlyric.kernel.identity.TrackIdentity

fun MediaMetadata.hostString(key: String): String =
    runCatching { getString(key) }.getOrNull().orEmpty()

fun MediaMetadata.toTrackIdentity(
    source: String,
    stableId: String?,
    catalogId: CatalogId? = null,
): TrackIdentity = TrackIdentity(
    source = source,
    stableId = stableId,
    title = hostString(MediaMetadata.METADATA_KEY_TITLE),
    artist = hostString(MediaMetadata.METADATA_KEY_ARTIST),
    durationMs = runCatching { getLong(MediaMetadata.METADATA_KEY_DURATION) }.getOrDefault(0L),
    catalogId = catalogId,
)
