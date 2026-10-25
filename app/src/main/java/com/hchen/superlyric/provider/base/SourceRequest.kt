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
package com.hchen.superlyric.provider.base

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次取词请求的不可变快照。
 *
 * session 与 identity 必须同时发布，否则异步回调可能拿到新 session + 旧 identity。
 */
data class SourceRequest(
    val session: LyricSource.Session,
    val identity: TrackIdentity,
    val generation: Long,
)

/**
 * 按请求代际筛选异步结果。
 *
 * 本地只做预筛；最终仍由编排器的 epoch 丢弃迟到投递。
 */
class SourceRequestGate {
    private val generation = AtomicLong()
    private val lock = Any()

    @Volatile
    private var current: SourceRequest? = null

    fun open(identity: TrackIdentity, session: LyricSource.Session): SourceRequest =
        synchronized(lock) {
            val request = SourceRequest(session, identity, generation.incrementAndGet())
            current = request
            request
        }

    fun current(): SourceRequest? = current

    fun invalidate() {
        synchronized(lock) {
            generation.incrementAndGet()
            current = null
        }
    }

    fun isCurrent(request: SourceRequest?): Boolean =
        request != null && synchronized(lock) { current === request }
}
