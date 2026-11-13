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
package com.hchen.superlyric.kernel.catalog

import com.hchen.superlyric.kernel.identity.TrackIdentity

/**
 * 曲库命名空间
 */
enum class LyricCatalog(val namespace: String) {
    QQ("qq"),
    KUGOU("kugou"),
    NETEASE("netease"),
    SPOTIFY("spotify"),
}

/**
 * 曲库取词标识：平台原始 ID，不带命名空间
 */
data class CatalogId(
    val catalog: LyricCatalog,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "catalog id must not be blank" }
    }
}

/**
 * 四类平台原始标识的格式规则
 *
 * 规则只在此处维护；hooks-id 从这里取宿主值，network 从这里复核检索结果。
 */
object CatalogIds {

    private val NUMERIC_ID = Regex("^\\d{1,15}$")
    private val KUGOU_HASH = Regex("^[0-9a-fA-F]{32}$")
    private val SPOTIFY_TRACK_ID = Regex("^[A-Za-z0-9]{16,32}$")

    fun qqMid(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.length != 14) return null
        var hasLetter = false
        for (ch in value) {
            val letter = ch in 'a'..'z' || ch in 'A'..'Z'
            val digit = ch in '0'..'9'
            if (!letter && !digit) return null
            if (letter) hasLetter = true
        }
        return value.takeIf { hasLetter }
    }

    fun isNumericId(raw: String?): Boolean =
        raw?.trim()?.let { NUMERIC_ID.matches(it) } == true

    fun qqSongId(raw: String?): Long? = numericId(raw)

    /**
     * 身份的曲库取词标识
     *
     * 与 [catalog] 一致的 catalogId 优先，否则归一化 stableId；两条来源都经 [normalize] 校验，
     * 各曲库的校验函数保持独立。
     */
    @JvmStatic
    fun <T> lyricId(
        identity: TrackIdentity,
        catalog: LyricCatalog,
        normalize: (String?) -> T?,
    ): T? {
        val fromCatalog = identity.catalogId?.takeIf { it.catalog == catalog }?.id?.let(normalize)
        return fromCatalog ?: normalize(TrackIdentity.normalizeStableId(identity.stableId))
    }

    fun kugouHash(raw: String?): String? =
        raw?.trim()?.takeIf { KUGOU_HASH.matches(it) }

    fun neteaseSongId(raw: String?): Long? = numericId(raw)

    fun spotifyTrackId(raw: String?): String? =
        raw?.trim()?.takeIf { SPOTIFY_TRACK_ID.matches(it) }


    private fun numericId(raw: String?): Long? =
        raw?.trim()?.takeIf { NUMERIC_ID.matches(it) }?.toLongOrNull()?.takeIf { it > 0L }
}
