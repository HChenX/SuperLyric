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
package com.hchen.superlyric.provider.cider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CiderAnchorsTest {

    enum class NativeSource { APPLE, MUSIXMATCH, NETEASE, USER, UNKNOWN }

    class UserResult(@JvmField val a: String)

    class MxmResult(
        @JvmField val a: String?,
        @JvmField val d: String?,
        @JvmField val e: String?,
    )

    class ChangedUserResult(@JvmField val a: Int)

    private fun reader() = CiderAnchors.RawTexts(UserResult::class.java, MxmResult::class.java)

    @Test
    fun communityTtmlPreservesEntitiesWordTagsAndWhitespace() {
        val raw = " \n<tt><p begin=\"1s\"><span begin=\"1s\" end=\"2s\">a &amp; b</span></p></tt>\n"
        assertEquals(listOf(raw), reader().community(UserResult(raw)))
    }

    @Test
    fun musixmatchKeepsRawSyllableAndLineCandidatesFromOneResponse() {
        val syllables = """[{"ts":1.0,"te":3.0,"l":[{"c":"hello ","o":0.0},{"c":"world","o":1.0}]}]"""
        val body = "[00:01.000]hello world"
        val lines = "[00:02.000]line fallback"
        assertEquals(listOf(syllables, body, lines), reader().mxm(MxmResult(body, syllables, lines)))
    }

    @Test
    fun suspendedFailedAndEmptyResponsesAreNotLyricBodies() {
        val reader = reader()
        assertTrue(reader.community(null).isEmpty())
        assertTrue(reader.community(NativeSource.APPLE).isEmpty())
        assertTrue(reader.mxm(null).isEmpty())
        assertTrue(reader.mxm(MxmResult("", null, "  ")).isEmpty())
        assertEquals(listOf("[00:01]one"), reader.mxm(MxmResult("[00:01]one", null, "[00:01]one")))
    }

    @Test
    fun changedResponseSignaturesFailBeforeHookInstallation() {
        assertThrows(NoSuchFieldException::class.java) {
            CiderAnchors.RawTexts(ChangedUserResult::class.java, MxmResult::class.java)
        }
    }

    @Test
    fun nativeChoicesIncludeAutoAndRiseCommunity() {
        assertEquals(null, CiderAnchors.sourceOf(null))
        assertEquals(CiderRawStore.Source.APPLE, CiderAnchors.sourceOf(NativeSource.APPLE))
        assertEquals(CiderRawStore.Source.MUSIXMATCH, CiderAnchors.sourceOf(NativeSource.MUSIXMATCH))
        assertEquals(CiderRawStore.Source.NETEASE, CiderAnchors.sourceOf(NativeSource.NETEASE))
        assertEquals(CiderRawStore.Source.COMMUNITY, CiderAnchors.sourceOf(NativeSource.USER))
        assertThrows(IllegalArgumentException::class.java) { CiderAnchors.sourceOf(NativeSource.UNKNOWN) }
    }
}
