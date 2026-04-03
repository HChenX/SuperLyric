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

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import top.yukonga.miuix.kmp.basic.ScrollBehavior

val LocalViewModel =
    compositionLocalOf<ViewModel> { error("No view model.") }
val LocalMiuixScrollBehavior =
    compositionLocalOf<ScrollBehavior> { error("No scroll behavior") }
val LocalPagerState =
    compositionLocalOf<PagerState> { error("No pager state.") }
val LocalHandlePagerChange =
    compositionLocalOf<(Boolean, Int) -> Unit> { error("No handle pager change.") }