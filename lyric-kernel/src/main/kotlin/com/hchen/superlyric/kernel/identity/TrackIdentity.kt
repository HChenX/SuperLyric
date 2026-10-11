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

import kotlin.math.abs

/**
 * 音轨身份
 *
 * ## 判定规则
 * 1. 双方都有可用的稳定标识 → **只比稳定标识**（剥离命名空间前缀后比较）
 * 2. 否则退回组合判据：标题归一化后必须相同**且非空**，歌手须相容，
 *    且双方时长已知时偏差不超过 [DURATION_TOLERANCE_MS]
 *
 * 不要退回"标题相同即同一首"：同名的 Intro / Interlude 会被永久合并；而标识表示法
 * 不同时，Hook 捕获又会被当成过期数据丢弃
 *
 * @property source     来源标识（`qq` / `kugou` 等），用于诊断
 * @property stableId   平台侧不可变唯一标识；未知时为 null
 * @property title      曲名
 * @property artist     歌手
 * @property durationMs 曲目总时长；未知时为 0
 *
 * @author 焕晨HChen
 */
data class TrackIdentity(
    val source: String,
    val stableId: String?,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0L,
) {

    /**
     * 判断两者是否指向同一首曲目
     */
    fun matches(other: TrackIdentity?): Boolean {
        if (other == null) return false

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
            val trimmed = rawId?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return "$namespace:$trimmed"
        }

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
         * 双方都已知时才构成约束；**仅一方已知不构成否定证据** —— 否则任何一条
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
