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
package com.hchen.superlyric.shared

import android.media.MediaMetadata
import com.hchen.superlyric.kernel.identity.TrackIdentity

/**
 * 宿主侧身份来源
 *
 * 具体实现位于 :hooks-id；:lyric-network 只依赖本契约，不依赖 hook 层。
 */
interface HostIdentitySource {

    fun install()

    fun currentFacts(): HostTrackFacts?

    /**
     * 从宿主元数据构造当前音轨身份；返回 null 时由 MusicProvider 使用默认身份。
     */
    fun current(metadata: MediaMetadata): TrackIdentity? = null

    fun currentStableId(): String? = currentFacts()?.identity?.stableId

    /** 订阅身份事件；新订阅者立即收到当前值 */
    fun subscribe(listener: (HostTrackFacts) -> Unit): AutoCloseable

    /** 从宿主主动刷新一次当前快照；不支持时为空实现 */
    fun refresh() {}
}
