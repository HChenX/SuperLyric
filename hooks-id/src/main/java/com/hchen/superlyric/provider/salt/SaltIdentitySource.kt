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
package com.hchen.superlyric.provider.salt

import android.media.MediaMetadata
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.shared.HostTrackFacts
import com.hchen.superlyric.shared.HostTrackMetaSource
import com.hchen.superlyric.shared.hostString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class SaltIdentitySource(private val providerTag: String) : BaseIdentitySource(), HostTrackMetaSource {
    data class SongInfo(val title: String, val artist: String, val durationMs: Long)

    private val latestSongId = AtomicReference<String>()
    private val songs = ConcurrentHashMap<String, SongInfo>()

    override fun install() {
        SaltAnchors.install(object : SaltAnchors.LyricsListener {
            override fun onLyrics(sourceText: String, songId: String) {
                this@SaltIdentitySource.onLyrics(songId)
            }

            override fun onSong(songId: String, title: String, artist: String, durationMs: Long) {
                this@SaltIdentitySource.onSong(songId, title, artist, durationMs)
            }
        })
    }

    internal fun onLyrics(songId: String) {
        if (songId.isEmpty()) return
        publish(songId, songs[songId])
    }

    internal fun onSong(songId: String, title: String, artist: String, durationMs: Long) {
        if (songId.isEmpty()) return
        val info = SongInfo(title, artist, durationMs)
        songs[songId] = info
        publish(songId, info)
    }

    private fun publish(songId: String, info: SongInfo?) {
        latestSongId.set(songId)
        update(
            HostTrackFacts(
                identity = TrackIdentity(
                    source = providerTag,
                    stableId = TrackIdentity.stableId("salt", songId),
                    title = info?.title.orEmpty(),
                    artist = info?.artist.orEmpty(),
                    durationMs = info?.durationMs ?: 0L,
                )
            )
        )
    }

    override fun currentStableId(): String? = latestSongId.get()

    fun songInfo(songId: String): SongInfo? = songs[songId]

    override fun title(id: String): String = songInfo(id)?.title.orEmpty()

    override fun artist(id: String): String = songInfo(id)?.artist.orEmpty()

    override fun current(metadata: MediaMetadata): TrackIdentity = current(
        currentStableId(),
        metadata.hostString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        metadata.hostString(MediaMetadata.METADATA_KEY_TITLE),
        metadata.hostString(MediaMetadata.METADATA_KEY_ARTIST),
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
    )

    internal fun current(
        captured: String?,
        mediaId: String,
        metadataTitle: String,
        metadataArtist: String,
        metadataDurationMs: Long,
    ): TrackIdentity {
        val hasCaptured = !captured.isNullOrEmpty()
        val info = if (hasCaptured) songInfo(captured) else null
        val title = if (!info?.title.isNullOrEmpty()) info.title else metadataTitle
        val artist = if (!info?.artist.isNullOrEmpty()) info.artist else metadataArtist
        val duration = if (info != null && info.durationMs > 0L) info.durationMs else metadataDurationMs
        if (hasCaptured) {
            return TrackIdentity(providerTag, TrackIdentity.stableId("salt", captured), title, artist, duration)
        }
        if (mediaId.isNotEmpty()) {
            return TrackIdentity(providerTag, TrackIdentity.stableId("salt", mediaId), title, artist, duration)
        }
        return TrackIdentity(providerTag, null, title, artist, duration)
    }
}
