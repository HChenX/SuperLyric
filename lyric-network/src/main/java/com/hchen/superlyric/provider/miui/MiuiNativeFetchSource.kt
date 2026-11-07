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
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.provider.qq.QQNetwork
import com.hchen.superlyric.shared.HostIdentitySource
import com.hchen.superlyric.shared.HostTrackFacts
import com.hchen.superlyric.shared.merge
import com.hchen.superlyric.shared.matches

/**
 * 小米音乐 Native 路径的网络取词源：身份来自 hooks-id，按宿主 songID 直查 QQ 曲库。
 */
class MiuiNativeFetchSource(
    private val identityFeed: HostIdentitySource,
) : InstallableLyricSource {

    override val sourceName: String = "MiuiNative"

    private val fetches = FetchSlot()
    private val dedupLock = Any()

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    @Volatile
    private var pendingId: Long = 0L

    @Volatile
    private var attemptedId: Long = 0L

    @Volatile
    private var inFlightId: Long = 0L

    override fun install() {
        identityFeed.subscribe(::onSongInfo)
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        resetDedupState()
        this.session = session
        this.pendingIdentity = identity

        identityFeed.refresh()
        val facts = identityFeed.currentFacts()
        if (facts != null) {
            onSongInfo(facts)
        } else {
            AndroidLog.logD(sourceName, "No SongInfo snapshot yet; awaiting host capture")
        }
    }

    override fun cancel() {
        fetches.invalidate()
        session = null
        pendingIdentity = null
        resetDedupState()
    }

    private fun onSongInfo(facts: HostTrackFacts) {
        val id = facts.identity.catalogId
            ?.takeIf { it.catalog == LyricCatalog.QQ }
            ?.id?.let(CatalogIds::qqSongId)
            ?: facts.secondaryId
        if (id <= 0L) return

        val target = session ?: return
        val base = pendingIdentity ?: return

        if (!facts.matches(base)) {
            AndroidLog.logD(
                sourceName,
                "$facts does not match the current track (\"${base.title}\"); waiting",
            )
            return
        }

        val identity = facts.merge(base)
        pendingIdentity = identity
        pendingId = id

        fetchAsync(id, identity, target)
    }

    private fun fetchAsync(id: Long, identity: TrackIdentity, session: LyricSource.Session) {
        if (!claimFetch(id)) return
        val token = fetches.open()

        fetches.execute {
            val outcome = QQNetwork.fetchByMid(
                "", id, identity,
                isCurrent = { fetches.isCurrent(token) },
                requests = fetches,
            )
            releaseInFlight(id)
            markAttempted(id)

            if (!fetches.isCurrent(token) || session !== this.session || pendingId != id) {
                AndroidLog.logD(sourceName, "Discarding result for id=$id: no longer the current track")
                return@execute
            }

            session.deliver(outcome)
        }
    }

    private fun claimFetch(id: Long): Boolean = synchronized(dedupLock) {
        if (id == attemptedId || id == inFlightId) return@synchronized false
        inFlightId = id
        true
    }

    private fun releaseInFlight(id: Long): Unit = synchronized(dedupLock) {
        if (inFlightId == id) inFlightId = 0L
    }

    private fun markAttempted(id: Long): Unit = synchronized(dedupLock) {
        attemptedId = id
    }

    private fun resetDedupState(): Unit = synchronized(dedupLock) {
        pendingId = 0L
        attemptedId = 0L
        inFlightId = 0L
    }
}
