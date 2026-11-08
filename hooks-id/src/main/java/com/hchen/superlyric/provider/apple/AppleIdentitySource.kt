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
package com.hchen.superlyric.provider.apple

import android.media.MediaMetadata
import com.hchen.superlyric.identity.BaseIdentitySource
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.shared.hostString

private const val APPLE_MEDIA_ID = "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID"

/**
 * Apple 的默认身份实现：Apple 没有独立于 MEDIA_ID 的身份。
 *
 * 只把元数据映射为 TrackIdentity，不安装 Hook，也不保存 itemId。itemId 仅由
 * AppleHookSource 从事件中读取并用于内容校验，不参与身份。
 */
class AppleIdentitySource(private val providerTag: String) : BaseIdentitySource() {

    override fun install() = Unit

    override fun current(metadata: MediaMetadata): TrackIdentity = current(
        metadata.hostString(APPLE_MEDIA_ID).trim().ifEmpty {
            metadata.hostString(MediaMetadata.METADATA_KEY_MEDIA_ID).trim()
        },
        metadata.hostString(MediaMetadata.METADATA_KEY_TITLE),
        metadata.hostString(MediaMetadata.METADATA_KEY_ARTIST),
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
    )

    internal fun current(mediaId: String, title: String, artist: String, durationMs: Long): TrackIdentity =
        TrackIdentity(
            source = providerTag,
            stableId = mediaId.ifEmpty { null },
            title = title,
            artist = artist,
            durationMs = durationMs,
        )
}
