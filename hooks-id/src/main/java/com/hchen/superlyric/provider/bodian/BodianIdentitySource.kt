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
package com.hchen.superlyric.provider.bodian

import android.media.MediaMetadata
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.provider.bodian.BodianAnchors.LyricFileListener
import com.hchen.superlyric.shared.HostTrackFacts
import com.hchen.superlyric.shared.hostString
import java.util.concurrent.atomic.AtomicReference

class BodianIdentitySource(private val providerTag: String) : BaseIdentitySource() {
    private val latestRid = AtomicReference<String>()

    override fun install() {
        BodianAnchors.install(object : LyricFileListener {
            override fun onLyricFile(filePath: String, title: String, artist: String, album: String, rid: String) {
                this@BodianIdentitySource.onLyricFile(title, artist, album, rid)
            }
        })
    }

    internal fun onLyricFile(title: String, artist: String, album: String, rid: String) {
        if (rid.isNotEmpty()) latestRid.set(rid)
        update(
            HostTrackFacts(
                identity = TrackIdentity(
                    source = providerTag,
                    stableId = TrackIdentity.stableId(NAMESPACE, rid),
                    title = title,
                    artist = artist,
                    durationMs = 0L,
                ),
                album = album,
            )
        )
    }

    override fun currentStableId(): String? = latestRid.get()

    override fun current(metadata: MediaMetadata): TrackIdentity = current(
        metadata.hostString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        metadata.hostString(MediaMetadata.METADATA_KEY_TITLE),
        metadata.hostString(MediaMetadata.METADATA_KEY_ARTIST),
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
    )

    internal fun current(mediaId: String, title: String, artist: String, durationMs: Long): TrackIdentity {
        val id = mediaId.ifEmpty { currentStableId().orEmpty() }
        return TrackIdentity(providerTag, TrackIdentity.stableId(NAMESPACE, id), title, artist, durationMs)
    }

    private companion object {
        const val NAMESPACE = "kuwo"
    }
}
