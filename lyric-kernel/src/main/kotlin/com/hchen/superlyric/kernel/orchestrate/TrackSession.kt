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
package com.hchen.superlyric.kernel.orchestrate

import com.hchen.superlyric.kernel.identity.TrackIdentity

/**
 * 一次曲目请求在编排器锁内的可变状态。
 *
 * 所有字段只在 [LyricOrchestrator] 的锁内读写；对外通过编排器的只读属性观察。
 */
internal class TrackSession {
    var epoch: Long = 0L
    var identity: TrackIdentity? = null
    var startedAtMs: Long = 0L
    var apiAttempts: Int = 0
    var hookDelivered: Boolean = false
    var apiDelivered: Boolean = false
    var hookTimeout: Cancellable? = null
}
