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
package com.hchen.superlyric.provider.kuwo

import android.media.MediaMetadata
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.shared.HostTrackFacts
import com.hchen.superlyric.shared.HostTrackMetaSource
import com.hchen.superlyric.shared.hostString
import java.util.concurrent.atomic.AtomicReference

/**
 * 酷我宿主身份：曲目探针的 rid、曲名与歌手
 */
class KuwoIdentitySource(
    private val providerTag: String,
) : BaseIdentitySource(), HostTrackMetaSource {

    private data class Track(val title: String, val artist: String)

    private val latestRid = AtomicReference<String>()
    private val tracks = object : LinkedHashMap<String, Track>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>): Boolean =
            size > MAX_CACHED_TRACKS
    }

    override fun install() {
        KuwoAnchors.installTrackProbe(::onTrack)
    }

    internal fun onTrack(rid: String, title: String, artist: String) {
        if (rid.isEmpty()) return
        latestRid.set(rid)
        synchronized(tracks) { tracks[rid] = Track(title, artist) }
        update(
            HostTrackFacts(
                identity = TrackIdentity(
                    source = providerTag,
                    stableId = stableIdOf(rid),
                    title = title,
                    artist = artist,
                    durationMs = 0L,
                )
            )
        )
    }

    override fun currentStableId(): String? = latestRid.get()

    override fun current(metadata: MediaMetadata): TrackIdentity? = current(
        metadata.hostString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        metadata.hostString(MediaMetadata.METADATA_KEY_TITLE),
        metadata.hostString(MediaMetadata.METADATA_KEY_ARTIST),
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
    )

    internal fun current(mediaId: String, title: String, artist: String, durationMs: Long): TrackIdentity? {
        val usableMedia = TrackIdentity.usableId(mediaId)
        val captured = TrackIdentity.usableId(currentStableId())
        val rid = usableMedia ?: captured ?: return null
        val hostTitle = title(rid)
        val hostArtist = artist(rid)
        return TrackIdentity(
            providerTag,
            stableIdOf(rid),
            hostTitle.ifEmpty { title },
            hostArtist.ifEmpty { artist },
            durationMs,
        )
    }

    override fun title(id: String): String = synchronized(tracks) { tracks[id]?.title }.orEmpty()

    override fun artist(id: String): String = synchronized(tracks) { tracks[id]?.artist }.orEmpty()

    private fun stableIdOf(rid: String): String? {
        val trimmed = title(rid).trim()
        return if (trimmed.isEmpty()) {
            TrackIdentity.stableId(NAMESPACE, rid)
        } else {
            TrackIdentity.stableId(NAMESPACE, "$rid#$trimmed")
        }
    }

    private companion object {
        const val NAMESPACE = "kuwo"
        const val MAX_CACHED_TRACKS = 32
    }
}
