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
package com.hchen.superlyric.provider.qq

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.outcome.LyricOutcome

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 检索候选的遍历规则
 *
 * 检索得来的候选只代表"这个 mid 是否可用"，不构成平台对整首曲目的结论。遍历必须在首个
 * 成功处停止，并在全部失败时返回可重试失败，否则会把有歌词的曲目钉成纯音乐占位。
 */
class CandidateFetchTest {

    @Test
    fun `a no-lyric candidate falls through to the next one`() {
        val first = candidate("first")
        val second = candidate("second")
        val third = candidate("third")
        val visited = ArrayList<String>()

        val outcome = fetchFirstUsable(listOf(first, second, third)) { item ->
            visited += item.mid
            if (item == second) {
                LyricOutcome.Success(LyricDoc.EMPTY)
            } else {
                LyricOutcome.NoLyrics("candidate has no lyric")
            }
        }

        assertTrue(outcome is LyricOutcome.Success, "outcome=$outcome")
        assertEquals(listOf("first", "second"), visited)
    }

    @Test
    fun `a search miss is never reported as no lyrics`() {
        val outcome = fetchFirstUsable(listOf(candidate("first"), candidate("second"))) { item ->
            if (item.mid == "first") {
                LyricOutcome.NoLyrics("first candidate has no lyric")
            } else {
                LyricOutcome.Unsupported("second candidate cannot serve the track")
            }
        }

        assertTrue(outcome is LyricOutcome.TransientFailure, "outcome=$outcome")
        assertTrue(outcome !is LyricOutcome.NoLyrics, "outcome=$outcome")
    }

    @Test
    fun `the last transient failure cause is kept`() {
        val firstFailure = IllegalStateException("first candidate failed")
        val lastFailure = IllegalStateException("last candidate failed")

        val outcome = fetchFirstUsable(listOf(candidate("first"), candidate("last"))) { item ->
            LyricOutcome.TransientFailure(if (item.mid == "first") firstFailure else lastFailure)
        }

        assertTrue(outcome is LyricOutcome.TransientFailure, "outcome=$outcome")
        assertSame(lastFailure, (outcome as LyricOutcome.TransientFailure).cause)
    }

    private fun candidate(mid: String) =
        QQNetwork.CandidateItem(
            mid = mid,
            songId = 0L,
            title = "未来へ",
            artist = "kiroro",
            durationMs = 333_000L,
        )
}
