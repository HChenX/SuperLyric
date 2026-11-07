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

import com.hchen.superlyric.kernel.catalog.CatalogIds

/**
 * 从音轨身份中解析 QQ 音乐的两种官方标识
 *
 * 只做正向识别：明确符合 songMID（14 位 base62）或 songID（纯数字）形态的才承认。
 * 排除式判定不成立：真实的数字 songId 会落进任何看似可排除的区间
 *
 */
object QQIdentity {

    /** 是否形如 songMID（14 位字母数字组合） */
    @JvmStatic
    fun isMid(raw: String?): Boolean = CatalogIds.qqMid(raw) != null

    /**
     * 在锚点捕获的 songMID 与 `MediaSession` 元数据自带的标识之间选出更可信的那个
     *
     * 锚点常先于新曲目的元数据到达（实测早约 8~30ms），此时它指向下一首，而元数据可能
     * 仍是上一首。切歌时锚点也可能没有触发（宿主改名或走了别的路径），于是 [capturedMid]
     * 停在上一首，把新曲目伪装成旧的，表现为漏切歌。
     */
    @JvmStatic
    fun preferredMid(capturedMid: String?, metadataId: String?): String? {
        val captured = capturedMid?.trim()?.takeIf { isMid(it) }
        val fromMetadata = metadataId?.trim()?.takeIf { isMid(it) }
        if (fromMetadata != null && fromMetadata != captured) return fromMetadata
        return captured ?: fromMetadata
    }

    /**
     * 是否为纯数字的 songID
     *
     * QQ 音乐的 `MediaSession` 分两步上报同一首曲目：先给数字 songID、再给 songMID。
     * 两者是同一音轨的两种表示，若把数字形态也当成独立稳定标识，宿主从 songID 切到
     * songMID 时会被判定为切歌
     *
     * @return 形如 `^\d{1,15}$` 时为 true
     */
    @JvmStatic
    fun isNumericId(raw: String?): Boolean = CatalogIds.isNumericId(raw)

}