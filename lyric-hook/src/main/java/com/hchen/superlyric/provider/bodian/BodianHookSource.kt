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
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.provider.base.KeyedStash
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

import java.io.File
import java.util.concurrent.Executors

/**
 * 波点 Hook 取词：读取宿主本地歌词文件。
 *
 * 成功返回解析结果；宿主明确无路径才是 NoLyrics；路径存在但读取/解析失败是 Unsupported。
 */
class BodianHookSource : InstallableLyricSource {

    override val sourceName: String = "BodianHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-BodianHook").apply { isDaemon = true }
    }

    private data class PathStash(
        val rid: String,
        val filePath: String,
        val title: String,
        val artist: String,
        val album: String,
    )

    private data class ParsedStash(val doc: LyricDoc, val rid: String?, val baseId: String?)

    private val requests = SourceRequestGate()
    private val docStash = KeyedStash<ParsedStash>()
    private val pathStash = KeyedStash<PathStash>()

    /** start() 在编排线程重置，宿主回调线程读写；去重判断必须看到最新值 */
    @Volatile
    private var lastProcessedPath: String? = null

    override fun install() {
        BodianAnchors.install(::onLyricFile)
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.open(identity, session)
        lastProcessedPath = null

        docStash.takeIf(identity.stableId)?.let { stashed ->
            AndroidLog.logD(sourceName, "Replaying stashed lyric for id=${identity.stableId}")
            session.deliver(LyricOutcome.Success(stashed.doc.withMetadata(identity)))
        }

        val pathKey = TrackIdentity.normalizeStableId(identity.stableId)
        pathStash.takeIf(pathKey)?.let { path ->
            executor.execute {
                val outcome = parseFile(path.filePath, path.album, identity.stableId)
                session.deliver(outcome.withMetadata(identity, path.title, path.artist))
            }
        }
    }

    override fun cancel() {
        requests.invalidate()
        pathStash.clear()
        docStash.clear()
    }

    private fun onLyricFile(filePath: String, title: String, artist: String, album: String, rid: String) {

        val active = requests.current()
        if (active == null) {
            if (filePath.isEmpty()) {
                AndroidLog.logW(sourceName, "No session yet and host reported no lyric file; dropping")
                return
            }
            if (rid.isEmpty()) {
                AndroidLog.logW(sourceName, "No session yet and no rid; cannot associate the file: $filePath")
                return
            }
            pathStash.put(TrackIdentity.normalizeStableId(rid), PathStash(rid, filePath, title, artist, album))
            AndroidLog.logD(sourceName, "Stashed lyric file until its session is up: rid=$rid")
            return
        }
        val base = active.identity

        // 只取稳定标识。曲名、歌手、时长不在解析时确定：该回调可能早于元数据更新到达，
        // 此时基础身份仍属于上一首，写入文档后无法纠正（见 withMetadata）
        val identity = base.copy(
            stableId = TrackIdentity.stableId(NAMESPACE, rid) ?: base.stableId,
        )

        if (filePath.isEmpty()) {
            // 否定结论也要校验身份：这可能是上一首迟到的“没有歌词文件”。记到当前曲目
            // 会占用本代际的 Hook 名额（编排器只收第一次投递），随后到达的真实结果会被
            // 当作重复投递丢弃，最终给有歌词的曲目下发纯音乐占位。
            // rid 为空时 identity 退化为 base.stableId，上述判据恒等，预载曲目的“无词”
            // 会投给当前曲目。无词是终态，只能在同一 rid 的证据下采纳。
            if (rid.isEmpty() || identity.stableId == null || identity.stableId != base.stableId) {
                AndroidLog.logW(
                    sourceName,
                    "Discarding no-lyric verdict without a matching rid: rid=\"$rid\", " +
                            "verdict=${identity.stableId}, pending=${base.stableId}",
                )
                return
            }
            AndroidLog.logD(sourceName, "Host reported no lyric file for this track")
            active.session.deliver(LyricOutcome.NoLyrics("bodian: host has no lyric"))
            return
        }
        if (filePath == lastProcessedPath) return
        lastProcessedPath = filePath

        executor.execute {
            val outcome = parseFile(filePath, album, identity.stableId)
            if (outcome is LyricOutcome.Success) {
                docStash.put(listOf(identity.stableId, base.stableId), ParsedStash(outcome.doc, identity.stableId, base.stableId))
            }

            // 会话与身份必须在投递时重读，不能在回调入口捕获：解析在后台线程进行，
            // 期间可能切歌；投给入口处的过期会话会被 epoch 判为过期而丢弃，表现为
            // Hook 解析成功却超时转网络，网络匹配不上时还会下发纯音乐占位。
            // 身份对不上也先保留，投给其它曲目的会话会被当作新曲目歌词发布。
            val activeAtDelivery = requests.current()
            val current = activeAtDelivery?.identity
            val sameTrack = identity.stableId == current?.stableId || base.stableId == current?.stableId
            if (activeAtDelivery == null || !sameTrack) {
                AndroidLog.logD(
                    sourceName,
                    "Stashed lyric for ${identity.stableId}; will replay once its session is up",
                )
                return@execute
            }
            activeAtDelivery.session.deliver(outcome.withMetadata(current, title, artist))
            // 直接投递成功后清掉暂存：`start()` 的补投只在投不出去时保留；
            // 保留会让同一曲目再次 `start()`（重播）时重放同一文档
            if (outcome is LyricOutcome.Success) {
                docStash.clear()
            }
        }
    }

    private fun parseFile(
        filePath: String,
        album: String,
        stableId: String?,
    ): LyricOutcome {
        val file = File(filePath)
        if (!file.isFile || file.length() == 0L) {
            AndroidLog.logW(sourceName, "Lyric file is missing or empty: $filePath")
            return LyricOutcome.Unsupported("bodian: file unreadable")
        }
        // 文件由宿主给出，大小不受本层控制；整块读取超大文件会耗尽宿主内存。
        // 上限复用网络路径的同一常量
        if (file.length() > LyricCacheStore.MAX_PAYLOAD_BYTES) {
            AndroidLog.logW(
                sourceName,
                "Lyric file exceeds ${LyricCacheStore.MAX_PAYLOAD_BYTES} bytes, refusing to read: $filePath",
            )
            return LyricOutcome.Unsupported("bodian: file too large")
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
                    // album 是宿主为这一份文件给出的，不属于身份，因此在这里带上
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

    private companion object {
        const val NAMESPACE = "kuwo"
    }
}
