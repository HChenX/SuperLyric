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

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.provider.base.FetchSlot

/**
 * QQ 音乐 API 取词来源：Hook 路径拿不到 songMID 时，按歌名 + 歌手检索兜底。
 *
 * <p>检索采纳门槛已收紧（见 [QQNetwork]），未达标一律判为无候选，
 * 宁可不下发也不下发另一首歌的歌词。
 *
 * <p>但"没候选"**不等于**"平台确定没有歌词"：可能是没匹配上、也可能是报文形态变了。
 * 因此检索落空返回可重试失败，绝不下发纯音乐占位——一次检索抖动不该把有歌词的曲目钉死。
 *
 * @author 焕晨HChen
 */
class QQApiSource : LyricSource {

    override val sourceName: String = "QQApi"

    private val requests = FetchSlot()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        val token = requests.open()
        requests.execute {
            if (!requests.isCurrent(token)) return@execute
            val outcome = runCatching { resolve(identity) }
                .getOrElse { error ->
                    AndroidLog.logW(sourceName, "Search failed: ${error.message}")
                    LyricOutcome.TransientFailure(error)
                }
            if (requests.isCurrent(token)) session.deliver(outcome)
        }
    }

    override fun cancel() {
        requests.invalidate()
    }

    private fun resolve(identity: TrackIdentity): LyricOutcome {
        // 元数据自带 mid 时直接直查，无需检索
        val directMid = QQIdentity.midOf(identity)
        if (directMid != null) {
            AndroidLog.logI(sourceName, "Direct fetch by mid=$directMid")
            return QQNetwork.fetchByMid(directMid, QQIdentity.idOf(identity), identity)
        }

        val candidate = QQNetwork.searchBest(identity)
            // 检索落空 ≠ 平台确定没有歌词：可能只是没匹配上，或报文结构变了。
            // NoLyrics 允许下发纯音乐占位，误判一次就把有歌词的曲目钉死了
            ?: return LyricOutcome.TransientFailure(null)

        AndroidLog.logI(sourceName, "Matched candidate mid=${candidate.first}")
        return QQNetwork.fetchByMid(candidate.first, candidate.second, identity)
    }
}
