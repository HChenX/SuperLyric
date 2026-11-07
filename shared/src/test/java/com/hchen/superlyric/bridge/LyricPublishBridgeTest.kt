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
package com.hchen.superlyric.bridge

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LyricPublishBridgeTest {

    @Test
    fun `duration correction accepts a namespace-prefixed stable id`() {
        val doc = LyricDoc(
            lines = listOf(LyricLine(0L, 1_000L, "line")),
            durationMs = 0L,
            lyricId = "abc",
        )
        val bridge = LyricPublishBridge { TrackIdentity("qq", "qq:abc", durationMs = 5_000L) }

        val corrected = correctedDuration(bridge, doc)

        assertEquals(5_000L, corrected.durationMs)
    }

    @Test
    fun `duration correction keeps the parsed value for another track`() {
        val doc = LyricDoc(
            lines = listOf(LyricLine(0L, 1_000L, "line")),
            durationMs = 0L,
            lyricId = "abc",
        )
        val bridge = LyricPublishBridge { TrackIdentity("qq", "qq:other", durationMs = 5_000L) }

        val corrected = correctedDuration(bridge, doc)

        assertEquals(0L, corrected.durationMs)
    }

    private fun correctedDuration(bridge: LyricPublishBridge, doc: LyricDoc): LyricDoc {
        val method = LyricPublishBridge::class.java
            .getDeclaredMethod("correctedDuration", LyricDoc::class.java)
            .apply { isAccessible = true }
        return method.invoke(bridge, doc) as LyricDoc
    }
}
