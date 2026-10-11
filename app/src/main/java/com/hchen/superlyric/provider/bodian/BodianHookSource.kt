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
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.HookLyricSource

import java.io.File
import java.util.concurrent.Executors

/**
 * 波点音乐 Hook 取词来源：读取宿主自己的本地原生歌词文件。
 *
 * <h3>三种结果的语义</h3>
 * <ul>
 *   <li>宿主给了路径且解析成功 → [LyricOutcome.Success]；</li>
 *   <li>宿主**明确没有**给路径 → [LyricOutcome.NoLyrics]——这是宿主的确定性信号，
 *       表示这首曲目没有歌词，此时才允许下发纯音乐占位；</li>
 *   <li>给了路径但文件读不到 / 解不开 → [LyricOutcome.Unsupported]——不知道是不是没歌词，
 *       交给网络路径再试，绝不据此下发占位。</li>
 * </ul>
 *
 * <h3>格式识别</h3>
 * 使用 [LyricFormat.AUTO] 按载荷自身的结构特征识别（LRCX 有 {@code [kuwo:]} 头部、
 * JSON 有 {@code sentences} 根键、其余交给通用解析器）。
 * <b>旧实现只调用了 LRCX 解析器</b>，文件的 JSON 分支与纯 LRC 分支实际从未生效。
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
    }

    override fun cancel() {
        session = null
        pendingIdentity = null
    }

    private fun onLyricFile(filePath: String, title: String, artist: String, album: String, rid: String) {
        if (rid.isNotEmpty()) capturedRid = rid

        val activeSession = session ?: return
        val base = pendingIdentity ?: return

        val identity = base.copy(
            stableId = TrackIdentity.stableId(NAMESPACE, rid) ?: base.stableId,
            title = base.title.ifBlank { title },
            artist = base.artist.ifBlank { artist },
        )
        pendingIdentity = identity

        if (filePath.isEmpty()) {
            AndroidLog.logD(sourceName, "Host reported no lyric file for this track")
            activeSession.deliver(LyricOutcome.NoLyrics("bodian: host has no lyric"))
            return
        }
        if (filePath == lastProcessedPath) return
        lastProcessedPath = filePath

        executor.execute {
            val outcome = parseFile(filePath, identity, album)
            activeSession.deliver(outcome)
        }
    }

    private fun parseFile(filePath: String, identity: TrackIdentity, album: String): LyricOutcome {
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
            LyricPipeline.parse(
                LyricRequest(
                    format = LyricFormat.AUTO,
                    text = text,
                    title = identity.title,
                    artist = identity.artist,
                    album = album,
                    durationMs = identity.durationMs,
                    lyricId = identity.stableId,
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
