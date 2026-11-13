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
package com.hchen.superlyric.provider.salt

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.provider.base.KeyedStash
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.shared.HostTrackMetaSource
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.Executors

/**
 * 椒盐音乐 Hook 取词来源：解析宿主给出的原始歌词文本
 *
 * 只需一次解析、一个 Hook 点。宿主的行/词模型完全不需要触碰。
 *
 */
class SaltHookSource(
    private val meta: HostTrackMetaSource,
) : InstallableLyricSource {

    override val sourceName: String = "SaltHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-SaltHook").apply { isDaemon = true }
    }

    private val requests = SourceRequestGate()
    private val docStash = KeyedStash<LyricDoc>()

    /** start() 在编排线程重置，宿主回调线程读写；去重判断必须看到最新值 */
    @Volatile
    private var lastSourceText: String? = null

    override fun install() {
        SaltAnchors.install(object : SaltAnchors.LyricsListener {
            override fun onLyrics(sourceText: String, songId: String) {
                handleLyrics(sourceText, songId)
            }
        })
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.open(identity, session)
        lastSourceText = null
        docStash.takeIf(identity.stableId)?.let { stashed ->
            val id = TrackIdentity.normalizeStableId(identity.stableId).orEmpty()
            AndroidLog.logD(sourceName, "Replaying stashed lyric for id=${identity.stableId}")
            session.deliver(
                LyricOutcome.Success(
                    stashed.withMetadata(identity, meta.title(id), meta.artist(id))
                )
            )
        }
    }

    override fun cancel() {
        requests.invalidate()
        docStash.clear()
    }

    private fun handleLyrics(sourceText: String, songId: String) {
        if (sourceText == lastSourceText) return
        lastSourceText = sourceText

        val base = requests.current()?.identity
        val stableId = TrackIdentity.stableId(NAMESPACE, songId) ?: base?.stableId

        executor.execute {
            val doc = try {
                LyricDiag.parse(
                    sourceName,
                    LyricRequest(
                        format = LyricFormat.AUTO,
                        text = sourceText,
                        // 解析可能早于切歌完成；这里只带稳定 id，元数据在投递时
                        // 由当前请求身份回填，避免把上一首的曲名/时长写进文档。
                        lyricId = stableId,
                    )
                )
            } catch (t: Throwable) {
                AndroidLog.logW(sourceName, "Failed to parse sourceText: ${t.message}")
                null
            }

            if (doc == null || doc.isEmpty) {
                AndroidLog.logW(sourceName, "Parsed no lines from sourceText")
                return@execute
            }

            docStash.put(stableId, doc)
            AndroidLog.logI(sourceName, "Parsed sourceText, lines=${doc.lines.size}")

            // 只有当这份结果确实属于当前会话时才直接投递；否则留给 start() 补投。
            // 投给别的曲目的会话会被当作新曲目的歌词发布出去。
            val activeAtDelivery = requests.current()
            val target = activeAtDelivery?.session
            val current = activeAtDelivery?.identity
            if (target != null && stableId != null && stableId == current?.stableId) {
                target.deliver(
                    LyricOutcome.Success(
                        doc.withMetadata(current, meta.title(songId), meta.artist(songId))
                    )
                )
            } else {
                AndroidLog.logD(
                    sourceName,
                    "Stashed lyric for $stableId; will replay once its session is up",
                )
            }
        }
    }

    private companion object {
        const val NAMESPACE = "salt"
    }
}
