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

/**
 * 单值、按 key 匹配的暂存槽。
 *
 * 宿主可能在会话建立前就吐出一首歌的数据；key 为稳定标识，空 key 永不匹配。
 */
class KeyedStash<T> {
    private val lock = Any()
    private var keys: Set<String> = emptySet()
    private var value: T? = null

    fun put(key: String?, value: T) = put(listOf(key), value)

    fun put(keys: Collection<String?>, value: T) {
        val valid = keys.filterNotNull().filter { it.isNotBlank() }.toSet()
        if (valid.isEmpty()) return
        synchronized(lock) {
            this.keys = valid
            this.value = value
        }
    }

    fun takeIf(key: String?): T? = takeIfAny(key)

    fun takeIfAny(key: String?): T? {
        if (key.isNullOrBlank()) return null
        synchronized(lock) {
            if (key !in keys) return null
            val result = value
            keys = emptySet()
            value = null
            return result
        }
    }

    fun clear() {
        synchronized(lock) {
            keys = emptySet()
            value = null
        }
    }
}
