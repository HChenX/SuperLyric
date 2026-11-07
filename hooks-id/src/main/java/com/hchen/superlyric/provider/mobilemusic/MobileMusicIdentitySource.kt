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
 * 咪咕宿主身份：锚点快照中的不可变字段。
 */
class MobileMusicIdentitySource : BaseIdentitySource() {

    override fun install() {
        MobileMusicAnchors.subscribe { capture -> onCapture(capture) }
    }

    fun currentTrackIdentity(): TrackIdentity? = currentFacts()?.identity

    override fun current(metadata: MediaMetadata): TrackIdentity? = currentTrackIdentity()

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
