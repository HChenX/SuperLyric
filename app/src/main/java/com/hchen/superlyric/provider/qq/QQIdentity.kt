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

    /**
     * 是否为**纯数字**的 songID。
     *
     * <p>QQ 音乐的 `MediaSession` 会分两步上报同一首曲目的标识：先给数字 songID、
     * 再给 songMID。两者是同一音轨的两种表示法，若把数字形态也当作独立稳定标识，
     * 宿主从 songID 切到 songMID 时会被判定为切歌——白白推进一次 epoch，
     * 使已完成的取词因代际过期而被丢弃，并重复发起一次网络请求。
     *
     * @return 形如 {@code ^\d{1,15}$} 时为 true
     */
    @JvmStatic
    fun isNumericId(raw: String?): Boolean = raw != null && ID_PATTERN.matches(raw)

    fun idOf(identity: TrackIdentity): Long =
        TrackIdentity.normalizeStableId(identity.stableId)
            ?.takeIf { ID_PATTERN.matches(it) }
            ?.toLongOrNull()
            ?: 0L
}
