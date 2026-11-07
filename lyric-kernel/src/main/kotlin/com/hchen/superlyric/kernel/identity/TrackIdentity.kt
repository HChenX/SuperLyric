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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.kernel.identity

import com.hchen.superlyric.kernel.catalog.CatalogId

import kotlin.math.abs

/**
 * 音轨身份
 *
 * 判定规则
 * - 双方都有稳定标识时只比较稳定标识，忽略命名空间前缀
 * - 否则比较组合判据：归一化标题须非空且相同，歌手须相容，
 *   双方时长已知时偏差不得超过 [DURATION_TOLERANCE_MS]
 */
data class TrackIdentity @JvmOverloads constructor(
    val source: String,
    val stableId: String?,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0L,
    /**
     * 曲库取词 ID；不参与 equals/hashCode/matches。
     */
    val catalogId: CatalogId? = null,
) {

    /**
     * 保持原五字段判等；catalogId 是伴生事实，不得改变临时身份升级或缓存键。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackIdentity) return false
        return source == other.source &&
                stableId == other.stableId &&
                title == other.title &&
                artist == other.artist &&
                durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = source.hashCode()
        result = 31 * result + (stableId?.hashCode() ?: 0)
        result = 31 * result + title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + durationMs.hashCode()
        return result
    }

    /**
     * 判断两者是否指向同一首曲目
     */
    fun matches(other: TrackIdentity?): Boolean {
        if (other == null) return false
        // 同一实例或完全相同的数据必须判等：标题归一化为空时（如 [Music]、(间奏)），
        // 组合判据连自身都不匹配，编排器会把同一首歌反复判成切歌
        if (this === other || this == other) return true

        val thisId = normalizeStableId(stableId)
        val otherId = normalizeStableId(other.stableId)
        if (thisId != null && otherId != null) {
            return thisId == otherId
        }

        val thisTitle = normalizeTitle(title)
        if (thisTitle.isEmpty()) return false
        if (thisTitle != normalizeTitle(other.title)) return false
        if (!artistCompatible(artist, other.artist)) return false

        return durationCompatible(durationMs, other.durationMs)
    }

    /** 是否具备可直接比较的稳定标识 */
    val hasStableId: Boolean get() = normalizeStableId(stableId) != null

    companion object {
        /** 时长容差：不同来源对同一曲目的时长上报常有数百毫秒差异 */
        const val DURATION_TOLERANCE_MS = 1000L

        /**
         * 拼装带命名空间的稳定标识
         *
         * @param namespace 平台命名空间，如 `qq`
         * @param rawId     平台原始标识；空白视为未知
         */
        @JvmStatic
        fun stableId(namespace: String, rawId: String?): String? {
            // 宿主会把 0/-1/null/unknown/none 写进 MEDIA_ID；组合成 qq:0 会被当成权威标识，
            // 使不同曲目恒等。占位值统一走 usableId，未知即 null。
            val trimmed = usableId(rawId) ?: return null
            return "$namespace:$trimmed"
        }

        private val PLACEHOLDER_IDS = setOf("0", "-1", "null", "unknown", "none")

        /** 过滤宿主写入的占位标识；空白与占位值返回 null */
        @JvmStatic
        fun usableId(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in PLACEHOLDER_IDS }

        private val NAMESPACE_PREFIX = Regex("""^[a-zA-Z][a-zA-Z0-9_-]{0,15}:""")

        /**
         * 剥离命名空间前缀，使 `qq:005Ct2` 与 `005Ct2` 可比
         */
        @JvmStatic
        fun normalizeStableId(id: String?): String? {
            val trimmed = id?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val stripped = NAMESPACE_PREFIX.replaceFirst(trimmed, "")
            return stripped.ifEmpty { null }
        }

        private val BRACKETED = Regex("""[(\[（【][^)\]）】]*[)\]）】]""")

        private val IGNORED_TITLE_CHARS = Regex("""[\s\-_·・.,!?，。！？、:：;；'"“”‘’~～]+""")

        /**
         * 标题归一化：去括号补充说明、去空白与常见标点、转小写
         */
        fun normalizeTitle(title: String): String =
            IGNORED_TITLE_CHARS.replace(BRACKETED.replace(title.lowercase(), ""), "").trim()

        /**
         * 歌手相容判定：归一化后一方包含另一方即视为相容，
         * 以覆盖 `A/B` 与 `A` 这类多歌手表示差异
         */
        fun artistCompatible(left: String, right: String): Boolean {
            val a = IGNORED_TITLE_CHARS.replace(left.lowercase(), "")
            val b = IGNORED_TITLE_CHARS.replace(right.lowercase(), "")
            if (a.isEmpty() || b.isEmpty()) return true
            return a.contains(b) || b.contains(a)
        }

        /**
         * 时长相容判定
         *
         * 双方都已知时才构成约束；仅一方已知不构成否定证据，否则任何一条
         * 缺少时长上报的路径都会被误判为换歌
         *
         * @param left  一侧时长（毫秒）；≤0 表示未知
         * @param right 另一侧时长（毫秒）；≤0 表示未知
         */
        @JvmStatic
        fun durationCompatible(left: Long, right: Long): Boolean {
            if (left <= 0L || right <= 0L) return true
            return abs(left - right) <= DURATION_TOLERANCE_MS
        }
    }
}
