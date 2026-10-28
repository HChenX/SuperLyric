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
package com.hchen.superlyric.provider.miui

import com.hchen.superlyric.kernel.identity.TrackIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 宿主曲目快照与音轨身份的对应关系
 *
 * @author 焕晨HChen
 */
class MiuiSongInfoTest {

    private fun identity(title: String, artist: String = "周杰伦", duration: Long = 299_000L) =
        TrackIdentity(
            source = "MiuiProvider",
            stableId = "miui:track-1",
            title = title,
            artist = artist,
            durationMs = duration,
        )

    private fun snapshot(
        id: Long = 102_065_756L,
        title: String = "七里香",
        artist: String = "周杰伦",
        duration: Long = 299_000L,
    ) = MiuiAnchors.SongSnapshot(id, title, artist, duration)

    @Test
    fun `snapshot of the same track matches the identity`() {
        assertTrue(MiuiSongInfo.matches(identity("七里香"), snapshot()))
    }

    @Test
    fun `title normalization tolerates bracketed suffixes`() {
        assertTrue(MiuiSongInfo.matches(identity("七里香"), snapshot(title = "七里香 (Live)")))
    }

    @Test
    fun `a different title is rejected`() {
        assertFalse(MiuiSongInfo.matches(identity("七里香"), snapshot(title = "晴天")))
    }

    @Test
    fun `a snapshot without a title is rejected`() {
        // 没有曲名就无从证明这个 songID 属于当前音轨；宁可等下一个快照
        assertFalse(MiuiSongInfo.matches(identity("七里香"), snapshot(title = "")))
    }

    @Test
    fun `a duration far from the metadata is rejected`() {
        assertFalse(MiuiSongInfo.matches(identity("七里香"), snapshot(duration = 200_000L)))
    }

    @Test
    fun `merge keeps the stable id and takes the host object model`() {
        val merged = MiuiSongInfo.merge(
            identity("七里香 - 周杰伦"),
            snapshot(title = "七里香", artist = "周杰伦", duration = 299_100L),
        )

        assertEquals("miui:track-1", merged.stableId)
        assertEquals("七里香", merged.title)
        assertEquals(299_100L, merged.durationMs)
    }
}
