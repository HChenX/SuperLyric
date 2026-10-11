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
package com.hchen.superlyric.provider.apple

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.text.LyricText
import com.hchen.superlyric.provider.apple.AppleAnchors.AppleLine
import com.hchen.superlyric.provider.base.HookLyricSource

/**
 * Apple Music Hook 取词来源。
 *
 * <p>宿主给出的文本是**带 HTML 标记**的（{@code <i>}/{@code <br>}/实体转义），
 * 在这里统一净化：剥标签、还原实体、剥离零宽字符。
 * Apple 不使用协议报文，因此这里没有解析层——只有模型映射。
 *
 * @author 焕晨HChen
 */
class AppleHookSource : HookLyricSource {

    override val sourceName: String = "AppleHook"

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var pendingIdentity: TrackIdentity? = null

    override fun install() {
        AppleAnchors.install(object : AppleAnchors.LyricsListener {
            override fun onLyrics(lines: MutableList<AppleLine>) {
                onExtracted(lines)
            }

            override fun onNoLyrics() {
                session?.deliver(LyricOutcome.NoLyrics("apple: host reports no lyric"))
            }
        })
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        this.session = session
        this.pendingIdentity = identity
    }

    override fun cancel() {
        session = null
        pendingIdentity = null
    }

    private fun onExtracted(lines: List<AppleLine>) {
        val activeSession = session ?: return
        val identity = pendingIdentity ?: return

        val mapped = lines.mapNotNull { line ->
            val text = cleanHtml(line.text)
            if (text.isEmpty()) return@mapNotNull null

            LyricLine(
                startMs = line.startMs.toLong(),
                endMs = line.endMs.toLong(),
                text = text,
                translation = cleanHtml(line.translation).takeIf { it.isNotEmpty() },
                words = line.words.mapNotNull { word ->
                    val wordText = cleanHtml(word.text)
                    if (wordText.isEmpty()) null
                    else LyricWord(wordText, word.startMs, word.endMs)
                },
            )
        }.sortedBy { it.startMs }

        if (mapped.isEmpty()) {
            activeSession.deliver(LyricOutcome.Unsupported("apple: no usable line"))
            return
        }

        AndroidLog.logI(sourceName, "Extracted ${mapped.size} lines from host model")
        activeSession.deliver(
            LyricOutcome.Success(
                LyricDoc(
                    lines = mapped,
                    title = identity.title,
                    artist = identity.artist,
                    durationMs = identity.durationMs,
                    lyricId = identity.stableId,
                )
            )
        )
    }

    /**
     * 剥离 HTML 标记、还原实体、再剥离零宽字符。
     */
    private fun cleanHtml(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val withoutTags = HTML_TAG.replace(raw, "")
        return LyricText.cleanInvisibleChars(LyricText.decodeEntities(withoutTags)).trim()
    }

    private companion object {
        val HTML_TAG = Regex("<[^>]*>")
    }
}
