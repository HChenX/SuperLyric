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
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.HookLyricSource
import com.hchen.superlyric.provider.base.KeyedStash
import com.hchen.superlyric.provider.base.SourceRequestGate
import com.hchen.superlyric.provider.base.withMetadata
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.ConcurrentHashMap
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
 * ## 三种结果的语义
 * - 拿到明文且解析成功 → [LyricOutcome.Success]
 * - 明文存在但解析不出任何行 → [LyricOutcome.Unsupported]：这是"我们读不懂"，
 *   不是"没有歌词"，不能下发纯音乐占位
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
    private val docStash = KeyedStash<com.hchen.superlyric.kernel.model.LyricDoc>()

    /**
     * 最近一次由宿主上报的 rid
     *
     * <p>两处消费：Provider 经 [currentStableId] 用它构建一致的音轨身份；
     * 歌词回调用它把正文与**宿主的曲目**而不是 MediaSession 的身份绑定。
     */
    @Volatile
    private var capturedRid: String? = null

    /**
     * 按 rid 索引的宿主真实曲目信息
     *
     * <p>酷我把当前歌词行写进 {@code MediaMetadata.METADATA_KEY_TITLE}，元数据不可信；
     * 而宿主自己的 {@code Music} 对象带着干净的曲名与歌手，且其 {@code rid} 与
     * 歌词回调同一时序到达，可精确关联。
     */
    private val trackStore = ConcurrentHashMap<String, KuwoTrack>()

    /**
     * 最近一次交给解析器的（标识, 正文）
     *
     * <p>宿主会重复回调同一份歌词（实测同一 rid 在 70ms 内完整回调两次），用它挡掉
     * 重复触发：重复解析只会白烧 CPU 并让日志刷屏，重复投递也会让下游重新发布同一份。
     */
    @Volatile
    private var lastDelivered: Pair<String?, String>? = null

    override fun install() {
        // 曲目回调用对象表达式而不是 lambda：它是三参接口，lambda 形式在 Kotlin 里
        // 无法按位置推断参数名，可读性差
        KuwoAnchors.installTrackProbe(object : KuwoAnchors.TrackListener {
            override fun onTrack(rid: String, title: String, artist: String) {
                handleTrack(rid, title, artist)
            }
        })

        KuwoAnchors.install { text ->
            handleLyricText(text)
        }
    }

    /** 记录宿主上报的当前曲目 */
    private fun handleTrack(rid: String, title: String, artist: String) {
        if (rid.isEmpty()) {
            AndroidLog.logW(sourceName, "Track probe returned an empty rid; identity falls back to metadata")
            return
        }
        // 必须在此写入：歌词回调要拿它构造与宿主一致的稳定标识。缺了它，
        // 歌词侧只能回退到 MediaSession 的身份，而切歌瞬间两者会短暂错配，
        // 表现为同一份歌词配上了另一个 rid
        capturedRid = rid
        // 只做缓存，容量到顶即整体清空——保留最近若干首足够覆盖切歌时序
        if (trackStore.size >= MAX_CACHED_TRACKS) trackStore.clear()
        trackStore[rid] = KuwoTrack(rid, title, artist)
    }

    override fun currentStableId(): String? = capturedRid

    /** 取回指定 rid 的宿主曲名；未知时返回空串 */
    fun trackTitle(rid: String): String = trackStore[rid]?.title.orEmpty()

    /** 取回指定 rid 的宿主歌手；未知时返回空串 */
    fun trackArtist(rid: String): String = trackStore[rid]?.artist.orEmpty()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        requests.open(identity, session)
        docStash.takeIf(identity.stableId)?.let { stashed ->
            val rid = TrackIdentity.normalizeStableId(identity.stableId).orEmpty()
            AndroidLog.logD(sourceName, "Replaying stashed lyric for id=${identity.stableId}")
            session.deliver(
                LyricOutcome.Success(stashed.withMetadata(identity, trackTitle(rid), trackArtist(rid)))
            )
        }
    }

    override fun cancel() {
        requests.invalidate()
        docStash.clear()
        lastDelivered = null
        // 一并清掉：残留的旧 rid 会让下一首的歌词回调先被配上上一首的标识
        capturedRid = null
    }

    private fun handleLyricText(text: String) {
        val rid = capturedRid ?: ""
        val active = requests.current()
        // 用当前已知的 rid 构造稳定标识；宿主的 rid 由 trackProbe 先一步送达
        val stableId = TrackIdentity.stableId(NAMESPACE, rid) ?: active?.identity?.stableId

        val last = lastDelivered
        if (last != null && stableId != null && stableId == last.first && text == last.second) {
            return
        }
        lastDelivered = stableId to text

        executor.execute {
            val doc = try {
                LyricDiag.parse(
                    sourceName,
                    LyricRequest(
                        format = LyricFormat.AUTO,
                        text = text,
                        // 解析可能早于切歌完成；这里只带稳定 id，元数据在投递时
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
                AndroidLog.logW(sourceName, "Parsed no lines from lyric text (len=${text.length})")
                val activeAtDelivery = requests.current()
                val target = activeAtDelivery?.session
                val current = activeAtDelivery?.identity
                if (target != null && (stableId == null || stableId == current?.stableId)) {
                    target.deliver(LyricOutcome.Unsupported("kuwo: parsed empty"))
                } else {
                    AndroidLog.logW(sourceName, "Dropping stale parse failure for ${stableId}")
                }
                return@execute
            }

            docStash.put(stableId, parsed)
            AndroidLog.logI(sourceName, "Parsed lyric text, lines=${parsed.lines.size}")

            // 只有当这份结果确实属于当前会话时才直接投递；否则留给 start() 补投。
            // 绝不能投给一个属于别的曲目的会话——那会被当成新曲目的歌词发布出去。
            val activeAtDelivery = requests.current()
            val target = activeAtDelivery?.session
            val current = activeAtDelivery?.identity
            if (target != null && (stableId == null || stableId == current?.stableId)) {
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

    /** 宿主曲目的最小信息集 */
    private data class KuwoTrack(
        val rid: String,
        val title: String,
        val artist: String,
    )

    private companion object {
        const val NAMESPACE = "kuwo"

        /** 宿主真实曲目信息的缓存上限 */
        const val MAX_CACHED_TRACKS = 32

        /**
         * 可视为"歌词全文"的最小长度
         *
         * LRCX 全文至少带密钥头、若干 LRC 标签与一行歌词，实际上不会低于数百字节；
         * 而宿主辅助方法带过解析入口的单行文本通常只有数十字符。取 200 以稳定分开两者。
         */
        const val MIN_LYRIC_TEXT_LENGTH = 200
    }
}
