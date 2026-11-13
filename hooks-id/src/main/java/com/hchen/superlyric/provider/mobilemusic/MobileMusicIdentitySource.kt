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
package com.hchen.superlyric.provider.mobilemusic

import android.media.MediaMetadata
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.shared.HostTrackFacts

/**
 * 咪咕宿主身份
 *
 * <p>原生歌词快照给出权威身份；快照尚未更新时用 MediaMetadata 建立临时身份，
 * 让搜索页播放和“上一首”也能先触发切歌与清词。
 */
class MobileMusicIdentitySource : BaseIdentitySource() {

    override fun install() {
        MobileMusicAnchors.subscribe(::onCapture)
    }

    fun currentTrackIdentity(): TrackIdentity? = currentFacts()?.identity

    override fun current(metadata: MediaMetadata): TrackIdentity? {
        val native = currentTrackIdentity()
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()
        return mergeIdentity(native, title, artist, durationMs, mediaId)
    }

    /**
     * 宿主元数据先于原生歌词快照到达时，用元数据建立临时身份
     *
     * <p>搜索页播放和“上一首”不会立刻触发歌词解析回调；如果只等原生快照，
     * 编排器不会认为切歌，旧歌词会一直留着。标题/歌手与原生快照不一致时立即
     * 返回元数据身份，让编排器先清掉旧词；时长仍以原生快照为准。
     */
    internal fun mergeIdentity(
        native: TrackIdentity?,
        mediaTitle: String,
        mediaArtist: String,
        mediaDurationMs: Long,
        mediaId: String?,
    ): TrackIdentity? {
        val title = mediaTitle.trim()
        val artist = mediaArtist.trim()
        val id = TrackIdentity.usableId(mediaId)
        if (native == null) {
            if (title.isEmpty()) return null
            // 临时身份不能带稳定标识：原生快照随后到达时要用标题/歌手/时长完成升级，
            // 否则两个不同格式的 ID 会永久互斥。
            return TrackIdentity(
                source = PROVIDER_TAG,
                stableId = null,
                title = title,
                artist = artist,
                durationMs = if (mediaDurationMs > 0L) mediaDurationMs else 0L,
            )
        }
        if (title.isEmpty()) return native
        val sameTitle = TrackIdentity.normalizeTitle(title) == TrackIdentity.normalizeTitle(native.title)
        val artistDiffers = !TrackIdentity.artistCompatible(artist, native.artist)
        if (sameTitle && !artistDiffers) return native
        // 有些宿主会把当前歌词行写进 TITLE；只有标题变化、而歌手/时长/标识都不变时
        // 不能据此判为切歌，否则每换一行都会清一次歌词。
        val idDiffers = id != null && native.stableId != null &&
            TrackIdentity.normalizeStableId(id) != TrackIdentity.normalizeStableId(native.stableId)
        val durationConflicts = mediaDurationMs > 0L && native.durationMs > 0L &&
            kotlin.math.abs(mediaDurationMs - native.durationMs) > TrackIdentity.DURATION_TOLERANCE_MS
        if (!artistDiffers && !idDiffers && !durationConflicts) return native
        return TrackIdentity(
            source = PROVIDER_TAG,
            stableId = null,
            title = title,
            artist = artist,
            durationMs = if (mediaDurationMs > 0L) mediaDurationMs else 0L,
        )
    }

    internal fun onCapture(capture: MobileMusicAnchors.Capture) {
        update(
            HostTrackFacts(
                identity = TrackIdentity(
                    source = PROVIDER_TAG,
                    stableId = TrackIdentity.stableId(NAMESPACE, capture.songId()),
                    title = capture.title(),
                    artist = capture.artist(),
                    durationMs = capture.durationMs(),
                ),
                album = capture.album(),
            )
        )
    }

    private companion object {
        const val PROVIDER_TAG = "MobileMusicProvider"
        const val NAMESPACE = "migu"
    }
}
