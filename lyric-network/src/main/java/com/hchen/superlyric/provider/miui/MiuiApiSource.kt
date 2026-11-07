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
package com.hchen.superlyric.provider.miui

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.provider.qq.QQNetwork
import com.hchen.superlyric.shared.HostIdentitySource
import com.hchen.superlyric.shared.matches

/**
 * 小米音乐 API 取词：Hook 路径没有 songID 时按标题和歌手检索
 */
class MiuiApiSource(
    private val identityFeed: HostIdentitySource,
) : LyricSource {

    override val sourceName: String = "MiuiApi"

    private val requests = FetchSlot()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.launch(sourceName, session) { token -> resolve(identity, token) }
    }

    override fun cancel() {
        requests.invalidate()
    }

    private fun resolve(identity: TrackIdentity, token: Long): LyricOutcome {
        identityFeed.refresh()
        val facts = identityFeed.currentFacts()
        val hostId = facts?.identity?.catalogId
            ?.takeIf { it.catalog == LyricCatalog.QQ }
            ?.id?.let(CatalogIds::qqSongId)
            ?: facts?.secondaryId?.takeIf { it > 0L }
        if (facts != null && hostId != null && facts.matches(identity)) {
            AndroidLog.logI(sourceName, "Direct fetch by host songID=$hostId")
            return QQNetwork.fetchByMid("", hostId, identity,
                isCurrent = { requests.isCurrent(token) }, requests = requests)
        }

        val candidate = QQNetwork.searchBest(
            identity, requests,
            isCurrent = { requests.isCurrent(token) },
        )
            ?: return LyricOutcome.TransientFailure(null)

        AndroidLog.logI(sourceName, "Matched candidate mid=${candidate.first}")
        return QQNetwork.fetchByMid(candidate.first, candidate.second, identity,
            isCurrent = { requests.isCurrent(token) }, requests = requests)
    }
}
