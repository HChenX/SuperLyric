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
package com.hchen.superlyric.kernel.orchestrate

import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.outcome.LyricOutcome

/** 可取消的调度句柄。 */
fun interface Cancellable {
    fun cancel()
}

/**
 * 定时调度抽象。测试时注入假实现即可确定性复现超时与退避
 */
interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/**
 * 一个歌词来源
 *
 * **线程约束**：[start] 由编排器在锁外调用，但仍运行在调用方线程上，因此实现**必须快速返回**。
 * 轻量登记可以同步做，任何网络、文件 IO 或加解密都必须放到自己的后台线程，完成后经
 * [Session.deliver] 回投 —— 在 [start] 里做重 IO 会拖住调用方，进而阻塞超时调度与后续切歌
 */
interface LyricSource {

    /** 来源名，仅用于诊断 */
    val sourceName: String

    /**
     * 发起一次取词。**必须快速返回**。
     *
     * @param identity 目标音轨
     * @param session  回投通道；结果就绪时调用 [Session.deliver]，
     *                 可多次调用（编排器以先到者为准，并按代际丢弃过期结果）
     */
    fun start(identity: TrackIdentity, session: Session)

    /** 取消进行中的取词。 */
    fun cancel()

    /** 结果回投通道。 */
    fun interface Session {
        fun deliver(outcome: LyricOutcome)
    }
}

/**
 * 歌词获取编排状态机
 *
 * ## 状态
 * ```
 *   IDLE ──切歌──► RESOLVING_HOOK ──超时/失效──► FETCHING_API ──► ACTIVE
 *                        │                          │
 *                        └──── 直接命中 ─────────────┘
 *                                                   └──► NO_LYRICS（终态，可下发纯音乐占位）
 *                                                   └──► UNAVAILABLE（重试耗尽，不得下发占位）
 * ```
 *
 * ## Epoch 取消机制
 * 每次**切歌**与**停止**都自增 [epoch]，所有异步回投在送达时校验 epoch，不匹配即静默丢弃。
 * 这一条守卫同时挡住三件事：停止后迟到的捕获仍发布歌词、旧曲目的网络结果覆盖新曲目、
 * 以及快速连切时的时序竞争
 *
 * ## 并发模型
 * 所有共享状态由 [lock] 保护，但**锁内只做决策，不做执行**：每个公开入口在锁内构造一份
 * [Dispatch]，随后在锁外执行它。这样既不在持锁时做 Binder 发布或来源启动，
 * 也不存在"多个线程在锁外争抢清理同一份待发通知"的竞态
 *
 * @author 焕晨HChen
 */
class LyricOrchestrator(
    private val hookSource: LyricSource?,
    private val apiSource: LyricSource?,
    private val scheduler: Scheduler,
    private val listener: Listener,
    private val config: Config = Config(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val diagnostics: (String) -> Unit = {},
) {

    enum class State {
        IDLE,
        RESOLVING_HOOK,
        FETCHING_API,
        ACTIVE,
        NO_LYRICS,
        UNAVAILABLE,
    }

    /**
     * 编排结果的下游回调。
     */
    interface Listener {
        /**
         * 确认切换到新曲目，且该曲目尚无歌词。
         *
         * 下游应据此**立即作废上一首的展示**：旧歌词与当前播放已无关系，
         * 继续留着会让界面显示错误内容。具体下发什么由下游决定——
         * 宿主侧目前下发切歌包，把空窗期呈现方式交给客户端。
         */
        fun onTrackChanged(identity: TrackIdentity) = Unit

        /** 成功取得全量歌词。 */
        fun onLyricResolved(outcome: LyricOutcome.Success)

        /**
         * 平台或宿主**明确**说这首没有歌词——这是唯一允许下发「纯音乐」占位的结论。
         *
         * 与 [onUnavailable] 的区别在于归因：这里对方表了态，那里是我们没拿到
         */
        fun onNoLyrics(identity: TrackIdentity?)

        /** 取词失败且重试耗尽——**不得**下发占位，应保持等待或留空。 */
        fun onUnavailable(identity: TrackIdentity?)

        /** 播放停止。 */
        fun onIdle()

        /** 状态迁移，供诊断与测试观察。 */
        fun onStateChanged(state: State) = Unit
    }

    /**
     * @property hookTimeoutMs       等待 Hook 异步捕获的上限。宿主常在 30~100ms 后才吐出数据，
     *                               因此绝不能在 0ms 立即判定 Hook 失效转网络。
     * @property maxApiAttempts      网络取词的最大尝试次数（含首次）
     * @property retryBaseDelayMs    指数退避基数
     * @property provisionalGraceMs  允许"临时身份原地升级"的时间窗口，见 [isProvisionalUpgrade]
     */
    data class Config(
        val hookTimeoutMs: Long = 800L,
        val maxApiAttempts: Int = 3,
        val retryBaseDelayMs: Long = 500L,
        val provisionalGraceMs: Long = 3_000L,
    )

    private val lock = Any()

    private var epoch: Long = 0L
    private var currentIdentity: TrackIdentity? = null
    private var currentSinceMs: Long = 0L
    private var apiAttempts: Int = 0
    private var hookDelivered: Boolean = false
    private var apiDelivered: Boolean = false
    private var hookTimeout: Cancellable? = null

    @Volatile
    var state: State = State.IDLE
        private set

    /** 当前正在跟踪的音轨；停止后为 null。 */
    @Volatile
    var activeIdentity: TrackIdentity? = null
        private set

    /**
     * 锁内构造、锁外执行的动作清单。
     */
    private class Dispatch {
        var newTrack: TrackIdentity? = null
        var startHookEpoch: Long? = null
        var startApiEpoch: Long? = null
        var startApiIdentity: TrackIdentity? = null
        var stateChanged: State? = null
        var resolved: LyricOutcome.Success? = null
        var noLyrics = false
        var unavailable = false
        var idle = false
        var cancelHookSource = false
        var cancelApiSource = false
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 切歌。若判定为同一首曲目且已有结果，则保持现状不做重置。
     */
    fun onTrackChanged(identity: TrackIdentity) {
        synchronized(lock) { planTrackChanged(identity) }?.let(::execute)
    }

    /**
     * Hook 来源回投结果。
     */
    fun onHookCaptured(capturedEpoch: Long, outcome: LyricOutcome) {
        synchronized(lock) { planHookCaptured(capturedEpoch, outcome) }?.let(::execute)
    }

    /**
     * 停止播放。
     *
     * **必须**清空当前音轨并推进 epoch——这正是"停止后仍发布于歌词"缺陷的根治点。
     */
    fun onPlaybackStopped() {
        val dispatch = synchronized(lock) {
            epoch++
            cancelHookTimeoutLocked()
            currentIdentity = null
            currentSinceMs = 0L
            activeIdentity = null
            apiAttempts = 0
            hookDelivered = false
            apiDelivered = false

            Dispatch().apply {
                transitionLocked(State.IDLE, this)
                idle = true
                cancelHookSource = true
                cancelApiSource = true
            }
        }
        execute(dispatch)
    }

    /**
     * 由宿主主动上报 Hook 侧的确定性结论（宿主自己的解析函数明确判定"无歌词"）。
     */
    fun onHookDeterminedInvalid() {
        val dispatch = synchronized(lock) {
            if (state != State.RESOLVING_HOOK) return@synchronized null
            cancelHookTimeoutLocked()
            val identity = currentIdentity ?: return@synchronized null
            planStartApi(identity, epoch, LyricOutcome.NoLyrics("host reported no lyric"))
        }
        dispatch?.let(::execute)
    }

    // ------------------------------------------------------------ 锁内决策

    /**
     * @return 待执行的动作；判定为同一首曲目时返回 null。
     */
    private fun planTrackChanged(identity: TrackIdentity): Dispatch? {
        val current = currentIdentity
        if (current != null) {
            if (current.matches(identity) || isTitleEnrichment(current, identity)) {
                // 同一首曲目：只刷新元数据，**绝不重启取词**。
                //
                // 这里刻意不区分状态。早期实现只在 ACTIVE / RESOLVING_HOOK / FETCHING_API
                // 时按"同一首"处理，NO_LYRICS 与 UNAVAILABLE 会落到下面重启一次——
                // 那正是"事件 → 取词 → 无歌词 → 事件"死循环的成因：
                // 宿主若高频改写元数据（椒盐开启蓝牙歌词后会把当前歌词行写进 TITLE），
                // 同一个稳定标识会每几秒触发一次全新取词，并且**每次都下发纯音乐占位**。
                // 而 NO_LYRICS 在架构上本就是终态，重启不可能得到不同结果。
                currentIdentity = identity
                activeIdentity = identity
                return null
            } else if (isProvisionalUpgrade(current, identity)) {
                // 同一首歌拿到了更好的标识——原地升级，**不推进 epoch、不重启取词**。
                // 推进 epoch 会让在途的取词结果因代际过期被丢弃，于是白白重取一次。
                currentIdentity = identity
                activeIdentity = identity
                currentSinceMs = nowMs()
                return null
            }
        }

        epoch++
        val startedEpoch = epoch
        cancelHookTimeoutLocked()
        currentIdentity = identity
        activeIdentity = identity
        currentSinceMs = nowMs()
        apiAttempts = 0
        hookDelivered = false
        apiDelivered = false

        val dispatch = Dispatch().apply { newTrack = identity }

        if (hookSource != null) {
            transitionLocked(State.RESOLVING_HOOK, dispatch)
            hookTimeout = scheduler.schedule(config.hookTimeoutMs) { onHookTimedOut(startedEpoch) }
            dispatch.startHookEpoch = startedEpoch
        } else {
            planStartApi(identity, startedEpoch, null, dispatch)
        }
        return dispatch
    }

    /**
     * 判断是否为同一平台标识的两种写法：一侧带标题段、另一侧还没带上
     *
     * 稳定标识由「平台 id + 标题」拼成，而标题要等宿主把曲目对象交出来才拿得到。
     * 实测酷我：元数据先到（`kuwo:486777537`），44ms 后曲目探针补上标题
     * （`kuwo:486777537#南山南…`）。按字符串判等会当成换歌——推进 epoch、清屏、
     * 重新取词一次，而那次取词拿到的还是同一首。
     *
     * 只在**有一侧没有标题段**时放宽。两侧都带标题且不同，是有声书换集
     * （专辑级 rid 下唯一能区分集的依据），必须按换歌处理。
     *
     * **必须有时间上界**：标题是紧接着 id 补上来的，而"这一首始终没拿到标题"
     * （该集没有歌词、探针从未触发）与"换到同一专辑的另一集"在字符串上无法区分。
     * 窗口外一律按换歌处理，否则新一集会一直沿用上一集的歌词。
     */
    private fun isTitleEnrichment(current: TrackIdentity, next: TrackIdentity): Boolean {
        val age = nowMs() - currentSinceMs
        if (age < 0L || age > config.provisionalGraceMs) return false
        val a = TrackIdentity.normalizeStableId(current.stableId) ?: return false
        val b = TrackIdentity.normalizeStableId(next.stableId) ?: return false
        if (a == b) return true
        val aRid = a.substringBefore('#')
        if (aRid.isEmpty() || aRid != b.substringBefore('#')) return false
        return !a.contains('#') || !b.contains('#')
    }

    /**
     * 判断是否为"同一首歌拿到了更权威的标识"，而不是换歌
     *
     * 宿主上报标识常常**分步收敛**：先给一个降级形态，稍后再给权威形态。实测 QQ 音乐先给
     * 数字 songID、约 30ms 后再给 14 位 songMID —— 两者是同一音轨；不识别这一步，
     * 第二次上报会被当成切歌，第一次已完成的取词因代际过期被丢弃
     *
     * **不比对标题**：部分宿主会把当前正在唱的那句歌词写进 `METADATA_KEY_TITLE`（椒盐音乐即是），
     * 标题因此不是可信的同一性判据。本判据只用歌手与时长作佐证
     *
     * **必须有时间上界**：判据放宽了标题这一项，就必须限制它只能在"临时身份刚建立"的短窗口内
     * 生效，否则长期放宽会吞掉真实的切歌。窗口用 [Config.provisionalGraceMs] 控制。
     * 这**不是去抖** —— 本机制不延迟任何东西，只判断该不该换歌
     */
    private fun isProvisionalUpgrade(current: TrackIdentity, next: TrackIdentity): Boolean {
        // 当前已有权威标识：任何变化都按换歌处理，不做任何放宽
        if (current.hasStableId) return false
        // 新标识也不权威：交给 matches 的组合判据，本机制不介入
        if (!next.hasStableId) return false
        if (current.source != next.source) return false

        // 已经出结果了就不再放宽：此时"漏判切歌"的代价（展示错歌的歌词）远高于
        // "误判切歌"的代价（多取一次词），因此一律按换歌处理。
        if (state == State.ACTIVE) return false

        val age = nowMs() - currentSinceMs
        if (age < 0L || age > config.provisionalGraceMs) return false

        return TrackIdentity.artistCompatible(current.artist, next.artist) &&
            TrackIdentity.durationCompatible(current.durationMs, next.durationMs)
    }

    private fun planHookCaptured(capturedEpoch: Long, outcome: LyricOutcome): Dispatch? {
        // 丢弃必须留痕：静默丢弃过一次真实的取词成果，排查时完全看不出它到过这里
        if (capturedEpoch != epoch) {
            diagnostics("dropping hook outcome ${outcome.label}: epoch $capturedEpoch != $epoch")
            return null
        }
        if (hookDelivered) {
            diagnostics("dropping hook outcome ${outcome.label}: hook already delivered")
            return null
        }
        hookDelivered = true
        cancelHookTimeoutLocked()

        val dispatch = Dispatch()

        if (outcome is LyricOutcome.Success) {
            transitionLocked(State.ACTIVE, dispatch)
            dispatch.resolved = outcome
            return dispatch
        }

        // Hook 侧给不出歌词（无论确定无歌词还是暂时失败），一律交给网络源再试一次
        val identity = currentIdentity ?: return dispatch
        planStartApi(identity, epoch, outcome, dispatch)
        return dispatch
    }

    private fun planStartApi(
        identity: TrackIdentity,
        startEpoch: Long,
        hookOutcome: LyricOutcome?,
        dispatch: Dispatch = Dispatch(),
    ): Dispatch {
        if (apiDelivered) return dispatch

        // 两个来源都不存在 = Provider 配置错误。绝不能与"平台明确说没有歌词"走同一条路：
        // 那会给每一首歌都下发纯音乐占位，而且毫无痕迹
        if (apiSource == null && hookSource == null) {
            diagnostics("no lyric source configured; refusing to conclude 'no lyrics'")
            transitionLocked(State.UNAVAILABLE, dispatch)
            dispatch.unavailable = true
            return dispatch
        }

        if (apiSource == null) {
            // 没有可用的网络来源，只能就 Hook 的结论收尾。
            // **只有宿主明确说"没有歌词"才允许下发占位**：`Unsupported`（读不到文件、清洗后
            // 一行不剩）与"暂时失败"一样都是**我们没拿到**，判成纯音乐等于替宿主断言——
            // 而且"清洗后一行不剩"这种情形恰恰说明宿主**给了**歌词行
            if (hookOutcome is LyricOutcome.NoLyrics) {
                transitionLocked(State.NO_LYRICS, dispatch)
                dispatch.noLyrics = true
            } else {
                transitionLocked(State.UNAVAILABLE, dispatch)
                dispatch.unavailable = true
            }
            return dispatch
        }

        transitionLocked(State.FETCHING_API, dispatch)
        dispatch.startApiEpoch = startEpoch
        dispatch.startApiIdentity = identity
        return dispatch
    }

    private fun onHookTimedOut(timedOutEpoch: Long) {
        val dispatch = synchronized(lock) {
            if (timedOutEpoch != epoch) return@synchronized null
            if (state != State.RESOLVING_HOOK) return@synchronized null
            if (hookDelivered) {
                // Hook 其实已经交付过，超时任务只是晚到——留着这条痕迹才能区分
                // "Hook 没结果"与"Hook 有结果但被别的路径吃掉了"
                diagnostics("hook timeout fired after the hook had already delivered")
                return@synchronized null
            }
            val identity = currentIdentity ?: return@synchronized null
            hookDelivered = true
            // 超时只说明"这次没等到"，绝不等于"没有歌词"
            planStartApi(identity, epoch, LyricOutcome.TransientFailure())
        }
        dispatch?.let(::execute)
    }

    private fun planApiCaptured(capturedEpoch: Long, outcome: LyricOutcome): Dispatch? {
        if (capturedEpoch != epoch) {
            diagnostics("dropping api outcome ${outcome.label}: epoch $capturedEpoch != $epoch")
            return null
        }

        val dispatch = Dispatch()

        when (outcome) {
            is LyricOutcome.Success -> {
                apiDelivered = true
                transitionLocked(State.ACTIVE, dispatch)
                dispatch.resolved = outcome
            }

            is LyricOutcome.NoLyrics -> {
                // 平台明确说"这首没有歌词"——**这是允许下发纯音乐占位的唯一来源**
                transitionLocked(State.NO_LYRICS, dispatch)
                dispatch.noLyrics = true
            }

            is LyricOutcome.Unsupported -> {
                // "这个来源服务不了这一首"（平台无版权、拿不到必要的标识）**不是**"平台说没有歌词"：
                // 请求要么根本没发出去，要么服务端没说这句话。据此下发占位等于替平台编结论
                transitionLocked(State.UNAVAILABLE, dispatch)
                dispatch.unavailable = true
            }

            is LyricOutcome.TransientFailure -> {
                apiAttempts++
                if (apiAttempts >= config.maxApiAttempts) {
                    // 重试耗尽：失败不等于"没有歌词"，绝不下发纯音乐占位
                    transitionLocked(State.UNAVAILABLE, dispatch)
                    dispatch.unavailable = true
                } else {
                    val identity = currentIdentity
                    if (identity != null) {
                        val delay = config.retryBaseDelayMs shl (apiAttempts - 1)
                        val retryEpoch = epoch
                        scheduler.schedule(delay) { scheduleRetry(retryEpoch, identity) }
                    }
                }
            }
        }
        return dispatch
    }

    private fun onApiCaptured(capturedEpoch: Long, outcome: LyricOutcome) {
        synchronized(lock) { planApiCaptured(capturedEpoch, outcome) }?.let(::execute)
    }

    private fun scheduleRetry(retryEpoch: Long, identity: TrackIdentity) {
        val dispatch = synchronized(lock) {
            if (retryEpoch != epoch) return@synchronized null
            Dispatch().apply {
                startApiEpoch = retryEpoch
                startApiIdentity = identity
            }
        }
        dispatch?.let(::execute)
    }

    // ------------------------------------------------------------ 锁外执行

    private fun execute(dispatch: Dispatch) {
        if (dispatch.cancelHookSource) hookSource?.cancel()
        if (dispatch.cancelApiSource) apiSource?.cancel()

        dispatch.newTrack?.let(listener::onTrackChanged)

        // 状态迁移必须**先于**启动来源回调：来源可以在 start() 里**同步**回投
        // （例如按稳定标识暂存后的补投），那样它会把状态一路推到 ACTIVE。
        // 若此时才发出先前捕获的 RESOLVING_HOOK，监听者就会看到
        // ACTIVE 之后又回到 RESOLVING_HOOK——内部 state 是对的，但对外回报的顺序错了。
        dispatch.stateChanged?.let(listener::onStateChanged)

        dispatch.startHookEpoch?.let { hookEpoch ->
            val identity = dispatch.newTrack ?: activeIdentity
            if (identity != null) {
                hookSource?.start(identity) { outcome -> onHookCaptured(hookEpoch, outcome) }
            }
        }

        dispatch.startApiEpoch?.let { apiEpoch ->
            val identity = dispatch.startApiIdentity ?: activeIdentity
            if (identity != null) {
                apiSource?.start(identity) { outcome -> onApiCaptured(apiEpoch, outcome) }
            }
        }

        // 下游回调一律在锁外，避免持锁进行 Binder 发布
        dispatch.resolved?.let(listener::onLyricResolved)
        if (dispatch.noLyrics) listener.onNoLyrics(activeIdentity)
        if (dispatch.unavailable) listener.onUnavailable(activeIdentity)
        if (dispatch.idle) listener.onIdle()
    }

    // ------------------------------------------------------------ 工具

    private fun cancelHookTimeoutLocked() {
        hookTimeout?.cancel()
        hookTimeout = null
    }

    private fun transitionLocked(next: State, dispatch: Dispatch) {
        if (state == next) return
        state = next
        dispatch.stateChanged = next
    }

    /**
     * 诊断用的稳定标签
     *
     * **不要用 `::class.simpleName`**：release 构建下 R8 会把子类名混淆成 `bw0` 之类，
     * 于是这行诊断恰好只在最需要它的场景里不可读（真机日志实证）
     */
    private val LyricOutcome.label: String
        get() = when (this) {
            is LyricOutcome.Success -> "Success"
            is LyricOutcome.NoLyrics -> "NoLyrics"
            is LyricOutcome.Unsupported -> "Unsupported"
            is LyricOutcome.TransientFailure -> "TransientFailure"
        }
}
