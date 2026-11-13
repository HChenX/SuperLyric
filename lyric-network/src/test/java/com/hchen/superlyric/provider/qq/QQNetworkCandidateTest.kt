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

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.match.LyricScorer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * QQ 检索候选的排序
 *
 * 同一首歌在平台曲库可能有多个 mid，歌词可用性各不相同。候选顺序决定先尝试哪个 mid，
 * 因此分数降序必须稳定：同分时保持检索顺序，不能把检索首位换掉。
 */
class QQNetworkCandidateTest {

    @Test
    fun `candidates are ordered by descending score`() {
        val identity = TrackIdentity("test", null, "未来へ", "kiroro", 333_000L)
        val exact = candidate("exact00000000", "未来へ", "kiroro", 333_000L)
        val offByFourSeconds = candidate("off4000000000", "未来へ", "kiroro", 329_000L)
        val wrongArtist = candidate("wrong00000000", "未来へ", "tove styrke", 333_000L)

        val ranked = QQNetwork.rankCandidates(identity, listOf(wrongArtist, offByFourSeconds, exact))

        assertEquals(listOf(exact, offByFourSeconds), ranked)
    }

    @Test
    fun `candidates at or below the acceptance line are dropped`() {
        val identity = TrackIdentity("test", null, "未来へ", "kiroro", 333_000L)
        // 歌名 1.0、歌手 0.5、时长 5 秒内 0.5 → 恰好 75.0，采纳线要求严格大于
        val exactlyAtLine = candidate("exactly0000000", "未来へ", "kiroro / tove styrke", 328_000L)
        assertEquals(
            75.0,
            LyricScorer.scoreCandidate(
                identity.title,
                identity.artist,
                identity.durationMs,
                exactlyAtLine.title,
                exactlyAtLine.artist,
                exactlyAtLine.durationMs,
            ),
            0.01,
        )

        assertTrue(QQNetwork.rankCandidates(identity, listOf(exactlyAtLine)).isEmpty())
    }

    @Test
    fun `candidates with the same score keep the search order`() {
        val identity = TrackIdentity("test", null, "未来へ", "kiroro", 333_000L)
        val first = candidate("first00000000", "未来へ", "kiroro", 333_000L)
        val second = candidate("second0000000", "未来へ", "kiroro", 333_000L)

        val ranked = QQNetwork.rankCandidates(identity, listOf(first, second))

        assertEquals(listOf(first, second), ranked)
    }

    private fun candidate(mid: String, title: String, artist: String, durationMs: Long) =
        QQNetwork.CandidateItem(
            mid = mid,
            songId = 0L,
            title = title,
            artist = artist,
            durationMs = durationMs,
        )
}
