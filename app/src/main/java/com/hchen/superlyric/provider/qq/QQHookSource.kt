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
import com.hchen.superlyric.provider.base.HookLyricSource

/**
 * QQ 音乐 Hook 取词来源：从宿主取回官方 songMID，再自行拉取原生 QRC
 *
 * ## 取词流程
 * - 切歌时若元数据已带 14 位 songMID，立刻拉取
 * - 否则等 [QQAnchors] 的 `LyricLoadBean` 构造信号
 * - 信号带来 songMID 后立即拉取——这条路径上的标识来自宿主自身，
 *   因此不会出现"同名翻唱错配"
 * - 信号始终不来时由编排器超时兜底转检索路径
 *
 * @author 焕晨HChen
 */
class QQHookSource : HookLyricSource {

    override val sourceName: String = "QQHook"

    /** 取词执行槽位：见 [FetchSlot]——单线程会让新歌排在上一首在途请求后面 */
    private val fetches = FetchSlot()

    /** [deliveredMid] / [inFlightMid] 的锁：见 [claimFetch] */
    private val dedupLock = Any()

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    /** 最近一次由宿主上报的 songMID，供 Provider 构建一致的音轨身份 */
    @Volatile
    private var capturedMid: String? = null

    /**
     * 已经成功取回歌词的 songMID，避免宿主重复构造 LyricLoadBean 时反复发起网络请求
     *
     * 只对**本次取词**有效：每次 [start] 都会清空。宿主切歌不会调用 [cancel]（只有确认
     * 停止才会），留着它会让"切走再切回"的曲目一个请求都发不出
     */
    @Volatile
    private var deliveredMid: String? = null

    /**
     * 正在取词中的 songMID
     *
     * 必须在**派发前**登记：宿主会在几十毫秒内连续构造多次 LyricLoadBean，
     * 若只在取词完成后才登记，这几次并发请求会全部落空去重、各发一次网络请求。
     */
    @Volatile
    private var inFlightMid: String? = null

    override fun install() {
        QQAnchors.install { songMid, songId, title, artist ->
            onSongInfoCaptured(songMid, songId, title, artist)
        }
    }

    override fun currentStableId(): String? = capturedMid

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        this.session = session
        this.pendingIdentity = identity

        // 去重状态只属于"一次取词"。Hook 来源的 start() 每个代际恰好调用一次，因此这里
        // 清空不会误伤本次取词，只会丢掉已经死掉的旧代际的登记。反之不清则致命：
        // 切走再切回同一首曲目时，本方法会直接命中 deliveredMid，一个请求都不发，
        // 编排器空等 Hook 超时后转检索，检索不成便是纯音乐占位
        clearDedupState()

        val mid = QQIdentity.midOf(identity)
        if (mid != null) {
            capturedMid = mid
            fetchAsync(mid, QQIdentity.idOf(identity), identity)
        } else {
            AndroidLog.logD(sourceName, "Awaiting LyricLoadBean capture for songMID")
        }
    }

    override fun cancel() {
        session = null
        pendingIdentity = null
        // 播放停止后重新播放同一首歌必须能再次取词，因此这里要清空去重状态
        clearDedupState()
    }

    private fun onSongInfoCaptured(songMid: String, songId: Long, title: String, artist: String) {
        if (songMid.isNotEmpty()) capturedMid = songMid

        // 仅判断"当前有没有人在等结果"；真正投给谁由 fetchAsync 在投递时决定
        if (session == null) return
        if (songMid.isEmpty()) return
        val base = pendingIdentity ?: return

        val baseMid = QQIdentity.midOf(base)

        // 锚点常**先于**新曲目的元数据到达（实测早约 8~30ms）。此刻它说的是下一首，
        // 而编排器还没切歌：照取不误的话，这轮请求会带着上一首的曲名、歌手与时长出门
        // （实测：一首曲目被发布成了上一首的歌名），而且它属于旧代际，结果只会被丢弃。
        // 留给随后的 start() 取词——那时编排器手里才是权威身份
        //
        // 这一次不会白丢：[capturedMid] 已在上面更新，因此下一次 buildIdentity 就会带上
        // 这个 mid，编排器随即判定切歌并调用 start()，由它按权威身份取词。
        // 若日志里这行之后**没有**紧跟着本来源的取词记录，说明切歌没被判定出来——
        // 那是身份抖动（宿主上报的标识乱变），不是本处的取舍问题
        if (baseMid != null && baseMid != songMid) {
            AndroidLog.logD(
                sourceName,
                "Anchor for $songMid predates the track change (current $baseMid); " +
                    "capturedMid updated, deferring the fetch to start()",
            )
            return
        }

        // 曲名与歌手以**锚点**为准：它们与 songMid 同属一次上报，而 base 可能仍是上一首
        val identity = base.copy(
            stableId = TrackIdentity.stableId(NAMESPACE, songMid),
            title = title.ifBlank { base.title },
            artist = artist.ifBlank { base.artist },
        )
        pendingIdentity = identity

        fetchAsync(songMid, songId, identity)
    }

    /**
     * 发起一次取词
     *
     * 去重集中在这里而不是调用点：宿主在播放期间会反复构造 `LyricLoadBean`，
     * 且 [start] 与锚点回调可能对同一首曲目各发一次。若只在某一个调用点去重，
     * 另一条路径仍会重复发起网络请求。
     *
     * 登记"已交付"的时机与投递绑定：取词是一次网络往返，期间可能已经切歌。投给入口处
     * 捕获的旧会话会被编排器按代际丢弃，而"已交付"若在投递之外提前登记，这首曲目在
     * 本次进程里就再也走不到 Hook 路径了
     */
    private fun fetchAsync(mid: String, songId: Long, identity: TrackIdentity) {
        if (!claimFetch(mid)) return

        fetches.execute {
            val outcome = QQNetwork.fetchByMid(mid, songId, identity)
            releaseInFlight(mid)

            val target = session
            val current = pendingIdentity
            if (target == null || current == null || mid != QQIdentity.midOf(current)) {
                AndroidLog.logD(sourceName, "Discarding result for $mid: no longer the current track")
                return@execute
            }

            if (outcome is LyricOutcome.Success) markDelivered(mid)
            target.deliver(outcome)
        }
    }

    /**
     * 认领一次取词
     *
     * 去重的"查—改"必须互斥：[start] 跑在编排器线程、锚点回调跑在宿主线程、投递跑在取词池
     * 的线程上，三者并发时两次检查可以同时通过，同一首歌会发两次网络请求
     */
    private fun claimFetch(mid: String): Boolean = synchronized(dedupLock) {
        if (mid == deliveredMid || mid == inFlightMid) return@synchronized false
        inFlightMid = mid
        true
    }

    private fun releaseInFlight(mid: String): Unit = synchronized(dedupLock) {
        if (inFlightMid == mid) inFlightMid = null
    }

    private fun markDelivered(mid: String): Unit = synchronized(dedupLock) {
        deliveredMid = mid
    }

    private fun clearDedupState(): Unit = synchronized(dedupLock) {
        deliveredMid = null
        inFlightMid = null
    }

    private companion object {
        const val NAMESPACE = "qq"
    }
}
