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
 * 从音轨身份中解析 QQ 音乐的两种官方标识。
 *
 * <p><b>为什么不能沿用旧实现的"哈希兜底 id"启发式</b>：
 * 旧代码用正则 {@code ^-?\d{1,10}$} 判断某个 id 是否为"标题哈希兜底值"，
 * 但真实的 QQ 数字 songId 恰好落在这个区间里，于是真 id 被误判为兜底值、
 * 引发 id 在两种表示之间振荡，进而产生假切歌。本实现改为**正向识别**：
 * 只有明确符合 songMID（14 位 base62）或 songID（纯数字）形态的才承认。
 *
 * @author 焕晨HChen
 */
internal object QQIdentity {

    /** songMID：14 位字母数字组合。 */
    private val MID_PATTERN = Regex("^[0-9A-Za-z]{14}$")

    private val ID_PATTERN = Regex("^\\d{1,15}$")

    fun midOf(identity: TrackIdentity): String? =
        TrackIdentity.normalizeStableId(identity.stableId)?.takeIf { MID_PATTERN.matches(it) }

    fun idOf(identity: TrackIdentity): Long =
        TrackIdentity.normalizeStableId(identity.stableId)
            ?.takeIf { ID_PATTERN.matches(it) }
            ?.toLongOrNull()
            ?: 0L
}
