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
package com.hchen.superlyric.ui.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UIConstantsTest {

    @Test
    fun `wide screen boundary is inclusive at 840dp`() {
        assertTrue(UIConstants.isWideScreen(840f, 1200f))
        assertFalse(UIConstants.isWideScreen(839.9f, 1200f))
    }

    @Test
    fun `medium width needs a landscape aspect ratio`() {
        assertTrue(UIConstants.isWideScreen(600f, 500f))
        assertFalse(UIConstants.isWideScreen(600f, 1000f))
        assertFalse(UIConstants.isWideScreen(599.9f, 500f))
    }
}
