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

import com.hchen.superlyric.kernel.outcome.LyricOutcome

/**
 * 按检索顺序尝试候选，返回第一个可用结果
 *
 * 检索得来的候选不是平台对整首曲目的结论：候选无歌词、来源不可用或请求失败都只否定该候选，
 * 继续尝试下一个。全部候选都不成立时返回可重试失败，任何情况下不返回
 * [LyricOutcome.NoLyrics] 或 [LyricOutcome.Unsupported]；最后一次可重试失败的 cause
 * 保留用于诊断。
 */
internal fun fetchFirstUsable(
    candidates: List<QQNetwork.CandidateItem>,
    fetch: (QQNetwork.CandidateItem) -> LyricOutcome,
): LyricOutcome {
    var lastFailure: Throwable? = null
    for (candidate in candidates) {
        when (val outcome = fetch(candidate)) {
            is LyricOutcome.Success -> return outcome
            is LyricOutcome.TransientFailure -> outcome.cause?.let { lastFailure = it }
            else -> Unit
        }
    }
    return LyricOutcome.TransientFailure(lastFailure)
}
