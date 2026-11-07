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
package com.hchen.superlyric.provider.kuwo

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.parse.LyricPayloadHeuristics
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.InstallableLyricSource
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.shared.HostTrackMetaSource
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.Executors

/**
 * 酷我 Hook 取词：解析宿主歌词引擎前的 LRCX 明文。
 * 成功 Success；像歌词但无行 Unsupported；从未拿到明文时不投递。
 */
class KuwoHookSource(
    private val meta: HostTrackMetaSource? = null,
) : InstallableLyricSource {

    override val sourceName: String = "KuwoHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-KuwoHook").apply { isDaemon = true }
    }

    private val requests = SourceRequestGate()

    /**
     * 按 rid 暂存已解析、尚未被会话消费的结果。
     *
     * 三个字段作为不可变快照一起读写；按 rid 多槽保存，避免预取的后续曲目顶掉当前曲目。
     * 访问一律在 stashByRid 上同步。
     */
    private val stashByRid = LinkedHashMap<String, Stashed>(8, 0.75f, true)

    /**
     * 最近一次成功交付的（标识, 正文），用于跳过宿主重复回调的同一份歌词。
     */
    @Volatile
    private var lastDelivered: Pair<String?, String>? = null

    /**
     * 最近一次交给解析器的（标识, 正文），用于挡住并发重复入队。
     */
    @Volatile
    private var lastAttempted: Pair<String?, String>? = null

    override fun install() {
        KuwoAnchors.install { text, rid ->
            handleLyricText(text, rid)
        }
    }

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        // 切歌时宿主已运行，dex 必然加载完毕，是首轮定位失败后的第二次机会；
        // 内部已限流封顶，重复调用不会反复执行 DexKit 检索。DexKit 全包检索放到
        // 来源线程，start() 本身仍快速返回。
        executor.execute { KuwoAnchors.retryInstallIfNeeded() }
        requests.open(identity, session)
        // 补投：宿主的歌词回调可能早于元数据更新到达，成果必须还给刚建立的新会话。
        //
        // 按完整标识取槽，不做 rid 级模糊匹配：暂存槽按 rid + 标题归类，而同专辑各集
        // 共用一个 rid。模糊匹配会把上一集正文补投给新一集；本次不补投优于错配，
        // 等待宿主重新解析。
        //
        // 判定收在 [canReplay]：有标识走 [sameTrack]，没有标识时才看解析那一刻是否有会话，
        // 并用歌词自带标题再核一次。
        val key = stashKey(identity.stableId)
        val stashed = synchronized(stashByRid) { stashByRid[key] }
        if (stashed != null && canReplay(stashed, identity)) {
            // 只取走刚刚读到的那一份：解析线程可能在两步之间把同一 rid 换成了新正文
            synchronized(stashByRid) { if (stashByRid[key] === stashed) stashByRid.remove(key) }
            val rid = TrackIdentity.normalizeStableId(identity.stableId).orEmpty()
            AndroidLog.logD(sourceName, "Replaying stashed lyric for id=${identity.stableId}")
            session.deliver(
                LyricOutcome.Success(
                    stashed.doc.withMetadata(identity, meta?.title(rid).orEmpty(), meta?.artist(rid).orEmpty())
                )
            )
        }
    }

    override fun cancel() {
        requests.invalidate()
        synchronized(stashByRid) { stashByRid.clear() }
        lastDelivered = null
        lastAttempted = null
    }

    /**
     *            切歌时宿主先翻 rid 再送歌词，使用回调自带的 rid 才不会把上一首正文
     *            配上新标识
     */
    private fun handleLyricText(text: String, rid: String) {
        // 与 Provider 侧同一套组合规则：rid + 标题。酷我的 rid 在有声书上是专辑级 ID，
        // 同一部书各集共用；只用 rid 会让新一集被当成同一首而丢弃
        val entryIdentity = requests.current()?.identity
        val stableId = stableIdOf(rid) ?: entryIdentity?.stableId
        // 已成功交付同一份正文才跳过。此处只是快速路径，真正写入在解析成功后
        // （见下方 lastDelivered = ...）。
        val delivered = lastDelivered
        if (delivered != null && stableId == delivered.first && text == delivered.second) {
            return
        }
        // 同一份正文正在解析队列中时不必重复入队：宿主连发两次回调时，
        // 第二次进入时 parsed 尚未写回 lastDelivered，仅靠上一条无法拦住。
        val attempted = lastAttempted
        if (attempted != null && stableId == attempted.first && text == attempted.second) {
            return
        }
        lastAttempted = stableId to text

        executor.execute {
            val doc = try {
                LyricDiag.parse(
                    sourceName,
                    LyricRequest(
                        format = LyricFormat.AUTO,
                        text = text,
                        // 解析可能早于切歌完成；这里只带稳定标识，元数据在投递时
                        // 由当前请求身份回填，避免把上一首的曲名/时长写进文档。
                        lyricId = stableId,
                    )
                )
            } catch (t: Throwable) {
                AndroidLog.logW(sourceName, "Failed to parse lyric text: ${t.message}")
                null
            }
            val parsed = doc
            if (parsed == null || parsed.isEmpty) {
                // 解析失败必须放行下一次回调：lastAttempted 只挡并发重复入队，
                // 不能连换个时机重试也挡掉。保留不清时，宿主再送同一份正文会被快速路径
                // 直接 return，该曲目不再有第二次机会。
                lastAttempted = null
                // 先按长度分流，再决定是否判 Unsupported。
                //
                // 短载荷不是“读不懂”，而是“不是歌词全文”：宿主的辅助方法也会带单行文本
                // 经过解析入口，长度不及一行完整歌词。判 Unsupported 会把整个 hook 来源
                // 标成服务不了，使本可成功的取词一起失败。
                //
                // 反之，载荷足够长却仍解不出行，才说明本模块读不懂，此时判 Unsupported：
                // NoLyrics 允许下发纯音乐占位，把有歌词曲目误判为无歌词的后果更严重。
                if (LyricPayloadHeuristics.isShortText(text)) {
                    AndroidLog.logD(
                        sourceName,
                        "Ignoring short lyric payload (len=${text.length}); not a full lyric",
                    )
                    return@execute
                }
                // 长但完全没有 LRC 时间标签的载荷同样不是“读不懂”，而是“不是歌词”。
                // 解析入口一旦多挂锚点，路径、JSON、搜索结果等长文本也会经过这里；
                // 判成 Unsupported 会把整首曲目标为不可用，而它只是走错锚点。
                if (!LyricPayloadHeuristics.hasLrcTimeTag(text)) {
                    AndroidLog.logD(
                        sourceName,
                        "Ignoring payload without LRC time tags (len=${text.length})",
                    )
                    return@execute
                }
                AndroidLog.logW(sourceName, "Parsed no lines from lyric text (len=${text.length})")
                // 终态结论同样必须属于当前曲目：切歌后迟到的旧载荷不能把新曲目锁成
                // UNAVAILABLE（Kuwo 没有网络源可回退）。
                val unsupportedTarget = requests.current()
                val unsupportedSame = if (stableId != null) {
                    sameTrack(stableId, unsupportedTarget?.identity?.stableId)
                } else {
                    entryIdentity == unsupportedTarget?.identity
                }
                if (unsupportedTarget != null && unsupportedSame) {
                    unsupportedTarget.session.deliver(
                        LyricOutcome.Unsupported("kuwo: parsed empty")
                    )
                } else {
                    AndroidLog.logD(sourceName, "Dropped empty-lyric verdict for a stale track")
                }
                return@execute
            }

            stashPut(stashKey(stableId), Stashed(parsed, stableId, requests.current() == null))
            // 解析成功才写 lastDelivered：这份正文确实能出歌词，才值得挡重复回调。
            // 若在解析前写，一次失败（线程池被占、宿主给的是残包）会让同一份正文持续
            // 命中去重分支，而宿主随后可能不再回调。
            lastDelivered = stableId to text
            lastAttempted = null
            AndroidLog.logI(sourceName, "Parsed lyric text, lines=${parsed.lines.size}")

            // 只有结果属于当前会话时才直接投递；否则留给 start() 补投。
            // 不得投给属于其它曲目的会话，那会被当作新曲目歌词发布。
            val activeAtDelivery = requests.current()
            val target = activeAtDelivery?.session
            val current = activeAtDelivery?.identity
            // 同样走 sameTrack：解析期间宿主标题才到位时，stableId 会从 `kuwo:<rid>`
            // 变成 `kuwo:<rid>#<标题>`；严格相等会判成不是当前曲目，结果白白转入 stash。
            //
            // stableId 为空（车机版 Music 字段全被混淆，读不到 rid，只能退回元数据身份，
            // 而元数据也可能没有 mediaId）时，用身份自回调入口以来是否变化判定：
            // 解析异步进行，期间宿主可能已切歌，此时投递会把上一首正文配上新曲目标识。
            // TrackIdentity 是 data class，结构相等即同一首。
            val sameSession = if (stableId != null) {
                sameTrack(stableId, current?.stableId)
            } else {
                entryIdentity == current
            }
            if (target != null && sameSession) {
                target.deliver(
                    LyricOutcome.Success(
                        parsed.withMetadata(current, meta?.title(rid).orEmpty(), meta?.artist(rid).orEmpty())
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

    /**
     * 暂存结果能否还给本次会话。
     *
     * 有标识时按 sameTrack 判；无标识时只放行“解析时没有会话”的一份，再用歌词自带的
     * 标题核对一次，避免把其它曲目的正文补投给当前会话。
     */
    private fun canReplay(stashed: Stashed, identity: TrackIdentity): Boolean {
        if (stashed.id != null) return sameTrack(stashed.id, identity.stableId)
        if (!stashed.withoutSession) return false
        val docTitle = stashed.doc.title.trim()
        if (docTitle.isEmpty()) return true
        // 歌词文档里的时长是"歌词跨度"而不是曲目时长，不能参与比较（传 0 跳过）
        val docIdentity = TrackIdentity(sourceName, null, docTitle, stashed.doc.artist, 0L)
        return docIdentity.matches(identity)
    }

    /**
     * 写入暂存槽；超出上限时淘汰最久未访问的一份。
     */
    private fun stashPut(key: String, value: Stashed) {
        synchronized(stashByRid) {
            stashByRid[key] = value
            while (stashByRid.size > MAX_STASHED_TRACKS) {
                val eldest = stashByRid.entries.iterator().next().key
                stashByRid.remove(eldest)
            }
        }
    }

    /**
     * 暂存槽的键：稳定标识去掉命名空间后的全文。
     *
     * 必须保留标题段：有声书的 rid 是专辑级，只按 rid 归槽会让换集时补投上一集。
     */
    private fun stashKey(stableId: String?): String =
        TrackIdentity.normalizeStableId(stableId).orEmpty()

    /**
     * 两个稳定标识是否指向同一曲目。
     *
     * 完全相等即同一首；只放宽“一方尚无标题段”的写法差异。两侧都带标题且不同时按不同集处理。
     */
    private fun sameTrack(leftId: String?, rightId: String?): Boolean {
        val left = TrackIdentity.normalizeStableId(leftId) ?: return false
        val right = TrackIdentity.normalizeStableId(rightId) ?: return false
        if (left == right) return true
        val leftRid = left.substringBefore('#')
        if (leftRid.isEmpty() || leftRid != right.substringBefore('#')) return false
        return !left.contains('#') || !right.contains('#')
    }

    /**
     * 用 rid + 宿主标题组装稳定标识
     *
     * 必须与 Provider 侧 [KuwoBaseProvider] 的规则一致，否则两侧标识表示法不同，
     * Hook 上报结果会被当作过期数据丢弃。标题取不到时退化为只用 rid。
     */
    private fun stableIdOf(rid: String): String? {
        if (rid.isEmpty()) return null
        val title = meta?.title(rid).orEmpty().trim()
        return if (title.isEmpty()) {
            TrackIdentity.stableId(NAMESPACE, rid)
        } else {
            TrackIdentity.stableId(NAMESPACE, "$rid#$title")
        }
    }

    /**
     * 暂存的一次解析结果
     *
     * @property doc            解析出的歌词
     * @property id             这份歌词的稳定标识；宿主读不到 rid 时为 null
     * @property withoutSession 解析发生时是否还没有任何会话
     */
    private data class Stashed(
        val doc: LyricDoc,
        val id: String?,
        val withoutSession: Boolean,
    )


    private companion object {
        const val NAMESPACE = "kuwo"


        /** 预取歌词的暂存槽上限：覆盖队列预取的几首即可，超出按访问顺序淘汰最旧 */
        const val MAX_STASHED_TRACKS = 8

    }
}
