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
 * 一次歌词解析尝试的**语义化**结果。
 *
 * <h3>为什么必须有这个类型</h3>
 * 重构前，各条取词路径统一用「{@code SuperLyricData} 或 null」表达结果，
 * 于是"这首曲子确实没有歌词"与"这次网络请求失败了"被压成了同一个 null。
 * 编排器无法区分二者，只能把二者都当作"没有歌词"，最终把**任何一次网络抖动**
 * 合成为「纯音乐，请欣赏」并永久钉死（因为进入 FULL_ACTIVE 后不再重试）。
 *
 * 本类型把三种语义彻底分开，使编排器能做出正确决策：
 * <ul>
 *   <li>[Success] → 直接激活；</li>
 *   <li>[NoLyrics] / [Unsupported] → **终态**，可下发纯音乐占位；</li>
 *   <li>[TransientFailure] → **可重试**，绝不据此下发占位。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
sealed interface LyricOutcome {

    /**
     * 成功解析出全量歌词。
     */
    data class Success(val doc: LyricDoc) : LyricOutcome

    /**
     * **确定性**地没有歌词：曲目本身是纯音乐、平台明确返回"无歌词"状态、
     * 或宿主自己的歌词文档解析后为零行。
     *
     * 这是终态语义——收到它才允许下发「纯音乐，请欣赏」。
     *
     * @property reason 便于诊断的来源说明
     */
    data class NoLyrics(val reason: String) : LyricOutcome

    /**
     * 本次取词失败，但**不能推断**曲目没有歌词（网络超时、解密失败、解析异常等）。
     *
     * 这是可重试语义——据此下发纯音乐占位是本项目历史上最顽固的缺陷之一。
     *
     * @property cause 失败原因；仅作诊断用
     */
    data class TransientFailure(val cause: Throwable? = null) : LyricOutcome

    /**
     * 该来源不支持这首曲目（例如平台无版权、缺少必要的标识）。
     *
     * 与 [NoLyrics] 同样是终态，但语义上归因于"来源不可用"而非"曲目无歌词"。
     *
     * @property source 来源标识
     */
    data class Unsupported(val source: String) : LyricOutcome

    /** 是否为可重试结果。 */
    val isRetryable: Boolean
        get() = this is TransientFailure

    /** 是否已可确定最终结论（无需继续等待其他来源）。 */
    val isTerminal: Boolean
        get() = this is Success || this is NoLyrics || this is Unsupported
}
