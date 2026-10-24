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
package com.hchen.superlyric.provider.soda

import android.os.SystemClock

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.kernel.pipeline.LyricFormat
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyric.provider.base.HookLyricSource
import com.hchen.superlyric.utils.LyricDiag

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 汽水音乐 Hook 取词来源：解析宿主交给歌词模型的原始正文
 *
 * ## 宿主给出的三种格式
 * - `LRC` —— 通用 LRC，交 [LyricFormat.GENERAL]
 * - `KRC` —— 酷狗系逐字格式 `[行起始,行时长]<词偏移,词时长,0>词`，交 [LyricFormat.KRC]
 * - `TEXT` —— 纯文本、无时间轴；按内容再嗅探一次，仍然没有时间轴就不发布
 *
 * ## 只投递成功结果
 * 编排器对同一代际**只接受第一次投递**，投出一次否定结论就把这一代际关死了：
 * 宿主先给一份占位/纯文本载荷、几百毫秒后才给真正的逐字载荷，是很常见的时序。
 * 若把前者的 [LyricOutcome.Unsupported] 投出去，后者会被当成重复投递丢弃 ——
 * 表现就是"同一首歌有时有歌词、有时没有"。
 *
 * 因此本来源只投递 [LyricOutcome.Success]。读不出来就保持沉默，由编排器的 Hook 超时
 * 把状态落到 `UNAVAILABLE`（不发布任何占位），迟到的成功结果仍然会被接受
 *
 * ## 为什么译文要等一小会儿
 * 主歌词与译文是**两份独立载荷**（宿主按语言各构造一个歌词实体），到达时间不固定。
 * 编排器对同一代际只接受第一次投递，因此先到就投会把译文整份丢掉。这里给译文一个
 * [TRANSLATION_GRACE_MS] 的窗口，窗口内没等到就按主歌词单独发布 —— 宁可少一份译文，
 * 也不能为了译文把发布拖到 Hook 等待窗口之外。只有译文、没有主歌词时多等
 * [MAIN_WAIT_MS]：译文本身也是带时间轴的，实在等不到主歌词才拿它当正文。
 *
 * @author 焕晨HChen
 */
class SodaHookSource : HookLyricSource {

    override val sourceName: String = "SodaHook"

    /** 一份已截获的载荷 */
    private class Captured(
        val type: String,
        val language: String,
        val payload: String,
        val trackId: String?,
        val atMs: Long,
    )

    private val lock = Any()

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SuperLyric-SodaHook").apply { isDaemon = true }
        }

    @Volatile
    private var session: LyricSource.Session? = null

    @Volatile
    private var identity: TrackIdentity? = null

    /** 最近一次截获到的曲目标识；供 `buildIdentity` 在元数据缺标识时兜底 */
    @Volatile
    private var capturedTrackId: String? = null

    private var pendingMain: Captured? = null
    private var pendingTranslation: Captured? = null

    /** 待投递的主载荷其标识是否与当前身份相等（更强的证据） */
    private var pendingMatched: Boolean = false

    /** 最近一次截获的载荷；歌词常先于元数据到达，`start()` 时据此补投 */
    private var lastCaptured: Captured? = null

    /**
     * 按曲目标识暂存的载荷
     *
     * 宿主会**预加载下一首的歌词**：切歌前就把它解析完，切过去之后不再重新解析。
     * 只靠 [lastCaptured] 一个槽位的话，预加载的那份会被判成"上一首的"而丢弃，
     * 切过去以后就再也等不到第二份 —— 表现为切歌后没歌词。
     */
    private val stash = object : LinkedHashMap<String, Stashed>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stashed>?): Boolean =
            size > MAX_STASH
    }

    /** 一首曲目的主歌词与译文 */
    private class Stashed {
        var main: Captured? = null
        var translation: Captured? = null
    }

    private var scheduled: ScheduledFuture<*>? = null

    private var deliveredKey: String? = null

    override fun install() {
        SodaAnchors.install { type, language, payload, trackId ->
            onPayload(type, language, payload, trackId)
        }
    }

    override fun currentStableId(): String? = capturedTrackId

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        this.session = session
        this.identity = identity
        deliveredKey = null

        synchronized(lock) {
            pendingMain = null
            pendingTranslation = null
            pendingMatched = false

            val key = usableId(identity.stableId)
            val stashed = key?.let { stash[it] }
            if (stashed != null && (stashed.main != null || stashed.translation != null)) {
                AndroidLog.logD(sourceName, "Replaying stashed payload for ${identity.stableId}")
                stashed.main?.let { accept(it, true) }
                stashed.translation?.let { accept(it, true) }
                return
            }

            val cached = lastCaptured ?: return
            // 载荷先于元数据到达是常态；只补投"刚刚截获"的那份，避免把上一首的歌词
            // 当成本曲目的结果发出去
            if (SystemClock.elapsedRealtime() - cached.atMs > STASH_WINDOW_MS) return
            if (!matchesCurrent(cached.trackId)) return

            AndroidLog.logD(sourceName, "Replaying recent payload for ${identity.stableId}")
            // 补投过一次就作废：同一份载荷在下一首的会话里再被取用就是错歌
            lastCaptured = null
            accept(cached, true)
        }
    }

    override fun cancel() {
        synchronized(lock) {
            session = null
            identity = null
            pendingMain = null
            pendingTranslation = null
            lastCaptured = null
            scheduled?.cancel(false)
            scheduled = null
        }
    }

    private fun onPayload(type: String, language: String, payload: String, trackId: String?) {
        if (trackId != null) capturedTrackId = trackId

        synchronized(lock) {
            val captured = Captured(type, language, payload, trackId, SystemClock.elapsedRealtime())

            // 无论是不是当前曲目，先按标识入栈：预加载的下一首切过去时要用它补投
            usableId(trackId)?.let { id ->
                val entry = stash.getOrPut(id) { Stashed() }
                if (isTranslation(language)) entry.translation = captured else entry.main = captured
            }

            lastCaptured = captured
            if (session == null) return
            // 本代际已经投递过：编排器只收第一次，再解析一遍只是白烧 CPU
            if (deliveredKey != null) return

            // 标识相等只是"更强"的证据，不是准入门槛：汽水对同一个播放项会给出两套命名空间
            // （媒体会话的 MEDIA_ID 是 UGC 片段 id，歌词实体的 trackId 是曲目 id），
            // 实测两者并不相等。按不相等就丢弃，等于切歌后整首取不到词
            accept(captured, matchesCurrent(captured.trackId))
        }
    }

    private fun accept(captured: Captured, matched: Boolean) {
        if (isTranslation(captured.language)) {
            pendingTranslation = captured
        } else {
            // 先到先得：同一代际里第一份载荷就是宿主为当前播放项解析的那份。
            // 唯一例外是"对得上标识"的那份可以顶掉对不上的 —— 启动时宿主会一次性预解析
            // 队列里的好几首，其中只有一首属于当前曲目
            val existing = pendingMain
            when {
                existing == null -> {
                    pendingMain = captured
                    pendingMatched = matched
                }
                matched && !pendingMatched -> {
                    pendingMain = captured
                    pendingMatched = true
                }
                else -> {
                    AndroidLog.logD(
                        sourceName,
                        "Keeping earlier payload ${existing.trackId} over ${captured.trackId}",
                    )
                    return
                }
            }
        }
        // 标识对得上：短暂等译文；只有译文：多等主歌词；
        // 标识对不上：先压一会儿，让真正对得上的那份有机会抢先
        val delay = when {
            pendingMain == null -> MAIN_WAIT_MS
            pendingMatched -> TRANSLATION_GRACE_MS
            else -> MISMATCH_WAIT_MS
        }
        scheduled?.cancel(false)
        scheduled = executor.schedule({ deliver() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun deliver() {
        val batch = synchronized(lock) {
            scheduled = null
            val main = pendingMain ?: pendingTranslation
            val translation = pendingTranslation.takeIf { pendingMain != null }
            val matched = pendingMatched
            pendingMain = null
            pendingTranslation = null
            pendingMatched = false
            val target = session
            if (main == null || target == null) null
            else Batch(main, translation, target, identity, matched)
        } ?: return

        val key = batch.main.type + "|" + batch.main.payload.hashCode() + "|" +
            batch.translation?.payload?.hashCode()
        if (key == deliveredKey) return

        val outcome = parse(batch.main, batch.translation, batch.identity)
        if (outcome !is LyricOutcome.Success) {
            // 读不出来不等于没有歌词；投出去会把这一代际关死（见类注释）
            AndroidLog.logD(sourceName, "No usable lyric in ${batch.main.type} payload; staying silent")
            return
        }
        deliveredKey = key
        if (!batch.matched) {
            // 两套命名空间下的 id 不相等是常态，这条日志是"取到的是当前曲目"的旁证
            AndroidLog.logI(
                sourceName,
                "Publishing by arrival order: payload id ${batch.main.trackId} "
                    + "!= identity ${batch.identity?.stableId}",
            )
        }
        batch.target.deliver(outcome)
    }

    /** 一次投递所需的全部快照；在锁内组装，锁外执行 */
    private class Batch(
        val main: Captured,
        val translation: Captured?,
        val target: LyricSource.Session,
        val identity: TrackIdentity?,
        val matched: Boolean,
    )

    private fun parse(
        main: Captured,
        translation: Captured?,
        current: TrackIdentity?,
    ): LyricOutcome {
        // TEXT / 未知标签交给内容嗅探：宿主的标签只作提示，正文自身的结构才是判据。
        // 标签与正文不符时（实测有宿主把逐字载荷标成纯文本），按标签走会整首丢词
        val format = when (main.type.uppercase()) {
            "KRC" -> LyricFormat.KRC
            "LRC" -> LyricFormat.GENERAL
            else -> LyricFormat.AUTO
        }

        val doc = try {
            LyricDiag.parse(
                sourceName,
                LyricRequest(
                    format = format,
                    text = main.payload,
                    translationText = translation?.payload,
                    title = current?.title.orEmpty(),
                    artist = current?.artist.orEmpty(),
                    durationMs = current?.durationMs ?: 0L,
                    lyricId = main.trackId,
                ),
            )
        } catch (t: Throwable) {
            AndroidLog.logW(sourceName, "Failed to parse ${main.type} payload: ${t.message}")
            return LyricOutcome.Unsupported("soda: payload unreadable")
        }

        if (doc == null || doc.isEmpty) {
            AndroidLog.logD(sourceName, "Parsed no lines from ${main.type} payload")
            return LyricOutcome.Unsupported("soda: no parseable line")
        }
        if (!doc.hasUsableTiming()) {
            // 纯文本歌词会解析成一堆 startMs=0 的行；发出去等于把整首词钉在开头
            AndroidLog.logD(sourceName, "Parsed ${doc.lines.size} lines without timeline (${main.type})")
            return LyricOutcome.Unsupported("soda: no timeline")
        }

        AndroidLog.logI(sourceName, "Parsed ${doc.lines.size} lines (${main.type})")
        return LyricOutcome.Success(doc.withMetadata(current))
    }

    /** 至少两个不同的起始时间，或首行起始非零 —— 否则整份载荷没有可用时间轴 */
    private fun LyricDoc.hasUsableTiming(): Boolean {
        val starts = lines.map { it.startMs }.distinct()
        return starts.size >= 2 || (starts.size == 1 && starts[0] > 0L)
    }

    private fun isTranslation(language: String): Boolean =
        language.isNotEmpty() && !language.equals(MAIN_LANGUAGE, ignoreCase = true)

    /**
     * 载荷的标识是否与当前身份**相等**
     *
     * 只作为"更强的证据"使用，不作为准入门槛：汽水对同一个播放项会给出两套命名空间的 id
     * （媒体会话的 `MEDIA_ID` 是 UGC 片段 id，歌词实体的 `trackId` 是曲目 id），
     * 实测二者不相等。把不相等当成"上一首迟到的载荷"会直接丢掉整首词
     *
     * 任意一侧缺标识时返回 true：歌词先于元数据到达时两边都还没有标识
     */
    private fun matchesCurrent(payloadId: String?): Boolean {
        val left = usableId(payloadId) ?: return true
        val right = usableId(identity?.stableId) ?: return true
        if (left == right) return true
        AndroidLog.logD(sourceName, "Track id differs: payload=$left identity=$right")
        return false
    }

    private fun usableId(raw: String?): String? {
        val normalized = TrackIdentity.normalizeStableId(raw) ?: return null
        return normalized.takeIf { it.lowercase() !in PLACEHOLDER_IDS }
    }

    /**
     * 身份与元数据在**投递时**回填
     *
     * 截获可能早于 `setMetadata`，那时身份还停在上一位；烤进文档就再也纠不回来
     */
    private fun LyricDoc.withMetadata(identity: TrackIdentity?): LyricDoc = copy(
        title = title.ifBlank { identity?.title.orEmpty() },
        artist = artist.ifBlank { identity?.artist.orEmpty() },
        durationMs = if (durationMs > 0) durationMs else identity?.durationMs ?: 0L,
        lyricId = lyricId ?: identity?.stableId,
    )

    private companion object {
        /** 主歌词的语言枚举名；其余语言视为译文 */
        const val MAIN_LANGUAGE = "CHINESE"

        /** 等译文的窗口；必须明显小于编排器的 Hook 等待窗口 */
        const val TRANSLATION_GRACE_MS = 260L

        /** 只有译文时等主歌词的窗口；超时后译文自己当正文发布 */
        const val MAIN_WAIT_MS = 900L

        /** 标识对不上时的压制窗口：让真正对得上的那份有机会抢先，再按到达顺序发布 */
        const val MISMATCH_WAIT_MS = 500L

        /** 载荷先于元数据到达时允许补投的时间跨度 */
        const val STASH_WINDOW_MS = 4_000L

        /** 宿主可能写进标识位的占位值；它们不是曲目标识 */
        val PLACEHOLDER_IDS = setOf("", "0", "-1", "null", "unknown", "none")

        /** 按曲目标识暂存的载荷上限；够覆盖"当前 + 预加载下一首"即可 */
        const val MAX_STASH = 4
    }
}
