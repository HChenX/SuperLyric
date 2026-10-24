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

    /** songMID：14 位字母数字组合，且至少含一个字母（纯数字是 songID，不是 songMID） */

    private val ID_PATTERN = Regex("^\\d{1,15}$")

    fun midOf(identity: TrackIdentity): String? =
        TrackIdentity.normalizeStableId(identity.stableId)?.takeIf { isMid(it) }

    /** 是否形如 songMID（14 位字母数字组合） */
    @JvmStatic
    fun isMid(raw: String?): Boolean {
        if (raw == null || raw.length != 14) return false
        var hasLetter = false
        for (ch in raw) {
            val letter = (ch in 'a'..'z') || (ch in 'A'..'Z')
            val digit = ch in '0'..'9'
            if (!letter && !digit) return false
            if (letter) hasLetter = true
        }
        return hasLetter
    }

    /**
     * 在"锚点捕获的 songMID"与"`MediaSession` 元数据自带的标识"之间选出更可信的那个
     *
     * ## 为什么不能简单地"信更新的"
     * 锚点常**先于**新曲目的元数据到达（实测早约 8~30ms），此时它说的是下一首，而元数据
     * 可能仍是上一首。反过来，切歌时锚点也可能**根本没触发**（宿主改名或走了别的路径），
     * 于是 [capturedMid] 一直停在上一首，把新曲目伪装成旧的 —— 表现为漏切歌。
     *
     * ## 判据
     * 只有当元数据自带一个**合法且不同**的 songMID 时才改用它：那一刻元数据与当前曲目
     * **原子绑定**，而锚点值已证明落后。其余情形一律沿用锚点——它是唯一能把数字 songID
     * 与 songMID 区分开的来源，元数据的 `MEDIA_ID` 常常只是数字 songID
     *
     * @param capturedMid 锚点最近捕获的 songMID；未捕获过时为 null
     * @param metadataId  `MediaMetadata.MEDIA_ID` 的原始值；缺失时为空串
     * @return 应当采用的 songMID；两者都不可用时返回 null
     */
    @JvmStatic
    fun preferredMid(capturedMid: String?, metadataId: String?): String? {
        val captured = capturedMid?.trim()?.takeIf { isMid(it) }
        val fromMetadata = metadataId?.trim()?.takeIf { isMid(it) }
        if (fromMetadata != null && fromMetadata != captured) return fromMetadata
        return captured ?: fromMetadata
    }

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
