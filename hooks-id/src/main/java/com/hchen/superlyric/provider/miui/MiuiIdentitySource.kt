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
package com.hchen.superlyric.provider.miui

import android.media.MediaMetadata
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.catalog.CatalogId
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.shared.HostTrackFacts
import com.hchen.superlyric.shared.hostString

/**
 * 小米音乐宿主身份：SongInfo 快照
 */
class MiuiIdentitySource(private val providerTag: String) : BaseIdentitySource() {

    override fun install() {
        MiuiAnchors.install { snapshot -> onSnapshot(snapshot) }
    }

    override fun refresh() {
        MiuiAnchors.readCurrentSong()?.let(::onSnapshot)
    }

    override fun current(metadata: MediaMetadata): TrackIdentity = current(
        mediaId = metadata.hostString(MediaMetadata.METADATA_KEY_MEDIA_ID).trim(),
        title = metadata.hostString(MediaMetadata.METADATA_KEY_TITLE),
        artist = metadata.hostString(MediaMetadata.METADATA_KEY_ARTIST),
        durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
    )

    internal fun current(
        mediaId: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): TrackIdentity {
        val identity = TrackIdentity(
            source = providerTag,
            stableId = mediaId.ifEmpty { null },
            title = title,
            artist = artist,
            durationMs = durationMs,
        )
        val facts = currentFacts()
        val catalogId = facts
            ?.takeIf { it.identity.matches(identity) }
            ?.identity
            ?.catalogId
            ?.takeIf { it.catalog == LyricCatalog.QQ }
        return identity.copy(catalogId = catalogId)
    }

    internal fun onSnapshot(snapshot: MiuiAnchors.SongSnapshot) {
        if (snapshot.id <= 0L) return
        update(
            HostTrackFacts(
                identity = TrackIdentity(
                    source = providerTag,
                    stableId = null,
                    title = snapshot.title,
                    artist = snapshot.artist,
                    durationMs = snapshot.durationMs,
                    catalogId = CatalogId(LyricCatalog.QQ, snapshot.id.toString()),
                ),
                secondaryId = snapshot.id,
            )
        )
    }
}
