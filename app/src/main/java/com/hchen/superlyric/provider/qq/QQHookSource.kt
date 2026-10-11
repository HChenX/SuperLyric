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
import com.hchen.superlyric.provider.base.HookLyricSource

import java.util.concurrent.Executors

/**
 * QQ 音乐 Hook 取词来源：从宿主取回官方 songMID，再自行拉取原生 QRC。
 *
 * <h3>取词流程</h3>
 * <ol>
 *   <li>切歌时若元数据已带 14 位 songMID，立刻拉取；</li>
 *   <li>否则等 [QQAnchors] 的 {@code LyricLoadBean} 构造信号；</li>
 *   <li>信号带来 songMID 后立即拉取——这条路径上的标识来自宿主自身，
 *       因此不会出现"同名翻唱错配"；</li>
 *   <li>信号始终不来时由编排器超时兜底转检索路径。</li>
 * </ol>
 *
 * @author 焕晨HChen
 */
class QQHookSource : HookLyricSource {

    override val sourceName: String = "QQHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-QQHook").apply { isDaemon = true }
    }

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    /** 最近一次由宿主上报的 songMID，供 Provider 构建一致的音轨身份。 */
    @Volatile
    private var capturedMid: String? = null

    override fun install() {
        QQAnchors.install { songMid, songId, title, artist ->
            onSongInfoCaptured(songMid, songId, title, artist)
        }
    }

    override fun currentStableId(): String? = capturedMid

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        this.session = session
        this.pendingIdentity = identity

        val mid = QQIdentity.midOf(identity)
        if (mid != null) {
            capturedMid = mid
            fetchAsync(mid, QQIdentity.idOf(identity), identity, session)
        } else {
            AndroidLog.logD(sourceName, "Awaiting LyricLoadBean capture for songMID")
        }
    }

    override fun cancel() {
        session = null
        pendingIdentity = null
    }

    private fun onSongInfoCaptured(songMid: String, songId: Long, title: String, artist: String) {
        if (songMid.isNotEmpty()) capturedMid = songMid

        val activeSession = session ?: return
        val base = pendingIdentity ?: return

        // 宿主上报的元数据比 MediaSession 更可靠，缺失时用它补齐
        val identity = base.copy(
            stableId = TrackIdentity.stableId(NAMESPACE, songMid),
            title = base.title.ifBlank { title },
            artist = base.artist.ifBlank { artist },
        )
        pendingIdentity = identity

        fetchAsync(songMid, songId, identity, activeSession)
    }

    private fun fetchAsync(mid: String, songId: Long, identity: TrackIdentity, target: LyricSource.Session) {
        executor.execute {
            val outcome = QQNetwork.fetchByMid(mid, songId, identity)
            target.deliver(outcome)
        }
    }

    private companion object {
        const val NAMESPACE = "qq"
    }
}
