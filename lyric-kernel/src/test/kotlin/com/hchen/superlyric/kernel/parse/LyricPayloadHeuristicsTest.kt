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
package com.hchen.superlyric.kernel.parse

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LyricPayloadHeuristicsTest {

    @Test
    fun `a single zero timestamp is not a timeline`() {
        assertFalse(LyricPayloadHeuristics.hasUsableTiming(LyricDoc(listOf(LyricLine(0, 100, "a")))))
        assertTrue(LyricPayloadHeuristics.hasUsableTiming(LyricDoc(listOf(LyricLine(1_000, 2_000, "a")))))
        assertTrue(
            LyricPayloadHeuristics.hasUsableTiming(
                LyricDoc(listOf(LyricLine(0, 100, "a"), LyricLine(100, 200, "b"))),
            ),
        )
    }
}
