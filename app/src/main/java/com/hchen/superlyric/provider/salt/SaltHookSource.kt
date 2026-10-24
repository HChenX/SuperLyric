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
import com.hchen.superlyric.provider.base.HookLyricSource
import com.hchen.superlyric.provider.base.KeyedStash
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 椒盐音乐 Hook 取词来源：解析宿主给出的原始歌词文本
 *
 * 只需一次解析、一个 Hook 点。宿主的行/词模型完全不需要触碰。
 *
 * @author 焕晨HChen
 */
class SaltHookSource : HookLyricSource {

    override val sourceName: String = "SaltHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-SaltHook").apply { isDaemon = true }
    }

    private val requests = SourceRequestGate()
    private val docStash = KeyedStash<LyricDoc>()

    @Volatile
    private var capturedSongId: String? = null

    private var lastSourceText: String? = null

    /** 宿主的真实曲目信息。 */
    data class SongInfo(val title: String, val artist: String, val durationMs: Long)

    /**
     * 按 `Song.getId()` 索引的真实曲目信息
     *
     * 椒盐会把 `MediaMetadata` 的 TITLE/ARTIST 改写成歌词行与 `"歌手 - 曲名"`，
     * 元数据不可信；而宿主自己的 `Song` 对象带着干净的曲名、歌手与时长，
     * 且其 `id` 与 `LyricsDocument` 一起给出的 songId **完全一致**，可精确关联。
     */
    private val songStore = ConcurrentHashMap<String, SongInfo>()

    override fun install() {
        SaltAnchors.install(object : SaltAnchors.LyricsListener {
            override fun onLyrics(sourceText: String, songId: String) {
                handleLyrics(sourceText, songId)
            }

            override fun onSong(songId: String, title: String, artist: String, durationMs: Long) {
                if (songId.isEmpty()) return
                // Song 探针是宿主自己的切歌点，必须推进身份；只在 handleLyrics 里写
                // 会让“切到无歌词曲目”仍返回上一首的身份，编排器判同一首、旧歌词继续显示。
                capturedSongId = songId
                // 只做缓存，容量到顶即整体清空——保留最近若干首足够覆盖切歌时序
                if (songStore.size >= MAX_CACHED_SONGS) songStore.clear()
                songStore[songId] = SongInfo(title, artist, durationMs)
            }
        })
    }

    /** 取回指定曲目的真实信息；未知时返回 null */
    fun songInfo(songId: String): SongInfo? = songStore[songId]

    override fun currentStableId(): String? = capturedSongId

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.open(identity, session)
        lastSourceText = null
        docStash.takeIf(identity.stableId)?.let { stashed ->
            val id = TrackIdentity.normalizeStableId(identity.stableId).orEmpty()
            val info = songInfo(id)
            AndroidLog.logD(sourceName, "Replaying stashed lyric for id=${identity.stableId}")
            session.deliver(
                LyricOutcome.Success(
                    stashed.withMetadata(identity, info?.title.orEmpty(), info?.artist.orEmpty())
                )
            )
        }
    }

    override fun cancel() {
        requests.invalidate()
        docStash.clear()
    }

    private fun handleLyrics(sourceText: String, songId: String) {
        if (songId.isNotEmpty()) capturedSongId = songId
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
            // 绝不能投给一个属于别的曲目的会话——那会被当成新曲目的歌词发布出去。
            val activeAtDelivery = requests.current()
            val target = activeAtDelivery?.session
            val current = activeAtDelivery?.identity
            if (target != null && stableId != null && stableId == current?.stableId) {
                val info = songInfo(songId)
                target.deliver(
                    LyricOutcome.Success(
                        doc.withMetadata(current, info?.title.orEmpty(), info?.artist.orEmpty())
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

        /** 真实曲目信息的缓存上限 */
        const val MAX_CACHED_SONGS = 32
    }
}
