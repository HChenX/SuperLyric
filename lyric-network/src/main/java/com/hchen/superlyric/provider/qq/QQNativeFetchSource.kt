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
package com.hchen.superlyric.provider.qq

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.shared.HostIdentitySource
import com.hchen.superlyric.shared.HostTrackFacts

/**
 * QQ 音乐 Native 路径的网络取词源：身份来自 hooks-id，mid 由宿主 Hook 事件补全，
 * 请求仍在 network。
 */
class QQNativeFetchSource(
    private val identityFeed: HostIdentitySource,
) : InstallableLyricSource {

    override val sourceName: String = "QQNative"

    private val fetches = FetchSlot()
    private val dedupLock = Any()
    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    @Volatile
    private var deliveredMid: String? = null

    @Volatile
    private var inFlightMid: String? = null

    override fun install() {
        identityFeed.subscribe(::onSongInfoCaptured)
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        this.session = session
        this.pendingIdentity = identity
        clearDedupState()

        val mid = CatalogIds.lyricId(identity, LyricCatalog.QQ, CatalogIds::qqMid)
        if (mid != null) {
            val songId = CatalogIds.qqSongId(TrackIdentity.normalizeStableId(identity.stableId)) ?: 0L
            fetchAsync(mid, songId, identity)
        } else {
            AndroidLog.logD(sourceName, "Awaiting LyricLoadBean capture for songMID")
        }
    }

    override fun cancel() {
        fetches.invalidate()
        session = null
        pendingIdentity = null
        clearDedupState()
    }

    private fun onSongInfoCaptured(facts: HostTrackFacts) {
        val mid = CatalogIds.lyricId(facts.identity, LyricCatalog.QQ, CatalogIds::qqMid) ?: return
        val songId = facts.secondaryId
        val title = facts.identity.title
        val artist = facts.identity.artist

        if (session == null) return
        val base = pendingIdentity ?: return
        val baseMid = CatalogIds.lyricId(base, LyricCatalog.QQ, CatalogIds::qqMid)

        if (baseMid != null && baseMid != mid) {
            AndroidLog.logD(
                sourceName,
                "Anchor for $mid predates the track change (current $baseMid); ignoring it",
            )
            return
        }

        val identity = base.copy(
            stableId = TrackIdentity.stableId("qq", mid),
            title = title.ifBlank { base.title },
            artist = artist.ifBlank { base.artist },
            catalogId = facts.identity.catalogId ?: base.catalogId,
        )
        pendingIdentity = identity
        fetchAsync(mid, songId, identity)
    }

    private fun fetchAsync(mid: String, songId: Long, identity: TrackIdentity) {
        // 去重判定先于推进代际：重复捕获若先 open，会把在途取词的结果静默作废且不重发
        val claimed = synchronized(dedupLock) {
            if (mid == deliveredMid || mid == inFlightMid) {
                false
            } else {
                inFlightMid = mid
                true
            }
        }
        if (!claimed) return
        val token = fetches.open()

        fetches.execute {
            val outcome = QQNetwork.fetchByMid(
                mid, songId, identity,
                isCurrent = { fetches.isCurrent(token) },
                requests = fetches,
            )
            synchronized(dedupLock) {
                if (inFlightMid == mid) inFlightMid = null
            }
            if (!fetches.isCurrent(token)) {
                AndroidLog.logD(sourceName, "Discarding stale result for $mid: fetch was superseded")
                return@execute
            }

            val target = session
            val current = pendingIdentity
            if (target == null || current == null || mid != CatalogIds.qqMid(
                    TrackIdentity.normalizeStableId(current.stableId)
                )
            ) {
                AndroidLog.logD(sourceName, "Discarding result for $mid: no longer the current track")
                return@execute
            }

            if (outcome is LyricOutcome.Success ||
                outcome is LyricOutcome.NoLyrics ||
                outcome is LyricOutcome.Unsupported
            ) {
                synchronized(dedupLock) { deliveredMid = mid }
            }
            target.deliver(outcome)
        }
    }

    private fun clearDedupState(): Unit = synchronized(dedupLock) {
        deliveredMid = null
        inFlightMid = null
    }
}
