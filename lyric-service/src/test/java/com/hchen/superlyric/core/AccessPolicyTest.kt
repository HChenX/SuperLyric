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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessPolicyTest {

    @Test
    fun systemAndSupportedCallersReceiveBothServices() {
        assertEquals(AccessPolicy.Level.BOTH, AccessPolicy.resolve(true, false, false, 0))
        assertEquals(AccessPolicy.Level.BOTH, AccessPolicy.resolve(false, true, false, 0))
        assertEquals(AccessPolicy.Level.BOTH, AccessPolicy.resolve(true, false, true, 40))
        assertEquals(AccessPolicy.Level.BOTH, AccessPolicy.resolve(false, true, true, 31))
    }

    @Test
    fun markerFortyAndAboveReceiveOnlyV4() {
        assertEquals(AccessPolicy.Level.V4, AccessPolicy.resolve(false, false, true, 40))
        assertEquals(AccessPolicy.Level.V4, AccessPolicy.resolve(false, false, true, 41))
        assertEquals(true, AccessPolicy.Level.V4.allowsV4())
        assertEquals(false, AccessPolicy.Level.V4.allowsLegacy())
    }

    @Test
    fun legacyMarkersReceiveOnlyLegacy() {
        assertEquals(AccessPolicy.Level.LEGACY, AccessPolicy.resolve(false, false, true, 31))
        assertEquals(AccessPolicy.Level.LEGACY, AccessPolicy.resolve(false, false, true, 39))
        assertEquals(true, AccessPolicy.Level.LEGACY.allowsLegacy())
        assertEquals(false, AccessPolicy.Level.LEGACY.allowsV4())
    }

    @Test
    fun missingOrOldMarkersAreDenied() {
        assertEquals(AccessPolicy.Level.DENY, AccessPolicy.resolve(false, false, false, 40))
        assertEquals(AccessPolicy.Level.DENY, AccessPolicy.resolve(false, false, true, 30))
        assertEquals(AccessPolicy.Level.DENY, AccessPolicy.resolve(false, false, true, 0))
        assertEquals(AccessPolicy.Level.DENY, AccessPolicy.resolve(false, false, true, -1))
    }

    @Test
    fun invalidUidIsDeniedWithoutContext() {
        assertEquals(AccessPolicy.Level.DENY, AccessPolicy.levelForUid(0))
        assertEquals(AccessPolicy.Level.DENY, AccessPolicy.levelForUid(-1))
    }

    @Test
    fun `manifest metadata accepts string boolean and numeric values`() {
        assertEquals(true, AccessPolicy.toBooleanValue("true"))
        assertEquals(true, AccessPolicy.toBooleanValue(true))
        assertEquals(false, AccessPolicy.toBooleanValue("false"))
        assertEquals(true, AccessPolicy.toBooleanValue(1))
        assertEquals(false, AccessPolicy.toBooleanValue(null))

        assertEquals(36, AccessPolicy.toIntValue("36"))
        assertEquals(36, AccessPolicy.toIntValue(36))
        assertEquals(36, AccessPolicy.toIntValue(36L))
        assertEquals(36, AccessPolicy.toIntValue(36.9f))
        assertEquals(0, AccessPolicy.toIntValue("not-a-number"))
        assertEquals(0, AccessPolicy.toIntValue(null))
    }

    @Test
    fun legacyFullLyricsStartAt35() {
        assertFalse(AccessPolicy.supportsFullLegacy(34))
        assertTrue(AccessPolicy.supportsFullLegacy(35))
        assertTrue(AccessPolicy.supportsFullLegacy(39))
        assertTrue(AccessPolicy.supportsFullLegacy(40))
    }
}
