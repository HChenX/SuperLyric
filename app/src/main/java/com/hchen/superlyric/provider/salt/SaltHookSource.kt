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
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 椒盐音乐 Hook 取词来源：解析宿主给出的原始歌词文本
 *
 * <p>只需一次解析、一个 Hook 点。宿主的行/词模型完全不需要触碰。
 *
 * @author 焕晨HChen
 */
class SaltHookSource : HookLyricSource {

    override val sourceName: String = "SaltHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-SaltHook").apply { isDaemon = true }
    }

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    @Volatile
    private var capturedSongId: String? = null

    private var lastSourceText: String? = null

    /**
     * 已解析、但尚未被任何会话消费的结果，按稳定标识暂存
     *
     * ## 为什么必须暂存
     * 椒盐的调用顺序是**先**把歌词交给 `MusicController`、**再**更新
     * `MediaSession` 元数据（实测相隔约 20ms）。而会话是在元数据到达、
     * 编排器判定切歌之后才建立的——于是 Hook 的成果会绑在"上一个"会话上，
     * 随代际推进被当作过期数据丢弃，表现为"歌词明明解析出来了却整轮超时转网络"。
     */
    @Volatile
    private var stashedDoc: LyricDoc? = null

    @Volatile
    private var stashedId: String? = null

    /** 宿主的真实曲目信息。 */
    data class SongInfo(val title: String, val artist: String, val durationMs: Long)

    /**
     * 按 `Song.getId()` 索引的真实曲目信息
     *
     * <p>椒盐会把 `MediaMetadata` 的 TITLE/ARTIST 改写成歌词行与 `"歌手 - 曲名"`，
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
        this.session = session
        this.pendingIdentity = identity
        lastSourceText = null

        // 补投：宿主先给歌词后发元数据，成果必须还给刚刚建立的新会话
        val stashed = stashedDoc
        // 必须要求暂存标识**非空**：两边都是 null 时相等，会把上一首的歌词当成本曲目的
        // 结果发布出去（直接投递那条路径本来就要求非空，这里不能更松）
        if (stashed != null && stashedId != null && stashedId == identity.stableId) {
            stashedDoc = null
            stashedId = null
            AndroidLog.logD(sourceName, "Replaying stashed lyric for id=${identity.stableId}")
            session.deliver(LyricOutcome.Success(stashed.withMetadata(identity)))
        }
    }

    override fun cancel() {
        session = null
        pendingIdentity = null
        stashedDoc = null
        stashedId = null
    }

    private fun handleLyrics(sourceText: String, songId: String) {
        if (songId.isNotEmpty()) capturedSongId = songId
        if (sourceText == lastSourceText) return
        lastSourceText = sourceText

        val base = pendingIdentity
        val stableId = TrackIdentity.stableId(NAMESPACE, songId) ?: base?.stableId

        executor.execute {
            val doc = try {
                LyricDiag.parse(
                    sourceName,
                    LyricRequest(
                        format = LyricFormat.AUTO,
                        text = sourceText,
                        // 不在此处带曲目元数据：解析可能发生在切歌之前，那时 pendingIdentity
                        // 还是上一首的，带进来就会把旧曲名/旧时长烤进文档、事后无法纠正。
                        // 元数据一律在投递时由 withMetadata 按当时的身份回填。
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

            stashedDoc = doc
            stashedId = stableId
            AndroidLog.logI(sourceName, "Parsed sourceText, lines=${doc.lines.size}")

            // 只有当这份结果确实属于当前会话时才直接投递；否则留给 start() 补投。
            // 绝不能投给一个属于别的曲目的会话——那会被当成新曲目的歌词发布出去。
            val target = session
            val current = pendingIdentity
            if (target != null && stableId != null && stableId == current?.stableId) {
                target.deliver(LyricOutcome.Success(doc.withMetadata(current)))
            } else {
                AndroidLog.logD(
                    sourceName,
                    "Stashed lyric for $stableId; will replay once its session is up",
                )
            }
        }
    }

    /**
     * 用音轨身份补齐曲目信息
     *
     * <p>**身份优先，而不是"仅在为空时补齐"**：椒盐的歌词先于元数据到达，
     * 解析时用的可能是**上一首**的身份，于是旧曲名与旧时长会被烤进文档。
     * 若只在字段为空时才覆盖，这些错误值就永远纠正不过来（实测第二首会带着第一首的曲名）。
     * 因此只要身份里有值就一律以身份为准。
     */
    private fun LyricDoc.withMetadata(identity: TrackIdentity?): LyricDoc {
        if (identity == null) return this
        return copy(
            title = identity.title.ifBlank { title },
            artist = identity.artist.ifBlank { artist },
            durationMs = if (identity.durationMs > 0L) identity.durationMs else durationMs,
            lyricId = identity.stableId ?: lyricId,
        )
    }

    private companion object {
        const val NAMESPACE = "salt"

        /** 真实曲目信息的缓存上限 */
        const val MAX_CACHED_SONGS = 32
    }
}
