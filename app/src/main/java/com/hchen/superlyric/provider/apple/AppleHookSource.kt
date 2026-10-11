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
 * 宿主给出的文本是**带 HTML 标记**的（`<i>` / `<br>` / 实体转义），在这里统一净化：
 * 剥标签、还原实体、剥离零宽字符。Apple 不使用协议报文，因此这里没有解析层，只有模型映射
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
            override fun onLyrics(lines: MutableList<AppleLine>, itemId: String?) {
                onExtracted(lines, itemId)
            }

            override fun onNoLyrics(itemId: String?) {
                onNoLyricVerdict(itemId)
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

    /**
     * 宿主明确表示某一首没有歌词
     *
     * 采纳前必须确认它说的就是**当前**这一首——见 [trackGuard]
     */
    private fun onNoLyricVerdict(itemId: String?) {
        val activeSession = session ?: return
        if (pendingIdentity == null) return
        if (!trackGuard(itemId, "no-lyric verdict", enforce = true)) return

        activeSession.deliver(LyricOutcome.NoLyrics("apple: host reports no lyric"))
    }

    /**
     * 判定这个回调说的是不是**当前**这一首，并按 [enforce] 决定对不上时的后果
     *
     * ## 为什么需要
     * [AppleAnchors] 的取词有 350ms 去抖，因此 `requestLyrics(item)` 可能在**切歌之后**
     * 才跑完：那时 [session] 与 [pendingIdentity] 已经换成新曲目，而回调说的是上一首。
     * 歌词挂错会显示别的歌的词，无词结论更狠——它允许下发纯音乐占位，
     * 把一首**有歌词**的曲目永久钉死。这正是 `bc14d49` 给波点修掉的那一类。
     *
     * ## 为什么两条路的后果不同
     * 判等依赖"两套 id 可比"这个**未在真机确认**的前提（见 [judgeTrack]）。
     * - 无词结论是**终态**：判错的代价是把有歌词的曲目永久钉成纯音乐，因此只采纳
     *   **确认同一首**（`SAME`）这一种。"确定对不上"与"无从判定"都不采纳——后者同样
     *   说明这条判据不成立，而它一旦放行就等于押注在一个未经确认的前提上
     * - 歌词载荷是**可自愈**的：判错只是显示了一首歌的词，下一次投递就纠正了；
     *   而从严的代价是——一旦前提不成立，Apple 的歌词会一条都投不出去。两害相权，
     *   载荷路径只留痕、不丢弃
     *
     * 两条路都把判定结果打进日志：否则"从未收到回调"与"收到但每次都被拦下"长得一模一样
     *
     * @param itemId  回调携带的曲目标识，可能为 null
     * @param what    用于日志的回调类型
     * @param enforce 对不上时是否丢弃（投递载荷时传 false）
     * @return 调用方是否可以继续采纳这次回调
     */
    private fun trackGuard(itemId: String?, what: String, enforce: Boolean): Boolean {
        val stableId = pendingIdentity?.stableId
        val verdict = judgeTrack(itemId, stableId)
        val admit = admits(verdict, enforce)
        val reported = TrackIdentity.normalizeStableId(itemId)
        val current = TrackIdentity.normalizeStableId(stableId)

        when (verdict) {
            TrackVerdict.SAME -> Unit

            // 两边有一边取不到标识 ⇒ 无从判定。**载荷**照旧投递（判错可自愈），
            // 但**无词结论不能采纳**：它是终态，而"无从判定"恰恰说明这条判据不成立。
            // 代价是这首曲目拿不到纯音乐占位（显示为空），比"有歌词却被永久钉死"可接受
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
     * [trackGuard] 的**纯判定**部分
     *
     * 六格里只有一格放行——这条规则写错过两次，所以每一格都要有测试钉住：
     * 先是两条路都不拦（过期结论投给了新曲目），后是 `enforce` 形同虚设（注释说从严、代码照放行）。
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
     * 判定一个回调说的曲目与当前曲目是否同一首（纯函数，便于单测）
     *
     * ## 为什么需要
     * [AppleAnchors] 的取词有 350ms 去抖，因此 `requestLyrics(item)` 可能在**切歌之后**
     * 才跑完：那时 [session] 与 [pendingIdentity] 已经换成新曲目，而回调说的是上一首。
     * 歌词挂错会显示别的歌的词；无词结论更狠——它允许下发纯音乐占位，
     * 把一首**有歌词**的曲目永久钉死。这正是 `bc14d49` 给波点修掉的那一类。
     *
     * ## 判等为什么用"相等或互相包含"
     * 曲目标识在这一层有两个来源：本来源的 [TrackIdentity]（来自基类 `buildIdentity`，
     * 取 `MediaMetadata` 的 `MEDIA_ID`）与锚点手里的 `PlaybackItem.getId()`。
     * **两者是否同一套字符串并未在真机上确认**，严格判等一旦假设错，会把 Apple 的
     * 正常投递全部丢掉。因此取宽容判等：只有"两边都有值、且互不包含"才认定对不上。
     * 代价是极端情况下漏拦一次过期投递——那只是退回改动前的行为，不会更糟。
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
        val activeSession = session ?: return
        val identity = pendingIdentity ?: return
        trackGuard(itemId, "lyric payload", enforce = false)

        // 宿主原始模型（含 HTML 标记）——Apple 没有协议报文，这就是"未解析数据"
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
            activeSession.deliver(LyricOutcome.Unsupported("apple: no usable line"))
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
        activeSession.deliver(LyricOutcome.Success(doc))
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
     * 剥离 HTML 标记、还原实体、再剥离零宽字符。
     *
     * Apple 是本项目**唯一**没有协议报文、只从宿主模型取文本的 Provider，
     * 因此这段净化是它唯一可被单测覆盖的逻辑（`internal` 仅为测试可见性）。
     */
    internal fun cleanHtml(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""

        // `<br>` 必须先换成一个**空格**再剥其它标签：直接删掉会把两段文字粘在一起
        // （`first<br>second` → `firstsecond`），词组边界就丢了。
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
