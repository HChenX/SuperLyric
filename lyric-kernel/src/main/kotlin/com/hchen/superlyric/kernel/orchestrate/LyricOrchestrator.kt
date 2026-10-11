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
 * 定时调度抽象。测试时注入假实现即可确定性复现超时与退避。
 */
interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/**
 * 一个歌词来源。
 *
 * <h3>线程约束（重要）</h3>
 * [start] 由编排器在**锁外**调用，但仍运行在调用方线程上，因此实现**必须快速返回**：
 * 轻量登记可以同步做，任何网络、文件 IO 或加解密都必须放到自己的后台线程，
 * 完成后经 [Session.deliver] 回投。
 *
 * 这条约束直接对应重构前的一个真实故障：旧编排器在持有监视器锁时调用 Hook 引擎的
 * `tryExtractFullLyric`，而酷狗实现会在其中遍历多个目录做 `readAllBytes`，
 * 导致超时任务与后续切歌全部被阻塞。
 */
interface LyricSource {

    /** 来源名，仅用于诊断。 */
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
 * 歌词获取编排状态机。
 *
 * <h3>状态</h3>
 * <pre>
 *   IDLE ──切歌──► RESOLVING_HOOK ──超时/失效──► FETCHING_API ──► ACTIVE
 *                        │                          │
 *                        └──── 直接命中 ─────────────┘
 *                                                   └──► NO_LYRICS（终态，可下发纯音乐占位）
 *                                                   └──► UNAVAILABLE（重试耗尽，不得下发占位）
 * </pre>
 *
 * 移除了重构前的 {@code LEGACY_FALLBACK} 状态——它对应的引擎接口从未有任何实现，
 * 该状态在运行期不可达，相关分支全是死代码。
 *
 * <h3>Epoch 取消机制</h3>
 * 每次**切歌**与**停止**都自增 [epoch]。所有异步回投在送达时校验 epoch，不匹配即静默丢弃。
 * 这一条守卫同时根治了三个历史缺陷：停止后迟到的捕获仍发布歌词、旧曲目的网络结果覆盖新曲目、
 * 以及快速连切时的时序竞争。
 *
 * <h3>并发模型</h3>
 * 所有共享状态由 [lock] 保护，但**锁内只做决策，不做执行**：
 * 每个公开入口在锁内构造一份 [Dispatch]，随后在锁外执行它。
 * 这样既避免了在持有锁时进行 Binder 发布或来源启动，
 * 也不存在"多个线程在锁外争抢清理同一份待发通知"的竞态。
 *
 * @author 焕晨HChen
 */
class LyricOrchestrator(
    private val hookSource: LyricSource?,
    private val apiSource: LyricSource?,
    private val scheduler: Scheduler,
    private val listener: Listener,
    private val config: Config = Config(),
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
         * 下游应据此**清空上一首的残留展示**。注意这不是"停止播放"，
         * 因此**不得**走停止通道——那会清空服务端的活跃发布者并广播停播。
         */
        fun onTrackChanged(identity: TrackIdentity) = Unit

        /** 成功取得全量歌词。 */
        fun onLyricResolved(outcome: LyricOutcome.Success)

        /** 各来源一致确认该曲目无歌词——此时**才**允许下发「纯音乐」占位。 */
        fun onNoLyrics(identity: TrackIdentity?)

        /** 取词失败且重试耗尽——**不得**下发占位，应保持等待或留空。 */
        fun onUnavailable(identity: TrackIdentity?)

        /** 播放停止。 */
        fun onIdle()

        /** 状态迁移，供诊断与测试观察。 */
        fun onStateChanged(state: State) = Unit
    }

    /**
     * @property hookTimeoutMs    等待 Hook 异步捕获的上限。宿主常在 30~100ms 后才吐出数据，
     *                            因此绝不能在 0ms 立即判定 Hook 失效转网络。
     * @property maxApiAttempts   网络取词的最大尝试次数（含首次）
     * @property retryBaseDelayMs 指数退避基数
     */
    data class Config(
        val hookTimeoutMs: Long = 800L,
        val maxApiAttempts: Int = 3,
        val retryBaseDelayMs: Long = 500L,
    )

    private val lock = Any()

    private var epoch: Long = 0L
    private var currentIdentity: TrackIdentity? = null
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
        if (current != null && current.matches(identity)) {
            if (state == State.ACTIVE || state == State.RESOLVING_HOOK || state == State.FETCHING_API) {
                currentIdentity = identity
                activeIdentity = identity
                return null
            }
        }

        epoch++
        val startedEpoch = epoch
        cancelHookTimeoutLocked()
        currentIdentity = identity
        activeIdentity = identity
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

    private fun planHookCaptured(capturedEpoch: Long, outcome: LyricOutcome): Dispatch? {
        if (capturedEpoch != epoch) return null
        if (hookDelivered) return null
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

        if (apiSource == null) {
            // 没有可用的网络来源，只能就 Hook 的结论收尾。
            // 注意：Hook 的"暂时失败"绝不等于"没有歌词"。
            if (hookOutcome is LyricOutcome.TransientFailure) {
                transitionLocked(State.UNAVAILABLE, dispatch)
                dispatch.unavailable = true
            } else {
                transitionLocked(State.NO_LYRICS, dispatch)
                dispatch.noLyrics = true
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
            if (hookDelivered) return@synchronized null
            val identity = currentIdentity ?: return@synchronized null
            hookDelivered = true
            // 超时只说明"这次没等到"，绝不等于"没有歌词"
            planStartApi(identity, epoch, LyricOutcome.TransientFailure())
        }
        dispatch?.let(::execute)
    }

    private fun planApiCaptured(capturedEpoch: Long, outcome: LyricOutcome): Dispatch? {
        if (capturedEpoch != epoch) return null

        val dispatch = Dispatch()

        when (outcome) {
            is LyricOutcome.Success -> {
                apiDelivered = true
                transitionLocked(State.ACTIVE, dispatch)
                dispatch.resolved = outcome
            }

            is LyricOutcome.NoLyrics, is LyricOutcome.Unsupported -> {
                transitionLocked(State.NO_LYRICS, dispatch)
                dispatch.noLyrics = true
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
        dispatch.stateChanged?.let(listener::onStateChanged)
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
}
