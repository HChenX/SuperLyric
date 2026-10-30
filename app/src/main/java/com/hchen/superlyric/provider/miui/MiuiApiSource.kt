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
package com.hchen.superlyric.provider.miui

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.provider.qq.QQNetwork

/**
 * 小米音乐 API 取词来源。Hook 路径没有 songID 时按标题和歌手检索。
 *
 * <p>检索落空按可重试失败处理，不发布纯音乐占位。
 *
 * @author 焕晨HChen
 */
class MiuiApiSource : LyricSource {

    override val sourceName: String = "MiuiApi"

    private val requests = FetchSlot()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        val token = requests.open()
        requests.execute {
            if (!requests.isCurrent(token)) return@execute
            val outcome = runCatching { resolve(identity) }
                .getOrElse { error ->
                    AndroidLog.logW(sourceName, "Resolve failed: ${error.message}")
                    LyricOutcome.TransientFailure(error)
                }
            if (requests.isCurrent(token)) session.deliver(outcome)
        }
    }

    override fun cancel() {
        requests.invalidate()
    }

    private fun resolve(identity: TrackIdentity): LyricOutcome {
        // Hook 路径超时后宿主的曲目对象通常已经就绪，先按 songID 直查，无需检索
        val snapshot = MiuiAnchors.readCurrentSong()
        if (snapshot != null && MiuiSongInfo.matches(identity, snapshot)) {
            AndroidLog.logI(sourceName, "Direct fetch by host songID=${snapshot.id}")
            return QQNetwork.fetchByMid("", snapshot.id, identity)
        }

        val candidate = QQNetwork.searchBest(identity)
            // 检索落空表示未匹配，按可重试失败处理
            ?: return LyricOutcome.TransientFailure(null)

        AndroidLog.logI(sourceName, "Matched candidate mid=${candidate.first}")
        return QQNetwork.fetchByMid(candidate.first, candidate.second, identity)
    }
}
