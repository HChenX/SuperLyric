/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

import android.content.Context
import com.hchen.superlyric.kernel.orchestrate.LyricSource

/**
 * 一次 Provider 的取词计划：native 对应 Hook 路径，generic 对应 API 兜底。
 */
data class LyricPlan(
    val native: LyricSource?,
    val generic: LyricSource?,
)

/**
 * 需要安装生命周期的取词来源。install 在包加载时调用一次；onApplicationCreated 在主进程创建时调用。
 */
interface InstallableLyricSource : LyricSource {
    fun install()

    fun onApplicationCreated(context: Context) {}
}
