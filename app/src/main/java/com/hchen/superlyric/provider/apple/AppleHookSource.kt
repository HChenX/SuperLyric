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
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.utils.LyricDiag

/**
 * Apple Music Hook 取词来源
 *
 * 宿主文本带有 HTML 标记（`<br>` / 实体转义），在此统一净化：剥离标签、还原实体、
 * 清理零宽字符。Apple 不使用协议报文，因此没有解析层，只做模型映射。
 *
 * @author 焕晨HChen
 */
class AppleHookSource : HookLyricSource {

    override val sourceName: String = "AppleHook"

    /** session 与 identity 作为一个快照发布，避免回调读到跨代际组合 */
    private val requests = SourceRequestGate()

    override fun install() {
        AppleAnchors.install(object : AppleAnchors.LyricsListener {
            override fun onLyrics(lines: MutableList<AppleLine>, itemId: String?) {
                onExtracted(lines, itemId)
            }

            override fun onNoLyrics(itemId: String?) {
                onNoLyricVerdict(itemId)
            }
        })
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.open(identity, session)
    }

    override fun cancel() {
        requests.invalidate()
    }

    /**
     * 宿主明确表示某一首没有歌词
     *
     * 采纳前必须确认它说的是当前曲目，见 [trackGuard]。
     */
    private fun onNoLyricVerdict(itemId: String?) {
        val current = requests.current() ?: return
        if (!trackGuard(itemId, current.identity.stableId, "no-lyric verdict", enforce = true)) return

        current.session.deliver(LyricOutcome.NoLyrics("apple: host reports no lyric"))
    }

    /**
     * 判定回调是否属于当前曲目，并按 [enforce] 决定不匹配时的后果
     *
     * [AppleAnchors] 的取词有 350ms 去抖，`requestLyrics(item)` 可能在切歌后完成，
     * 回调携带的 itemId 可能属于上一首。必须用当前请求身份校验，否则过期歌词会挂到新曲目，
     * 无词结论还会错误下发纯音乐占位。
     */
    private fun trackGuard(
        itemId: String?,
        stableId: String?,
        what: String,
        enforce: Boolean,
    ): Boolean {
        val verdict = judgeTrack(itemId, stableId)
        val admit = admits(verdict, enforce)
        val reported = TrackIdentity.normalizeStableId(itemId)
        val current = TrackIdentity.normalizeStableId(stableId)

        when (verdict) {
            TrackVerdict.SAME -> AndroidLog.logD(sourceName, "Accepting $what for $reported")

            TrackVerdict.UNVERIFIABLE -> AndroidLog.logW(
                sourceName,
                "$what has no comparable track id (reported=$reported, current=$current); " +
                        if (admit) "delivering anyway" else "not adopting the verdict",
            )

            TrackVerdict.DIFFERENT -> AndroidLog.logW(
                sourceName,
                if (admit) {
                    "$what is for $reported but the current track is $current; delivering anyway " +
                            "(the two id schemes are unverified — see judgeTrack)"
                } else {
                    "Dropping $what for $reported: the current track is $current"
                },
            )
        }

        return admit
    }

    /**
     * [trackGuard] 的纯判定部分
     *
     * 仅 `SAME` 放行；`UNVERIFIABLE` 与 `DIFFERENT` 只在未启用 [enforce] 时放行。
     * 该规则覆盖六种判定组合，单测固定这些边界。
     *
     * @param enforce 判据不成立时是否拒绝投递
     */
    internal fun admits(verdict: TrackVerdict, enforce: Boolean): Boolean = when (verdict) {
        TrackVerdict.SAME -> true
        TrackVerdict.UNVERIFIABLE, TrackVerdict.DIFFERENT -> !enforce
    }

    /** [judgeTrack] 的三种结论 */
    internal enum class TrackVerdict { SAME, DIFFERENT, UNVERIFIABLE }

    /**
     * 判定回调曲目与当前曲目是否同一首（纯函数，便于单测）
     *
     * 同样受 350ms 去抖影响，itemId 可能属于上一首；调用方必须校验，
     * 否则过期歌词会挂到新曲目。
     */
    internal fun judgeTrack(itemId: String?, currentStableId: String?): TrackVerdict {
        val reported = TrackIdentity.normalizeStableId(itemId)
        val current = TrackIdentity.normalizeStableId(currentStableId)
        if (reported == null || current == null) return TrackVerdict.UNVERIFIABLE

        val same = reported == current || reported.contains(current) || current.contains(reported)
        return if (same) TrackVerdict.SAME else TrackVerdict.DIFFERENT
    }

    private fun onExtracted(lines: List<AppleLine>, itemId: String?) {
        val current = requests.current() ?: return
        val identity = current.identity
        if (!trackGuard(itemId, identity.stableId, "lyric payload", enforce = true)) return

        // 宿主原始模型（含 HTML 标记）；Apple 没有协议报文，这就是未解析数据
        LyricDiag.dumpPayload(sourceName, "RAW-HOST-MODEL", dumpHostLines(lines))

        val mapped = lines.mapNotNull { line ->
            val text = cleanHtml(line.text)
            if (text.isEmpty()) return@mapNotNull null

            LyricLine(
                startMs = line.startMs,
                endMs = line.endMs,
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
            current.session.deliver(LyricOutcome.Unsupported("apple: no usable line"))
            return
        }

        val doc = LyricDoc(
            lines = mapped,
            title = identity.title,
            artist = identity.artist,
            durationMs = identity.durationMs,
            lyricId = identity.stableId,
        )
        LyricDiag.dumpDoc(sourceName, doc)

        AndroidLog.logI(sourceName, "Extracted ${mapped.size} lines from host model")
        current.session.deliver(LyricOutcome.Success(doc))
    }

    private fun dumpHostLines(lines: List<AppleLine>): String {
        val builder = StringBuilder(64 * lines.size)
        builder.append("hostLines=").append(lines.size).append('\n')
        lines.forEachIndexed { index, line ->
            builder.append('[').append(index).append("] ")
                .append(line.startMs).append('-').append(line.endMs)
                .append(" text=\"").append(line.text).append('"')
            if (!line.translation.isNullOrEmpty()) {
                builder.append(" trans=\"").append(line.translation).append('"')
            }
            if (line.words.isNotEmpty()) {
                builder.append(" words=").append(line.words.size)
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    /**
     * 剥离 HTML 标记、还原实体，再清理零宽字符
     *
     * Apple 没有协议报文，只从宿主模型取文本；这段净化是唯一可被单测覆盖的逻辑
     * （`internal` 仅为测试可见性）。
     */
    internal fun cleanHtml(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""

        // `<br>` 必须先替换为空格再剥离其它标签：直接删除会把 `first<br>second`
        // 变成 `firstsecond`，丢失分词边界。
        val withBreaks = HTML_BREAK.replace(raw, " ")
        val withoutTags = HTML_TAG.replace(withBreaks, "")
        return LyricText.cleanInvisibleChars(LyricText.decodeEntities(withoutTags)).trim()
    }

    private companion object {
        val HTML_TAG = Regex("<[^>]*>")

        /** `<br>` / `<br/>` / `<br />`，大小写不敏感 */
        val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    }
}
