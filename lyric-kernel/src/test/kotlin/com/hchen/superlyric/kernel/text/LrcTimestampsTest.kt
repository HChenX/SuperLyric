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
package com.hchen.superlyric.kernel.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LrcTimestampsTest {

    @Test
    fun `fraction digits are scaled as centiseconds`() {
        assertEquals(62_300L, LrcTimestamps.timestampOf("[01:02.3]"))
        assertEquals(62_340L, LrcTimestamps.timestampOf("[01:02.34]"))
        assertEquals(62_345L, LrcTimestamps.timestampOf("[01:02.345]"))
        assertEquals(62_000L, LrcTimestamps.timestampOf("[01:02]"))
        assertEquals(62_345L, LrcTimestamps.timestampOf("[01:02:345]"))
        assertEquals(62_340L, LrcTimestamps.timestampOf("<01:02.34>"))
    }

    @Test
    fun `the segmented helper matches the token helper`() {
        assertEquals(62_340L, LrcTimestamps.timestampOf("01", "02", "34"))
        assertEquals(0L, LrcTimestamps.timestampOf("not", "a", "time"))
    }

    @Test
    fun `three part timestamps are rewritten without touching the body`() {
        assertEquals("[00:01.02]body", LrcTimestamps.normalize("[00:01:02]body"))
    }
}
