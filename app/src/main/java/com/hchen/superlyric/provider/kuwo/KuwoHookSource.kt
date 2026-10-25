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
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.HookLyricSource
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.Executors

/**
 * 酷我音乐 Hook 取词来源：解析宿主交给自己渲染层的**明文歌词全文**。
 *
 * ## 取词流程
 * 宿主持有明文全文只有一个瞬间：把它交给歌词引擎之前。锚点就挂在那一个瞬间
 * （见 [KuwoAnchors]），拿到的是未经宿主行/词模型裁切的原始 LRCX，
 * 因此逐字时戳不会出现 0ms 坍缩。
 *
 * ## 进度
 * 本模块不发布进度（L3）。宿主的播放位置由系统框架侧时钟推导，
 * 而歌词是**全量**的，因此逐字高亮与行切换天然跟随播放进度。
 *
 * ## 结果的语义
 * - 拿到明文且解析成功 → [LyricOutcome.Success]
 * - 明文像歌词（带 LRC 时间标签）却解析不出任何行 → [LyricOutcome.Unsupported]：
 *   这是"我们读不懂"，不是"没有歌词"，不能下发纯音乐占位
 * - 明文不像歌词（太短，或完全没有时间标签）→ 直接忽略：它不构成对曲目的任何判断
 * - 从未拿到明文 → 不投递，由编排器超时后转其它路径
 *
 * @author 焕晨HChen
 */
class KuwoHookSource : HookLyricSource {

    override val sourceName: String = "KuwoHook"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SuperLyric-KuwoHook").apply { isDaemon = true }
    }

    private val requests = SourceRequestGate()

    /**
     * 最近一次由宿主上报的 rid
     *
     * 两处消费：Provider 经 [currentStableId] 用它构建一致的音轨身份；
     * 歌词回调用它把正文与**宿主的曲目**而不是 MediaSession 的身份绑定。
     */
    @Volatile
    private var capturedRid: String? = null

    /**
     * 按 rid 索引的宿主真实曲目信息（访问序 LRU）
     *
     * 酷我把当前歌词行写进 {@code MediaMetadata.METADATA_KEY_TITLE}，元数据不可信；
     * 而宿主自己的 {@code Music} 对象带着干净的曲名与歌手，且其 {@code rid} 与
     * 歌词回调同一时序到达，可精确关联。
     *
     * **不能"满了整表清空"**：那会把当前曲目的标题一起抹掉，Provider 侧的身份随即从
     * `kuwo:<rid>#<标题>` 退化成 `kuwo:<rid>`，编排器把这次退化当成换歌重新取词；
     * 而宿主通常不会重新解析，歌词就此中断。访问序保证正在播的那一条每次构造身份都被读到，
     * 永远在最热的一端，被淘汰的只会是预取过、还没轮到的曲目。
     */
    private val trackStore = object : LinkedHashMap<String, KuwoTrack>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KuwoTrack>): Boolean =
            size > MAX_CACHED_TRACKS
    }

    /**
     * 已解析、尚未被会话消费的结果，按 rid 暂存。宿主先给歌词后发元数据，见 [start] 的补投。
     *
     * 三个字段必须一起读、一起写，所以合成一个不可变快照：分成三个 `@Volatile` 字段时，
     * [start] 可能读到新正文配旧标识（或反之），补投判定就会拿错身份。
     *
     * **必须是多槽而不是单槽**：宿主会预取播放队列里后面几首的歌词 —— 实测一次启动后的
     * 6 秒内解析了 76 / 42 / 63 / 76 行四份正文，分属四首不同的曲目。单槽只会留下最后
     * 一份，先播的那一首真正切过去时，它的正文已被后一份顶掉，只能等宿主重新解析 ——
     * 而宿主不会，表现为切歌后整首没有歌词。按 rid 各存一份，切到哪首补投哪首。
     *
     * 访问一律走 [stashByRid] 的锁：写入在解析线程，补投在读会话建立线程。
     */
    private val stashByRid = LinkedHashMap<String, Stashed>(8, 0.75f, true)

    /**
     * 最近一次**成功**交付的（标识, 正文）
     *
     * 宿主会重复回调同一份歌词（实测同一 rid 在 70ms 内完整回调两次），用它挡掉
     * 重复触发：重复解析只会白烧 CPU 并让日志刷屏，重复投递也会让下游重新发布同一份。
     *
     * **只在解析成功之后才写入**。若在解析之前就写，一次解析失败（线程池被占、
     * 宿主这次给的是残包）会让同一份正文永远命中去重分支而再也进不来 ——
     * 而宿主随后可能不再回调，这一首就整首没有歌词。
     */
    @Volatile
    private var lastDelivered: Pair<String?, String>? = null

    /**
     * 最近一次**交给解析器**的（标识, 正文）
     *
     * 只为挡住同一份正文的并发重复入队（回调是连发的），解析失败时会被清空，
     * 让下一次回调有权重新尝试。
     */
    @Volatile
    private var lastAttempted: Pair<String?, String>? = null

    override fun install() {
        // 曲目回调用对象表达式而不是 lambda：它是三参接口，lambda 形式在 Kotlin 里
        // 无法按位置推断参数名，可读性差
        KuwoAnchors.installTrackProbe(object : KuwoAnchors.TrackListener {
            override fun onTrack(rid: String, title: String, artist: String) {
                handleTrack(rid, title, artist)
            }
        })

        KuwoAnchors.install { text, rid ->
            handleLyricText(text, rid)
        }
    }

    /** 记录宿主上报的当前曲目 */
    private fun handleTrack(rid: String, title: String, artist: String) {
        if (rid.isEmpty()) return
        // 必须在此写入：歌词回调要拿它构造与宿主一致的稳定标识。缺了它，
        // 歌词侧只能回退到 MediaSession 的身份，而切歌瞬间两者会短暂错配，
        // 表现为同一份歌词配上了另一个 rid
        capturedRid = rid
        // 容量由 removeEldestEntry 按访问序淘汰最旧的一条，见 trackStore
        synchronized(trackStore) { trackStore[rid] = KuwoTrack(rid, title, artist) }
    }

    override fun currentStableId(): String? = capturedRid

    /** 取回指定 rid 的宿主曲名；未知时返回空串 */
    fun trackTitle(rid: String): String = synchronized(trackStore) { trackStore[rid]?.title }.orEmpty()

    /** 取回指定 rid 的宿主歌手；未知时返回空串 */
    fun trackArtist(rid: String): String = synchronized(trackStore) { trackStore[rid]?.artist }.orEmpty()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        // 首要补救时机：切歌那一刻宿主已跑起来、dex 必然加载完毕，是首轮定位失败后
        // 最自然的第二次机会。内部已限流封顶，重复调用不会白烧 DexKit 检索
        KuwoAnchors.retryInstallIfNeeded()
        requests.open(identity, session)
        // 补投：宿主的歌词回调可能早于元数据更新到达，成果必须还给刚刚建立的新会话。
        //
        // 按**完整标识**取槽，不做 rid 级模糊匹配：暂存槽是按 rid + 标题归的，而同专辑
        // 各集共用一个 rid。模糊匹配在有声书队列里会把上一集的正文补投给新一集，
        // 那正是身份带标题要消除的错配；宁可这一次不等补投，等宿主重新解析。
        //
        // 判定收在 [canReplay] 里：有标识走 [sameTrack]，没有标识时才看"解析那一刻是否
        // 有会话"，并用歌词自带的标题再核一次。
        val key = stashKey(identity.stableId)
        val stashed = synchronized(stashByRid) { stashByRid[key] }
        if (stashed != null && canReplay(stashed, identity)) {
            // 只取走刚刚读到的那一份：解析线程可能在两步之间把同一 rid 换成了新正文
            synchronized(stashByRid) { if (stashByRid[key] === stashed) stashByRid.remove(key) }
            val rid = TrackIdentity.normalizeStableId(identity.stableId).orEmpty()
            AndroidLog.logD(sourceName, "Replaying stashed lyric for id=${identity.stableId}")
            session.deliver(
                LyricOutcome.Success(
                    stashed.doc.withMetadata(identity, trackTitle(rid), trackArtist(rid))
                )
            )
        }
    }

    override fun cancel() {
        requests.invalidate()
        synchronized(stashByRid) { stashByRid.clear() }
        lastDelivered = null
        lastAttempted = null
        // 一并清掉：残留的旧 rid 会让下一首的歌词回调先被配上上一首的标识
        capturedRid = null
    }

    /**
     * @param rid 这份歌词所属曲目的 rid，由锚点在取词入口处一并转交。
     *           切歌时宿主先翻 rid 再送歌词，用回调自带的 rid 才不会把上一首的
     *           正文配上新标识
     */
    private fun handleLyricText(text: String, rid: String) {
        // 与 Provider 侧同一套组合规则：rid + 标题。酷我的 rid 在有声书上是专辑级 ID，
        // 同一部书各集共用，只用 rid 会让新一集被当成同一首而丢弃
        val entryIdentity = requests.current()?.identity
        val stableId = stableIdOf(rid) ?: entryIdentity?.stableId
        // 已经成功交付过的同一份正文才跳过。判定放在这里只是快速路径，
        // 真正的写入在解析成功之后（见下方 lastDelivered = ...）。
        val delivered = lastDelivered
        if (delivered != null && stableId == delivered.first && text == delivered.second) {
            return
        }
        // 同一份正文正在解析队列里时不必重复入队：宿主连发两次回调时，
        // 第二次进来 parsed 还没写回 lastDelivered，光靠上面那条挡不住。
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
                // 解析没成功，必须放行下一次回调：lastAttempted 只为挡并发重复入队，
                // 不能连"换个时机再试一次"也一起挡掉。留着不清，宿主再送同一份正文时
                // 会被上面的快速路径直接 return，这一首就再也没有第二次机会。
                lastAttempted = null
                // 先按长度分流，再决定要不要判 Unsupported。
                //
                // 短载荷不是"读不懂"而是"这不是歌词全文"：宿主的辅助方法也会带单行文本经过
                // 解析入口，长度还不及一行完整歌词。这种输入判 Unsupported 会把整个
                // hook 来源钉成"服务不了"，让本可成功的取词跟着一起失败。
                //
                // 反之，载荷足够长却仍解不出行，确实是"我们读不懂"，此时才判 Unsupported：
                // NoLyrics 允许下发纯音乐占位，把有歌词的曲目误判成没有更糟。
                if (text.length < MIN_LYRIC_TEXT_LENGTH) {
                    AndroidLog.logD(
                        sourceName,
                        "Ignoring short lyric payload (len=${text.length}); not a full lyric",
                    )
                    return@execute
                }
                // 长但完全没有 LRC 时间标签的载荷，同样不是"我们读不懂"，而是"这不是歌词"。
                // 解析入口一旦多挂了一个锚点，别的长文本（路径、JSON、搜索结果）也会经过这里；
                // 把它们判成 Unsupported 会把整首钉死，而它本来只是走错了锚点。
                if (!LYRIC_TIME_TAG.containsMatchIn(text)) {
                    AndroidLog.logD(
                        sourceName,
                        "Ignoring payload without LRC time tags (len=${text.length})",
                    )
                    return@execute
                }
                AndroidLog.logW(sourceName, "Parsed no lines from lyric text (len=${text.length})")
                requests.current()?.session?.deliver(
                    LyricOutcome.Unsupported("kuwo: parsed empty")
                )
                return@execute
            }

            stashPut(stashKey(stableId), Stashed(parsed, stableId, requests.current() == null))
            // 解析成功才写 lastDelivered：这份正文确实能出歌词，才值得挡重复回调。
            // 若在解析之前就写，一次失败（线程池被占、宿主这次给的是残包）会让同一份
            // 正文永远命中去重分支而再也进不来，而宿主随后可能不再回调。
            lastDelivered = stableId to text
            lastAttempted = null
            AndroidLog.logI(sourceName, "Parsed lyric text, lines=${parsed.lines.size}")

            // 只有当这份结果确实属于当前会话时才直接投递；否则留给 start() 补投。
            // 绝不能投给一个属于别的曲目的会话——那会被当成新曲目的歌词发布出去。
            val activeAtDelivery = requests.current()
            val target = activeAtDelivery?.session
            val current = activeAtDelivery?.identity
            // 同样走 sameTrack：解析期间若宿主标题才到位，stableId 会从 `kuwo:<rid>`
            // 变成 `kuwo:<rid>#<标题>`，用严格相等会判成"不是当前曲目"而白白转进 stash。
            //
            // stableId 为空（车机版的 Music 字段全被混淆，读不到 rid，只能退到元数据身份，
            // 而元数据也可能没有 mediaId）时改用**身份是否自回调入口以来变过**来判定：
            // 解析是异步的，这期间宿主可能已经切歌，此时投递就是把上一首的正文
            // 配上新曲目的标识。TrackIdentity 是 data class，结构相等即"同一首"。
            val sameSession = if (stableId != null) {
                sameTrack(stableId, current?.stableId)
            } else {
                entryIdentity == current
            }
            if (target != null && sameSession) {
                target.deliver(
                    LyricOutcome.Success(
                        parsed.withMetadata(current, trackTitle(rid), trackArtist(rid))
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
     * 暂存结果能否还给这次会话
     *
     * 有标识时按 [sameTrack] 判（两边表示法可能不同，见 [start] 的注释）。
     *
     * 没有标识时只在"解析那一刻没有任何会话"时放行。车机版的 {@code Music} 字段全被
     * 混淆、读不到 {@code rid}，而宿主在进程刚起来时就把当前曲目的歌词解析完了，
     * 那一刻还没有任何会话，标识只能是 null —— 这类歌词只可能属于接下来建立的会话
     * （切歌时旧会话仍在，走的是另一条路径），不放行就等于车机版整首没有歌词。
     *
     * 放行前再用歌词自带的 {@code [ti:]}/{@code [ar:]} 核一次：两边标题都拿得到却不一致，
     * 就说明这份歌词属于别的曲目，宁可丢弃也不发布错误内容。歌词没带标题时无从核对，
     * 只能依赖"没有会话"这一条 —— 那种输入本来就少见（酷我的 LRCX 必带 [ti:]）。
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
     * 写入一个暂存槽，超出上限时淘汰最久未访问的一份
     *
     * 上限存在的原因：队列预取没有边界，长时间播放会把每一首的正文都留在内存里。
     * 按访问顺序淘汰，正在等待补投的那几首总在最近访问的一端。
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
     * 暂存槽的键：稳定标识去掉命名空间后的全文（含标题段）
     *
     * **键里必须保留标题段**：酷我的 rid 在有声书上是专辑级 ID，同一部书各集共用一个；
     * 只按 rid 归槽会让「第 489 集」的正文顶掉「第 481 集」，换集时补投出去的还是上一集。
     * 读不到 rid（车机版）时退化成空串，等价于单槽 —— 那种输入本来也无法区分曲目。
     */
    private fun stashKey(stableId: String?): String =
        TrackIdentity.normalizeStableId(stableId).orEmpty()

    /**
     * 判断两个稳定标识是否指向同一曲目
     *
     * 完全相等即同一首；除此之外只放宽"一方还没有标题段"这一种写法差异：
     * `kuwo:<rid>` 与 `kuwo:<rid>#<标题>` 表示的是同一首（区别只在拼标识时宿主标题
     * 是否已就绪）。
     *
     * **两侧都带标题且不同时不是同一首**：酷我的 rid 在有声书上是专辑级 ID，标题才是
     * 区分集的依据。按 rid 放宽会把上一集的正文配给新一集。
     *
     * **任何一侧为空都返回 false** —— 两侧都为空时相等，会把上一首的歌词
     * 当成本曲目的结果发布出去。
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
     * Hook 上报的结果会被当成过期数据丢弃。标题取不到时退化为只用 rid。
     */
    private fun stableIdOf(rid: String): String? {
        if (rid.isEmpty()) return null
        val title = trackTitle(rid).trim()
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

    /** 宿主曲目的最小信息集 */
    private data class KuwoTrack(
        val rid: String,
        val title: String,
        val artist: String,
    )

    private companion object {
        const val NAMESPACE = "kuwo"

        /** 宿主真实曲目信息的 LRU 上限；被淘汰的只会是预取过、还没轮到的曲目 */
        const val MAX_CACHED_TRACKS = 32

        /** 预取歌词的暂存槽上限：覆盖队列里预取的几首即可，超出按访问顺序淘汰最旧 */
        const val MAX_STASHED_TRACKS = 8

        /**
         * 可视为"歌词全文"的最小长度
         *
         * LRCX 全文至少带密钥头、若干 LRC 标签与一行歌词，实际上不会低于数百字节；
         * 而宿主辅助方法带过解析入口的单行文本通常只有数十字符。取 200 以稳定分开两者。
         */
        const val MIN_LYRIC_TEXT_LENGTH = 200

        /**
         * LRC / LRCX 的时间标签（`[mm:ss]` 或 `[mm:ss.SSS]`）
         *
         * 任何一份真正的歌词全文都带它；用它把"读不懂歌词"和"这压根不是歌词"分开。
         */
        val LYRIC_TIME_TAG = Regex("""\[\d{1,3}:\d{1,2}""")
    }
}
