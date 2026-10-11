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
package com.hchen.superlyric.provider.kugou

import android.content.Context

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.HookLyricSource
import com.hchen.superlyric.publisher.AbsPublisher

import java.io.File
import java.util.concurrent.Executors

/**
 * 酷狗 Hook 取词来源：从宿主自己的本地 KRC 文件读取原始报文。
 *
 * <h3>取词流程</h3>
 * <ol>
 *   <li>切歌时若已能从元数据拿到 32 位 Hash，立即尝试读本地文件；</li>
 *   <li>否则等 [KuGouAnchors] 的"歌词已就绪"信号，再读 Hash 与文件；</li>
 *   <li>本地无文件则回报 [LyricOutcome.Unsupported]，让编排器立刻转网络，
 *       而不必白等 800ms 的超时窗口；</li>
 *   <li>信号始终不来时，由编排器的超时兜底转网络。</li>
 * </ol>
 *
 * <h3>为什么只读本地文件、不自己发网络请求</h3>
 * 本地文件是宿主**自己**下载并解密的歌词缓存，属于"应用自身的原始载荷"，
 * 准确性最高。网络兜底交给 [KuGouApiSource]，两条路径职责不重叠。
 *
 * @author 焕晨HChen
 */
class KuGouHookSource : HookLyricSource {

    override val sourceName: String = "KugouHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-KugouHook").apply { isDaemon = true }
    }

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    /** 已成功发布的 Hash，避免宿主重复写入同一份歌词时重复解析。 */
    @Volatile
    private var deliveredId: String? = null

    override fun install() {
        KuGouAnchors.install { onLyricReady() }
    }

    override fun currentStableId(): String? = KuGouAnchors.readSongHash()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        this.session = session
        this.pendingIdentity = identity

        // 元数据里若已带 Hash，立刻尝试本地文件；否则等 Hook 信号
        val hash = hashOf(identity)
        if (hash != null) {
            resolveAsync(hash, identity, session)
        } else {
            AndroidLog.logD(sourceName, "Awaiting GlobalVariate lyric-ready signal")
        }
    }

    override fun cancel() {
        session = null
        pendingIdentity = null
    }

    private fun onLyricReady() {
        val activeSession = session ?: return
        val identity = pendingIdentity ?: return
        val hash = KuGouAnchors.readSongHash() ?: return

        if (hash == deliveredId) return
        resolveAsync(hash, identity, activeSession)
    }

    private fun resolveAsync(hash: String, identity: TrackIdentity, target: LyricSource.Session) {
        executor.execute {
            val doc = readLocalKrc(hash, identity)
            if (doc == null) {
                AndroidLog.logD(sourceName, "No local KRC for hash=$hash; falling through")
                target.deliver(LyricOutcome.Unsupported(sourceName))
                return@execute
            }
            deliveredId = hash
            AndroidLog.logI(sourceName, "Loaded local KRC for hash=$hash, lines=${doc.lines.size}")
            target.deliver(LyricOutcome.Success(doc))
        }
    }

    private fun readLocalKrc(hash: String, identity: TrackIdentity) = runCatching {
        for (dir in candidateDirs()) {
            val file = File(dir, "$hash.krc")
            if (!file.isFile || file.length() <= 4L) continue

            val bytes = file.readBytes()
            val doc = LyricPipeline.parse(
                LyricRequest(
                    format = LyricFormat.KRC,
                    bytes = bytes,
                    title = identity.title,
                    artist = identity.artist,
                    durationMs = identity.durationMs,
                    lyricId = hash,
                )
            )
            if (doc != null && !doc.isEmpty) return@runCatching doc
        }
        null
    }.onFailure {
        AndroidLog.logW(sourceName, "Failed to read local KRC: ${it.message}")
    }.getOrNull()

    /**
     * 宿主可能把歌词缓存放在多个位置；逐个探测，命中即用。
     */
    private fun candidateDirs(): List<File> {
        val dirs = ArrayList<File>(8)
        val context: Context? = AbsPublisher.getAppContext()
        if (context != null) {
            context.getExternalFilesDir(null)?.let { external ->
                dirs += File(external, "kugou/lyric")
                dirs += File(external, "lyric")
            }
            dirs += File(context.filesDir, "lyric")
            dirs += File(context.filesDir, "kugou/lyric")
        }
        dirs += File("/sdcard/kugou/lyric/")
        dirs += File("/sdcard/Android/data/com.kugou.android/files/kugou/lyric/")
        dirs += File("/data/user/0/com.kugou.android/files/lyric/")
        return dirs
    }

    private companion object {
        private val HASH_PATTERN = Regex("^[0-9a-fA-F]{32}$")

        /** 只有当稳定标识确实是 32 位十六进制哈希时，才可作为文件名使用。 */
        fun hashOf(identity: TrackIdentity): String? {
            val raw = TrackIdentity.normalizeStableId(identity.stableId) ?: return null
            return raw.takeIf { HASH_PATTERN.matches(it) }
        }
    }
}
