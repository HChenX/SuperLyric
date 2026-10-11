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
package com.hchen.superlyric.provider.bodian

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.HookLyricSource
import com.hchen.superlyric.utils.LyricDiag

import java.io.File
import java.util.concurrent.Executors

/**
 * 波点音乐 Hook 取词来源：读取宿主自己的本地原生歌词文件
 *
 * ## 三种结果的语义
 * - 宿主给了路径且解析成功 → [LyricOutcome.Success]
 * - 宿主**明确没有**给路径 → [LyricOutcome.NoLyrics]：这是宿主的确定性信号，此时才允许下发纯音乐占位
 * - 给了路径但文件读不到 / 解不开 → [LyricOutcome.Unsupported]：不知道是不是没歌词，交给网络路径再试
 *
 * 格式识别交给 [LyricFormat.AUTO]，按载荷自身的结构特征判断：LRCX 有 `[kuwo:]` 头部、
 * JSON 有 `sentences` 根键，其余走通用解析器
 *
 * @author 焕晨HChen
 */
class BodianHookSource : HookLyricSource {

    override val sourceName: String = "BodianHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-BodianHook").apply { isDaemon = true }
    }

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    @Volatile
    private var capturedRid: String? = null

    private var lastProcessedPath: String? = null

    /**
     * 已解析、尚未被会话消费的结果
     *
     * 宿主分两次上报标识（先旧后新），而歌词常在第一次之前就解析完毕。那时投递会被
     * 随后推进的 epoch 判为过期丢弃，表现为"解析成功却整轮超时转网络"。故按稳定标识暂存，
     * 等它自己的会话建立后补投
     */
    @Volatile
    private var stashedDoc: LyricDoc? = null

    @Volatile
    private var stashedId: String? = null

    override fun install() {
        BodianAnchors.install { filePath, title, artist, album, rid ->
            onLyricFile(filePath, title, artist, album, rid)
        }
    }

    override fun currentStableId(): String? = capturedRid

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        this.session = session
        this.pendingIdentity = identity
        lastProcessedPath = null

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

    private fun onLyricFile(filePath: String, title: String, artist: String, album: String, rid: String) {
        if (rid.isNotEmpty()) capturedRid = rid

        val base = pendingIdentity ?: return

        // 只取稳定标识。曲名/歌手/时长一律不在解析时确定 —— 这个回调可能早于元数据更新到达，
        // 那时 pendingIdentity 还停在上一首，烤进文档就再也纠不回来了（见 withMetadata）
        val identity = base.copy(
            stableId = TrackIdentity.stableId(NAMESPACE, rid) ?: base.stableId,
        )

        if (filePath.isEmpty()) {
            // 否定结论同样要认身份：这可能是**上一首**迟到的"没有歌词文件"。算到当前曲目
            // 头上会占掉本代际的 Hook 名额（编排器只收第一次投递），把随后到达的真实
            // 结果当成重复投递丢弃，最终给一首有歌词的曲目下发纯音乐占位
            if (identity.stableId != null && identity.stableId != pendingIdentity?.stableId) {
                AndroidLog.logD(
                    sourceName,
                    "Discarding stale no-lyric verdict for ${identity.stableId}",
                )
                return
            }
            AndroidLog.logD(sourceName, "Host reported no lyric file for this track")
            session?.deliver(LyricOutcome.NoLyrics("bodian: host has no lyric"))
            return
        }
        if (filePath == lastProcessedPath) return
        lastProcessedPath = filePath

        executor.execute {
            val outcome = parseFile(filePath, album, identity.stableId, title, artist)
            if (outcome is LyricOutcome.Success) {
                stashedDoc = outcome.doc
                stashedId = identity.stableId
            }

            // 会话与身份都必须在**投递时**重读，不能在回调入口处捕获：
            // 解析在后台线程进行，期间可能已经切歌换掉了 session，
            // 投给入口处捕获的那个旧会话会被 epoch 判为过期而丢弃 —— 表现为
            // "Hook 明明解析成功却整轮超时转网络"，网络再匹配不上就成了纯音乐占位。
            // 身份对不上也先留着，投给别的曲目的会话会被当成新曲目的歌词发布出去
            val target = session
            val current = pendingIdentity
            if (target == null || !(identity.stableId == null || identity.stableId == current?.stableId)) {
                AndroidLog.logD(
                    sourceName,
                    "Stashed lyric for ${identity.stableId}; will replay once its session is up",
                )
                return@execute
            }
            target.deliver(outcome.withMetadata(current, title, artist))
        }
    }

    private fun parseFile(
        filePath: String,
        album: String,
        stableId: String?,
        hostTitle: String,
        hostArtist: String,
    ): LyricOutcome {
        val file = File(filePath)
        if (!file.isFile || file.length() == 0L) {
            AndroidLog.logW(sourceName, "Lyric file is missing or empty: $filePath")
            return LyricOutcome.Unsupported("bodian: file unreadable")
        }

        val text = try {
            file.readText()
        } catch (t: Throwable) {
            AndroidLog.logW(sourceName, "Failed to read lyric file: ${t.message}")
            return LyricOutcome.Unsupported("bodian: file read failed")
        }

        val doc = try {
            LyricDiag.parse(
                sourceName,
                LyricRequest(
                    format = LyricFormat.AUTO,
                    text = text,
                    // album 是宿主为**这一份文件**给出的，不属于身份，因此在这里带上
                    album = album,
                    lyricId = stableId,
                )
            )
        } catch (t: Throwable) {
            AndroidLog.logW(sourceName, "Failed to parse lyric file: ${t.message}")
            return LyricOutcome.TransientFailure(t)
        }

        if (doc == null || doc.isEmpty) {
            AndroidLog.logW(sourceName, "Parsed no lines from $filePath")
            return LyricOutcome.Unsupported("bodian: parsed empty")
        }

        AndroidLog.logI(sourceName, "Parsed local lyric file, lines=${doc.lines.size}")
        return LyricOutcome.Success(doc)
    }

    /**
     * 用音轨身份补齐曲目信息
     *
     * **身份优先，而不是"仅在为空时补齐"**：波点的歌词文件回调会早于元数据更新到达，
     * 解析时若带上身份，烤进去的会是**上一首**的曲名与时长。只在字段为空时才覆盖，
     * 那些错误值就永远纠不过来
     *
     * @param hostTitle  宿主为这份歌词文件给出的曲名；身份里没有时用它兜底
     * @param hostArtist 同上，歌手
     */
    private fun LyricDoc.withMetadata(
        identity: TrackIdentity?,
        hostTitle: String = "",
        hostArtist: String = "",
    ): LyricDoc {
        if (identity == null) return this
        return copy(
            title = identity.title.ifBlank { hostTitle }.ifBlank { title },
            artist = identity.artist.ifBlank { hostArtist }.ifBlank { artist },
            durationMs = if (identity.durationMs > 0L) identity.durationMs else durationMs,
            lyricId = identity.stableId ?: lyricId,
        )
    }

    private fun LyricOutcome.withMetadata(
        identity: TrackIdentity?,
        hostTitle: String = "",
        hostArtist: String = "",
    ): LyricOutcome = when (this) {
        is LyricOutcome.Success -> LyricOutcome.Success(doc.withMetadata(identity, hostTitle, hostArtist))
        else -> this
    }

    private companion object {
        const val NAMESPACE = "kuwo"
    }
}
