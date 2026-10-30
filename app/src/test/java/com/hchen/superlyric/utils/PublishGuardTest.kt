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
package com.hchen.superlyric.utils

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 发布内容与系统当前曲目的匹配规则
 *
 * 时长容差允许元数据刷新带来的抖动，不能收紧成精确相等；
 * 某一侧缺少信息时视为不知道，不能判为不一致，否则首次发布会被守卫拒掉。
 */
class PublishGuardTest {

    @Test
    fun `matching title and duration pass`() {
        assertTrue(PublishGuard.matches("Song", 180_000L, "Song", 180_000L))
    }

    @Test
    fun `a different title is rejected`() {
        assertFalse(PublishGuard.matches("Song", 180_000L, "Another", 180_000L))
    }

    @Test
    fun `title comparison trims surrounding whitespace`() {
        assertTrue(PublishGuard.matches(" Song ", 180_000L, "Song", 180_000L))
    }

    @Test
    fun `a duration beyond the tolerance is rejected`() {
        assertFalse(
            PublishGuard.matches(
                "Song", 180_000L, "Song", 180_000L + PublishGuard.DURATION_TOLERANCE_MS + 1
            )
        )
    }

    @Test
    fun `duration jitter inside the tolerance passes`() {
        assertTrue(
            PublishGuard.matches(
                "Song", 180_000L, "Song", 180_000L + PublishGuard.DURATION_TOLERANCE_MS
            )
        )
    }

    @Test
    fun `an unknown side cannot veto the publication`() {
        assertTrue(PublishGuard.matches("Song", 180_000L, null, 0L))
        assertTrue(PublishGuard.matches(null, 180_000L, "Song", 180_000L))
        assertTrue(PublishGuard.matches("Song", 0L, "Song", 180_000L))
        assertTrue(PublishGuard.matches("Song", 180_000L, "Song", 0L))
        assertTrue(PublishGuard.matches("  ", 180_000L, "Song", 180_000L))
    }
}
