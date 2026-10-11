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
package com.hchen.superlyric.kernel.outcome

import com.hchen.superlyric.kernel.model.LyricDoc

/**
 * 一次歌词解析尝试的**语义化**结果
 *
 * 三种语义必须分开，编排器才能做出正确决策：
 * - [Success] → 直接激活
 * - [NoLyrics] / [Unsupported] → **终态**，可下发纯音乐占位
 * - [TransientFailure] → **可重试**，绝不据此下发占位
 *
 * 若把它们压成同一个"失败"，编排器就无从区分"确实没有歌词"与"这次请求失败了"，
 * 只能把任何一次网络抖动都合成为「纯音乐，请欣赏」并永久钉死
 *
 * @author 焕晨HChen
 */
sealed interface LyricOutcome {

    /**
     * 成功解析出全量歌词
     */
    data class Success(val doc: LyricDoc) : LyricOutcome

    /**
     * **确定性**地没有歌词：曲目本身是纯音乐、平台明确返回"无歌词"状态，
     * 或宿主自己的解析函数明确判定"无歌词"
     *
     * **它是唯一允许下发「纯音乐，请欣赏」的结果**。注意"解析后为零行"不算：
     * 那是我们没读出来，不是对方说没有
     *
     * @property reason 便于诊断的来源说明
     */
    data class NoLyrics(val reason: String) : LyricOutcome

    /**
     * 本次取词失败，但**不能推断**曲目没有歌词：网络超时、解密失败、解析异常等
     *
     * 据此下发纯音乐占位，就会把一次网络抖动永久钉死为纯音乐
     *
     * @property cause 失败原因；仅作诊断用
     */
    data class TransientFailure(val cause: Throwable? = null) : LyricOutcome

    /**
     * 该来源不支持这首曲目，例如平台无版权、缺少必要的标识
     *
     * 与 [NoLyrics] 同为终态，但归因于"来源不可用"而非"曲目无歌词"，因此
     * **不得据此下发纯音乐占位**：请求要么根本没发出去，要么服务端没说"没有歌词"，
     * 据此占位就是在替对方编造结论。编排器把它归为"查不到"
     *
     * @property source 来源标识
     */
    data class Unsupported(val source: String) : LyricOutcome

    /** 是否为可重试结果 */
    val isRetryable: Boolean
        get() = this is TransientFailure

    /** 是否已可确定最终结论，无需继续等待其他来源 */
    val isTerminal: Boolean
        get() = this is Success || this is NoLyrics || this is Unsupported
}
