/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.ui.data

import androidx.compose.ui.unit.dp

object UIConstants {
    const val HOME_PAGE_INDEX = 0
    const val API_PAGE_INDEX = 1
    const val ABOUT_PAGE_INDEX = 2
    const val PAGE_COUNT = 3

    val WIDE_SCREEN_THRESHOLD = 840.dp
    val MEDIUM_WIDTH_THRESHOLD = 600.dp
    const val PORTRAIT_ASPECT_RATIO_THRESHOLD = 1.2f

    /**
     * 是否按宽屏（分屏 / 横屏）布局呈现
     *
     * 判定只有这一份：主布局与背景效果共用它，避免在 840dp / 600dp 边界上
     * 因 `>` 与 `>=` 的差异给出相反结论
     *
     * @param widthDp  可用宽度（dp）
     * @param heightDp 可用高度（dp）
     */
    fun isWideScreen(widthDp: Float, heightDp: Float): Boolean {
        if (widthDp >= WIDE_SCREEN_THRESHOLD.value) return true
        return widthDp >= MEDIUM_WIDTH_THRESHOLD.value &&
                (heightDp / widthDp) < PORTRAIT_ASPECT_RATIO_THRESHOLD
    }
}