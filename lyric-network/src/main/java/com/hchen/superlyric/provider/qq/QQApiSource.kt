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
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.provider.base.FetchSlot

/**
 * QQ 音乐 API 取词来源：Hook 路径拿不到 songMID 时，按歌名 + 歌手检索兜底
 *
 * 检索采纳门槛见 [QQNetwork]，未达标一律判为无候选，不下发另一首歌的歌词。
 *
 * 没有候选不等于平台确定没有歌词：可能是没有匹配上，也可能是报文形态变化。
 * 因此检索落空返回可重试失败，不下发纯音乐占位。
 *
 */
class QQApiSource : LyricSource {

    override val sourceName: String = "QQApi"

    private val requests = FetchSlot()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.launch(sourceName, session) { token -> resolve(identity, token) }
    }

    override fun cancel() {
        requests.invalidate()
    }

    private fun resolve(identity: TrackIdentity, token: Long): LyricOutcome {
        // 元数据自带 mid 时直接直查：这是宿主/平台给出的权威标识，NoLyrics 仍是平台结论
        val directMid = CatalogIds.lyricId(identity, LyricCatalog.QQ, CatalogIds::qqMid)
        if (directMid != null) {
            val songId = CatalogIds.qqSongId(TrackIdentity.normalizeStableId(identity.stableId)) ?: 0L
            AndroidLog.logI(sourceName, "Direct fetch by mid=$directMid")
            return QQNetwork.fetchByMid(directMid, songId, identity,
                isCurrent = { requests.isCurrent(token) }, requests = requests)
        }

        val candidates = QQNetwork.searchCandidates(
            identity, requests,
            isCurrent = { requests.isCurrent(token) },
        )
        if (candidates.isEmpty()) return LyricOutcome.TransientFailure(null)
        if (!requests.isCurrent(token)) return LyricOutcome.TransientFailure(null)

        return fetchFirstUsable(candidates) { candidate ->
            AndroidLog.logD(sourceName, "Trying candidate mid=${candidate.mid}")
            QQNetwork.fetchByMid(
                candidate.mid, candidate.songId, identity,
                isCurrent = { requests.isCurrent(token) }, requests = requests,
            )
        }
    }
}
