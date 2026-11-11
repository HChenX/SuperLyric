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
package com.hchen.superlyric.kernel.pipeline

/**
 * 单个解析候选的结果
 *
 * 只记录解析器标识、是否接受、拒绝原因和行数，不携带歌词正文；
 * 正文仍由 LyricDiag 的请求/文档转储负责。
 */
data class LyricParseTrace(
    val parserId: String,
    val accepted: Boolean,
    val reason: String? = null,
    val lineCount: Int = 0,
)
