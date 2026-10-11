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

/**
 * 从音轨身份中解析 QQ 音乐的两种官方标识
 *
 * 只做**正向识别**：明确符合 songMID（14 位 base62）或 songID（纯数字）形态的才承认。
 * 不要回到"用正则排除某类 id"的思路 —— 真实的数字 songId 会落进任何看似可排除的区间
 *
 * @author 焕晨HChen
 */
internal object QQIdentity {

    /** songMID：14 位字母数字组合 */
    private val MID_PATTERN = Regex("^[0-9A-Za-z]{14}$")

    private val ID_PATTERN = Regex("^\\d{1,15}$")

    fun midOf(identity: TrackIdentity): String? =
        TrackIdentity.normalizeStableId(identity.stableId)?.takeIf { MID_PATTERN.matches(it) }

    /**
     * 是否为**纯数字**的 songID
     *
     * QQ 音乐的 `MediaSession` 分两步上报同一首曲目：先给数字 songID、再给 songMID。
     * 两者是同一音轨的两种表示，若把数字形态也当成独立稳定标识，宿主从 songID 切到
     * songMID 时会被判定为切歌
     *
     * @return 形如 `^\d{1,15}$` 时为 true
     */
    @JvmStatic
    fun isNumericId(raw: String?): Boolean = raw != null && ID_PATTERN.matches(raw)

    fun idOf(identity: TrackIdentity): Long =
        TrackIdentity.normalizeStableId(identity.stableId)
            ?.takeIf { ID_PATTERN.matches(it) }
            ?.toLongOrNull()
            ?: 0L
}
