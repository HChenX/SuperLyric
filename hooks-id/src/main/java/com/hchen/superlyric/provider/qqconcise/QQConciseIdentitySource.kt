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
package com.hchen.superlyric.provider.qqconcise

import android.media.MediaMetadata
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.catalog.CatalogId
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.shared.toTrackIdentity

class QQConciseIdentitySource(private val providerTag: String) : BaseIdentitySource() {

    override fun install() = Unit

    override fun current(metadata: MediaMetadata): TrackIdentity =
        resolve(metadata.toTrackIdentity(providerTag, null), QQConciseAnchors.currentSong())

    internal fun resolve(metadata: TrackIdentity, song: QQConciseAnchors.Song?): TrackIdentity {
        // SDK MediaSession sets MEDIA_ID to the audio session ID.
        if (song == null || song.title().isBlank()) return metadata

        val title = song.title().trim()
        val artist = song.artist().trim()
        val normal = metadata.title.trim().equals(title, ignoreCase = true) &&
            TrackIdentity.artistCompatible(metadata.artist, artist)
        // With status-bar lyrics enabled, the host moves the track label into the artist field.
        val statusLabel = if (artist.isBlank()) title else "$title-$artist"
        if (!normal && metadata.artist.trim() != statusLabel) return metadata

        val mid = CatalogIds.qqMid(song.mid())
        val id = CatalogIds.qqSongId(song.id().toString())?.toString() ?: mid
        return metadata.copy(
            stableId = TrackIdentity.stableId(LyricCatalog.QQ.namespace, id),
            title = title,
            artist = if (normal) artist.ifBlank { metadata.artist } else artist,
            durationMs = metadata.durationMs.takeIf { it > 0L } ?: song.durationMs(),
            catalogId = mid?.let { CatalogId(LyricCatalog.QQ, it) },
        )
    }
}
