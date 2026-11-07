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
package com.hchen.superlyric.provider.poweramp

import com.hchen.superlyric.kernel.identity.TrackIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PowerampTrackIdTest {

    private val source = PowerampHookSource()

    private fun identity(stableId: String?) =
        TrackIdentity("PowerampProvider", stableId, "title", "artist", 1_000L)

    @Test
    fun `folder hierarchies expose folder_files id`() {
        assertEquals(362L, source.trackIdOf(identity("/folders/20/files/362")))
        assertEquals(505L, source.trackIdOf(identity("/folders_hier/21/files/505")))
    }

    @Test
    fun `playlist entries do not expose folder_files id`() {
        // 播放列表路径末尾是列表条目 id；当成文件 id 会命中同号的无关曲目
        assertNull(source.trackIdOf(identity("/playlists/2/files/5")))
        assertNull(source.trackIdOf(identity("/playlists/3/files/6")))
    }

    @Test
    fun `bare numeric ids remain usable`() {
        assertEquals(362L, source.trackIdOf(identity("362")))
    }

    @Test
    fun `missing stable id has no track id`() {
        assertNull(source.trackIdOf(identity(null)))
        assertNull(source.trackIdOf(identity("")))
    }
}
