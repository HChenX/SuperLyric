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
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.provider.base.HookLyricSource
import com.hchen.superlyric.provider.qq.QQNetwork

/**
 * 小米音乐 Hook 取词来源。从宿主曲目对象取 songID，再请求 QQ 音乐接口。
 *
 * 切歌时读取 `MusicPlayerHelper#getPlaySong()`；快照与当前身份不匹配时等待下一个；
 * 取到匹配的 songID 后拉取 QRC，不读取宿主内部歌词模型。
 *
 * @author 焕晨HChen
 */
class MiuiHookSource : HookLyricSource {

    override val sourceName: String = "MiuiHook"

    /** 取词执行槽位；单线程会让新歌排在上一首在途请求后面 */
    private val fetches = FetchSlot()

    /** 去重状态的锁，见 [claimFetch] */
    private val dedupLock = Any()

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    /** 本次取词采信的快照 songID；为 0 表示还没有采信任何快照 */
    @Volatile
    private var pendingId: Long = 0L

    /** 最近一次由宿主上报的 songID，仅用于诊断 */
    @Volatile
    private var capturedId: Long = 0L

    @Volatile
    private var attemptedId: Long = 0L

    @Volatile
    private var inFlightId: Long = 0L

    override fun install() {
        MiuiAnchors.install { snapshot -> onSongInfo(snapshot) }
    }

    override fun currentStableId(): String? = capturedId.takeIf { it > 0L }?.toString()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        // 去重状态按一次取词重置；必须在登记会话前清空，避免紧接的快照回调被作废
        resetDedupState()

        this.session = session
        this.pendingIdentity = identity

        // SongInfo 先于 MediaSession 更新，切歌时通常已可读到新曲目
        val snapshot = MiuiAnchors.readCurrentSong()
        if (snapshot != null) {
            onSongInfo(snapshot)
        } else {
            AndroidLog.logD(sourceName, "No SongInfo snapshot yet; awaiting host capture")
        }
    }

    override fun cancel() {
        session = null
        pendingIdentity = null
        resetDedupState()
    }

    private fun onSongInfo(snapshot: MiuiAnchors.SongSnapshot) {
        if (snapshot.id <= 0L) return
        capturedId = snapshot.id

        val target = session ?: return
        val base = pendingIdentity ?: return

        // 快照可能与切歌交错；身份不匹配时等待下一个，避免把 A 的歌词发布给 B
        if (!MiuiSongInfo.matches(base, snapshot)) {
            AndroidLog.logD(
                sourceName,
                "$snapshot does not match the current track (\"${base.title}\"); waiting"
            )
            return
        }

        val identity = MiuiSongInfo.merge(base, snapshot)
        pendingIdentity = identity
        pendingId = snapshot.id

        fetchAsync(snapshot.id, identity, target)
    }

    /**
     * 发起一次取词。
     *
     * 按 songID 去重：播放期间宿主会反复推送元数据，失败也登记，避免按回调频率重试。
     */
    private fun fetchAsync(id: Long, identity: TrackIdentity, session: LyricSource.Session) {
        if (!claimFetch(id)) return

        fetches.execute {
            val outcome = QQNetwork.fetchByMid("", id, identity)
            releaseInFlight(id)
            markAttempted(id)

            // 投递时再确认一次：本次请求在途期间可能已经切歌
            if (session !== this.session || pendingId != id) {
                AndroidLog.logD(sourceName, "Discarding result for id=$id: no longer the current track")
                return@execute
            }

            session.deliver(outcome)
        }
    }

    /**
     * 认领一次取词。查改必须互斥；start、快照回调和取词线程都会访问。
     */
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
