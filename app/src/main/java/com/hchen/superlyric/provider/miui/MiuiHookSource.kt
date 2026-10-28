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
 * 小米音乐 Hook 取词来源：从宿主曲目对象取回 songID，再走 QQ 音乐官方接口
 *
 * ## 取词流程
 * - 切歌时直接读宿主的 `MusicPlayerHelper#getPlaySong()`，拿到**当前**曲目对象
 * - 快照与编排器给出的音轨身份对不上时等下一个快照（见 [MiuiSongInfo.matches]）
 * - 取到匹配的快照后按 songID 拉取 QRC，宿主内部歌词模型一概不碰
 *
 * 不在这里直接解析宿主已经下载好的歌词：那套行/词模型随版本变动，而 songID 是协议层
 * 标识，接口返回的报文跨版本稳定
 *
 * @author 焕晨HChen
 */
class MiuiHookSource : HookLyricSource {

    override val sourceName: String = "MiuiHook"

    /** 取词执行槽位：见 [FetchSlot]——单线程会让新歌排在上一首在途请求后面 */
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
        // 去重状态只属于"一次取词"。Hook 来源的 start() 每个代际恰好调用一次，
        // 因此这里清空不会误伤本次取词；反之不清则切走再切回同一首时一个请求都不发。
        // 必须在登记会话**之前**清空：快照回调可能在登记之后立即到达，先登记再清空
        // 会把刚认领的请求一起作废
        resetDedupState()

        this.session = session
        this.pendingIdentity = identity

        // 宿主自己的曲目对象是权威来源：SongInfo 先于 MediaSession 元数据更新，
        // 因此切歌时这里读到的已经是新曲目
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

        // 快照可能与切歌交错（先于新元数据到达，或落后于新元数据）：对不上就等下一个，
        // 用另一首歌的 songID 取词会把 A 的歌词发布给 B
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
     * 发起一次取词
     *
     * 去重集中在这里：宿主在播放期间会反复推送元数据（每次状态栏歌词换行都会触发一次），
     * 若每次回调都发请求，一首取词失败的曲目会变成持续的轮询。这里按**尝试**去重——
     * 失败也登记，交给编排器转网络源重试，而不是由宿主回调的频率驱动重试
     */
    private fun fetchAsync(id: Long, identity: TrackIdentity, session: LyricSource.Session) {
        if (!claimFetch(id)) return

        fetches.execute {
            val outcome = QQNetwork.fetchByMid("", id, identity)
            releaseInFlight(id)
            markAttempted(id)

            // 投递时再确认一次：这次请求在途期间可能已经切歌
            if (session !== this.session || pendingId != id) {
                AndroidLog.logD(sourceName, "Discarding result for id=$id: no longer the current track")
                return@execute
            }

            session.deliver(outcome)
        }
    }

    /**
     * 认领一次取词
     *
     * "查—改"必须互斥：start 跑在编排器线程、快照回调跑在宿主线程、投递跑在取词池的线程上
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
