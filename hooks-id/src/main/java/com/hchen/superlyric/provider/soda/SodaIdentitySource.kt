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
package com.hchen.superlyric.provider.soda

import android.media.MediaMetadata
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.shared.HostTrackFacts
import com.hchen.superlyric.shared.hostString
import java.util.concurrent.atomic.AtomicReference

class SodaIdentitySource(private val providerTag: String) : BaseIdentitySource() {
    private val latestTrackId = AtomicReference<String>()

    override fun install() {
        SodaAnchors.install(::onPayload)
    }

    override fun currentStableId(): String? = latestTrackId.get()

    override fun current(metadata: MediaMetadata): TrackIdentity = current(
        metadata.hostString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        metadata.hostString(MediaMetadata.METADATA_KEY_TITLE),
        metadata.hostString(MediaMetadata.METADATA_KEY_ARTIST),
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
    )

    internal fun current(mediaId: String, title: String, artist: String, durationMs: Long): TrackIdentity {
        val id = TrackIdentity.usableId(mediaId) ?: TrackIdentity.usableId(currentStableId())
        return TrackIdentity(providerTag, TrackIdentity.stableId("luna", id), title, artist, durationMs)
    }

    internal fun onPayload(type: String, language: String, payload: String, trackId: String?) {
        if (trackId == null || trackId.isBlank()) return
        latestTrackId.set(trackId)
        update(
            HostTrackFacts(
                identity = TrackIdentity(
                    source = providerTag,
                    stableId = TrackIdentity.stableId("luna", trackId),
                )
            )
        )
    }
}
