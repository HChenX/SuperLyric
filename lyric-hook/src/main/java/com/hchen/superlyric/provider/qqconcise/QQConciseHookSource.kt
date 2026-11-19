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
package com.hchen.superlyric.provider.qqconcise

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.catalog.CatalogIds
import com.hchen.superlyric.kernel.decode.DecodeException
import com.hchen.superlyric.kernel.decode.PayloadBytes
import com.hchen.superlyric.kernel.decode.QrcCipher
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.provider.base.KeyedStash
import com.hchen.superlyric.provider.base.SourceRequest
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.utils.LyricDiag
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class QQConciseHookSource(
    private val executor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SuperLyric-QQConcise").apply { isDaemon = true }
    },
) : InstallableLyricSource {

    override val sourceName = "QQConciseHook"

    private val requests = SourceRequestGate()
    private val pending = KeyedStash<QQConciseAnchors.LyricEvent>()

    override fun install() {
        QQConciseAnchors.install(::onCaptured)
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        val request = requests.open(identity, session)
        executor.execute {
            if (!requests.isCurrent(request)) return@execute
            val key = TrackIdentity.normalizeStableId(identity.stableId)
            pending.takeIf(key)?.let { publish(request, it) }
        }
    }

    override fun cancel() {
        requests.invalidate()
    }

    internal fun onCaptured(event: QQConciseAnchors.LyricEvent) {
        val song = event.song()
        val keys = listOfNotNull(CatalogIds.qqSongId(song.id().toString())?.toString(), CatalogIds.qqMid(song.mid()))
        if (keys.isEmpty()) return
        executor.execute {
            val request = requests.current()
            val key = TrackIdentity.normalizeStableId(request?.identity?.stableId)
            if (request != null && key != null && key in keys) {
                publish(request, event)
            } else {
                pending.put(keys, event)
            }
        }
    }

    private fun publish(request: SourceRequest, event: QQConciseAnchors.LyricEvent) {
        val identity = request.identity
        val doc = try {
            LyricDiag.parse(
                sourceName,
                LyricRequest(
                    format = if (event.qrc()) LyricFormat.QRC else LyricFormat.GENERAL,
                    bytes = if (event.qrc()) PayloadBytes.decode(event.main()) else null,
                    text = event.main().takeUnless { event.qrc() },
                    translationText = event.translation(),
                    phoneticText = decodePhonetic(event.phonetic()),
                    title = identity.title,
                    artist = identity.artist,
                    durationMs = identity.durationMs,
                    lyricId = identity.stableId,
                ),
            )
        } catch (error: DecodeException) {
            AndroidLog.logW(sourceName, "Host QRC could not be decoded: ${error.message}")
            null
        }
        if (!requests.isCurrent(request)) return
        request.session.deliver(
            doc?.let { LyricOutcome.Success(it) } ?: LyricOutcome.Unsupported("QQ Concise: unusable host lyric"),
        )
    }

    private fun decodePhonetic(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return try {
            QrcCipher.decrypt(PayloadBytes.decode(text))
        } catch (error: DecodeException) {
            AndroidLog.logW(sourceName, "Host phonetic lyric could not be decoded: ${error.message}")
            null
        }
    }
}
