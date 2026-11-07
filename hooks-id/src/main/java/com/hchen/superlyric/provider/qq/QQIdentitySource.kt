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
package com.hchen.superlyric.provider.qq

import android.media.MediaMetadata
import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.catalog.CatalogId
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.shared.HostTrackFacts
import com.hchen.superlyric.shared.hostString
import java.util.concurrent.atomic.AtomicReference

/**
 * QQ 音乐宿主身份：songMID 与数字 songID
 */
class QQIdentitySource(private val providerTag: String) : BaseIdentitySource() {
    private val latestMid = AtomicReference<String>()

    override fun install() {
        QQAnchors.install { event -> onSongInfo(event) }
    }

    override fun currentStableId(): String? = latestMid.get()

    fun preferredMid(metadataId: String?): String? =
        QQIdentity.preferredMid(currentStableId(), metadataId)

    override fun current(metadata: MediaMetadata): TrackIdentity = current(
        metadata.hostString(MediaMetadata.METADATA_KEY_MEDIA_ID).trim(),
        metadata.hostString(MediaMetadata.METADATA_KEY_TITLE),
        metadata.hostString(MediaMetadata.METADATA_KEY_ARTIST),
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
    )

    internal fun current(mediaId: String, title: String, artist: String, durationMs: Long): TrackIdentity {
        val mid = preferredMid(mediaId)
        if (mid != null) {
            return TrackIdentity(
                providerTag,
                TrackIdentity.stableId("qq", mid),
                title,
                artist,
                durationMs,
                CatalogIds.qqMid(mid)?.let { CatalogId(LyricCatalog.QQ, it) },
            )
        }
        if (mediaId.isNotEmpty() && !QQIdentity.isNumericId(mediaId)) {
            return TrackIdentity(providerTag, TrackIdentity.stableId("qq", mediaId), title, artist, durationMs)
        }
        return TrackIdentity(providerTag, null, title, artist, durationMs)
    }

    internal fun onSongInfo(event: QQAnchors.SongInfoEvent) {
        val raw = event.rawToString()
        val shortMessage = event.shortMessage()

        var songMid = ""
        var songId = 0L
        var title = ""
        var artist = ""

        if (shortMessage.isNotEmpty()) {
            SHORT_MESSAGE_PATTERN.find(shortMessage)?.let { match ->
                songId = parseLong(match.groupValues[1])
                val candidateTitle = match.groupValues[2]
                title = if (isPlausible(candidateTitle)) candidateTitle.trim() else ""
                val singer = match.groupValues[3].trim()
                val separator = singer.indexOf('·')
                val candidateArtist = if (separator > 0) singer.substring(0, separator) else singer
                artist = if (isPlausible(candidateArtist)) candidateArtist.trim() else ""
            }
        }

        MID_PATTERN.find(raw)?.let { match ->
            val candidate = match.groupValues[1].trim()
            // 与 current()/preferredMid 同一校验：mid=null 或畸形值不能污染后续身份。
            if (CatalogIds.qqMid(candidate) != null) songMid = candidate
        }
        if (songId <= 0L) {
            ID_PATTERN.find(raw)?.let { songId = parseLong(it.groupValues[1]) }
        }
        if (title.isEmpty()) {
            NAME_PATTERN.find(raw)?.let {
                if (isPlausible(it.groupValues[1])) title = it.groupValues[1].trim()
            }
        }

        if (songMid.isEmpty() && songId <= 0L) {
            AndroidLog.logW(
                TAG,
                "LyricLoadBean captured but no usable song id; rawLen=${raw.length}, " +
                        "shortMessageLen=${shortMessage.length}",
            )
            return
        }

        if (songMid.isNotEmpty()) latestMid.set(songMid)
        update(
            HostTrackFacts(
                identity = TrackIdentity(
                    source = providerTag,
                    stableId = TrackIdentity.stableId(NAMESPACE, songMid),
                    title = title,
                    artist = artist,
                    durationMs = 0L,
                    catalogId = CatalogIds.qqMid(songMid)?.let { CatalogId(LyricCatalog.QQ, it) },
                ),
                secondaryId = songId,
            )
        )
    }

    private fun parseLong(value: String?): Long =
        runCatching { value?.trim()?.toLong() }.getOrNull() ?: 0L

    private fun isPlausible(value: String?): Boolean {
        if (value == null) return false
        val text = value.trim()
        if (text.isEmpty() || text.length > 80) return false
        if (text.startsWith("http://") || text.startsWith("https://")) return false
        if (text.contains('\n') || text.contains('\r') || text.contains('\t')) return false
        if (text == "未知歌手" || text == "未知专辑" || text.equals("null", ignoreCase = true)) return false
        if (text.length >= 16 && text.matches(Regex("^[0-9A-Fa-f]+$"))) return false
        val first = text.first()
        return first != '{' && first != '[' && first != '<'
    }

    private companion object {
        const val NAMESPACE = "qq"
        const val TAG = "QQIdentitySource"

        val MID_PATTERN = Regex("""mid=([^,]+)""")
        val ID_PATTERN = Regex("""id=(\d+)""")
        val NAME_PATTERN = Regex("""name=([^,]+)""")
        val SHORT_MESSAGE_PATTERN = Regex(
            """id\s*=\s*(\d+).*?name\s*=\s*(.*?)\s+singer\s*=\s*(.*?)\s+tmpPlayKey\s*=""",
        )
    }
}
