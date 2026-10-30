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
    private val requests = com.hchen.superlyric.provider.base.SourceRequestGate()

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
     *
     * 两条路径的后果不同：
     * - 无词结论是终态，误判会把有歌词的曲目永久标记为纯音乐，因此只采纳 `SAME`；
     *   `DIFFERENT` 与 `UNVERIFIABLE` 都不采纳，后者同样说明判据不成立
     * - 歌词载荷可被下一次投递纠正，误判只影响一次显示；从严会在 id 规则不成立时
     * 丢弃全部 Apple 歌词，因此载荷路径只记录、不丢弃
     *
     * 两条路径都把判定结果写入日志，以区分未收到回调与每次都被拦截。
     *
     * @param itemId  回调携带的曲目标识，可能为 null
     * @param what    用于日志的回调类型
     * @param enforce 不匹配时是否丢弃（投递载荷时传 false）
     * @return 调用方是否可以继续采纳该回调
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
            TrackVerdict.SAME -> Unit

            // 任一侧取不到标识即无从判定。载荷仍投递（判错可自愈），
            // 但无词结论不能采纳：它是终态，而“无从判定”说明判据不成立。
            // 该曲目因此拿不到纯音乐占位并显示为空，但不会把有歌词的曲目标为纯音乐
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
     * @param enforce 判据不成立时是否丢弃调用方（投递载荷传 false，终态结论传 true）
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
     *
     * 判等采用“相等或互相包含”：曲目标识有两个来源，本来源的 [TrackIdentity]
     * （基类 `buildIdentity` 取 `MediaMetadata.MEDIA_ID`）与锚点持有的
     * `PlaybackItem.getId()`。两者是否同一套字符串尚未在真机确认；严格判等一旦假设错误，
     * 会丢弃 Apple 的正常投递。因此只有两边都有值且互不包含时才认定对不上。
     * 极端情况下可能漏拦一次过期投递，但不会比不校验更差。
     *
     * @param itemId         回调携带的曲目标识，可能为 null
     * @param currentStableId 当前曲目的稳定标识（可能带命名空间前缀），可能为 null
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
        trackGuard(itemId, identity.stableId, "lyric payload", enforce = false)

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
            if (line.translation.isNotEmpty()) {
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
