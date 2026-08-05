/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.hook.music;

import androidx.annotation.NonNull;

/**
 * 歌词来源状态机：网络路径与兜底路径之间的纯决策逻辑。
 * <p>
 * 接收事件（切歌 / 广告 / 播放状态 / 网络请求结果 / 兜底行到达），输出当前发布来源决策，
 * 全程不依赖 Xposed / Android 运行时，可在 JVM 纯测试中直接驱动：
 * {@code transition(state, event)} 为纯函数，入参不可变快照，返回不可变新快照或原快照。
 * <p>
 * 状态由两部分组成：
 * <ul>
 *   <li>{@code source}：当前发布来源决策（网络 / 兜底 / 无）；</li>
 *   <li>{@code contract}：当前音轨已确定的来源契约（网络拉取中 / 网络就绪 / 兜底锁定 /
 *   无歌词）。契约在广告时清空，切歌置为「网络拉取中」；停止只清当前来源、保留契约，
 *   同曲恢复播放时按契约恢复来源，避免停止后同曲续播丢失歌词。</li>
 * </ul>
 * <p>
 * 规则（网络优先 + 占位兜底无缝切换）：
 * 切歌后网络未就绪期间由兜底占位（状态栏兜底未就绪时通知栏兜底顶替）；
 * 网络就绪立即接管发布（允许一次性的临时时间回跳），切到网络后同一首歌内不再回切兜底；
 * 请求失败整曲锁定兜底，晚到的网络响应不接管，但同一首歌内按退避进行有限次重试，
 * 重试成功即切回网络（恢复翻译 / 音译），达到次数上限后最终放弃直到下次切歌；
 * 无歌词 / 纯音乐结束占位保持空白；
 * 荣耀音乐（{@code fallbackAllowed=false}）只走网络、占位与兜底均不启用；
 * 切歌 / 广告 / 停止时当前来源重置，无残留发布决策。
 *
 * @author 焕晨HChen
 */
public final class LyricSourceMachine {
    /** 曲内重试基础退避延迟。 */
    private static final long RETRY_BASE_DELAY_MS = 5000L;
    /** 曲内重试退避延迟上限。 */
    private static final long RETRY_MAX_DELAY_MS = 60000L;

    /** 当前发布来源决策。 */
    public enum Source {
        /** 无任何来源发布（网络拉取中 / 未开始 / 停止）。 */
        NONE,
        /** 网络路径发布（MediaSession + eapi + 42ms 推进）。 */
        NETWORK,
        /** 兜底路径发布（状态栏 / 通知栏单行原文）。 */
        FALLBACK
    }

    /** 当前音轨已确定的来源契约；切歌 / 广告时清空，停止时保留。 */
    public enum Contract {
        /** 已确定无歌词 / 未开始。 */
        NONE,
        /** 网络拉取中：占位兜底可发布，网络就绪后接管。 */
        WAITING,
        /** 网络已就绪。 */
        NETWORK,
        /** 兜底已锁定（网络请求失败，整曲不回切）。 */
        FALLBACK
    }

    /** 状态机事件类型。 */
    public enum EventType {
        /** 切歌：携带新音轨标识。 */
        TRACK_CHANGED,
        /** 广告 / 无有效音轨标识。 */
        AD_OR_UNKNOWN,
        /** 播放中（恢复来源发布）。 */
        PLAYING,
        /** 停止（清当前来源，保留契约）。 */
        STOPPED,
        /** 网络歌词就绪：携带音轨标识。 */
        FETCH_SUCCEEDED,
        /** 网络请求失败：携带音轨标识。 */
        FETCH_FAILED,
        /** 曲内重试成功：携带音轨标识（仅锁定兜底期间可接管回网络）。 */
        RETRY_SUCCEEDED,
        /** 网络结果为空（无歌词 / 纯音乐 / 404）：结束占位，保持空白。 */
        FETCH_EMPTY,
        /** 兜底行到达：发布许可由 {@link #mayPublishFallback(State)} 声明式判定。 */
        FALLBACK_LINE
    }

    /** 状态机事件：类型 + 可选音轨标识（与状态机同样不可变）。 */
    public static final class Event {
        @NonNull
        public final EventType type;
        public final long trackId;

        private Event(@NonNull EventType type, long trackId) {
            this.type = type;
            this.trackId = trackId;
        }

        @NonNull
        public static Event trackChanged(long id) {
            return new Event(EventType.TRACK_CHANGED, id);
        }

        @NonNull
        public static Event adOrUnknown() {
            return new Event(EventType.AD_OR_UNKNOWN, -1L);
        }

        @NonNull
        public static Event playing() {
            return new Event(EventType.PLAYING, -1L);
        }

        @NonNull
        public static Event stopped() {
            return new Event(EventType.STOPPED, -1L);
        }

        @NonNull
        public static Event fetchSucceeded(long id) {
            return new Event(EventType.FETCH_SUCCEEDED, id);
        }

        @NonNull
        public static Event fetchFailed(long id) {
            return new Event(EventType.FETCH_FAILED, id);
        }

        @NonNull
        public static Event retrySucceeded(long id) {
            return new Event(EventType.RETRY_SUCCEEDED, id);
        }

        @NonNull
        public static Event fetchEmpty(long id) {
            return new Event(EventType.FETCH_EMPTY, id);
        }

        @NonNull
        public static Event fallbackLine() {
            return new Event(EventType.FALLBACK_LINE, -1L);
        }
    }

    /** 状态机不可变快照：音轨标识 + 当前来源 + 音轨契约 + 兜底配置 + 失败次数。 */
    public static final class State {
        /** 当前音轨标识；-1 = 无音轨（未初始化 / 广告）。 */
        public final long trackId;
        @NonNull
        public final Source source;
        @NonNull
        public final Contract contract;
        final boolean fallbackAllowed;
        /** 当前音轨已失败的网络尝试次数（含首次失败与每次重试失败）。 */
        public final int failedAttempts;
        /** 当前音轨允许的最大重试次数（超过后最终放弃，直到下次切歌）。 */
        public final int maxRetries;

        private State(
            long trackId,
            @NonNull Source source,
            @NonNull Contract contract,
            boolean fallbackAllowed,
            int failedAttempts,
            int maxRetries
        ) {
            this.trackId = trackId;
            this.source = source;
            this.contract = contract;
            this.fallbackAllowed = fallbackAllowed;
            this.failedAttempts = failedAttempts;
            this.maxRetries = maxRetries;
        }

        /** 兜底是否受支持（网易云 true；荣耀音乐 false）。 */
        public boolean fallbackAllowed() {
            return fallbackAllowed;
        }
    }

    private LyricSourceMachine() {
    }

    /**
     * 初始状态：无音轨、无来源、无契约。
     *
     * @param fallbackAllowed 是否允许兜底来源（网易云 true；荣耀音乐 false）
     * @param maxRetries      当前音轨允许的最大重试次数（0 = 不重试）
     */
    @NonNull
    public static State initial(boolean fallbackAllowed, int maxRetries) {
        return new State(-1L, Source.NONE, Contract.NONE, fallbackAllowed, 0, maxRetries);
    }

    /**
     * 兜底行到达时的发布许可：仅当来源决策为兜底且兜底受支持。
     */
    public static boolean mayPublishFallback(@NonNull State state) {
        return state.fallbackAllowed && state.source == Source.FALLBACK;
    }

    /**
     * 网络行发布许可：仅当来源决策为网络。
     * 与 {@link #mayPublishFallback(State)} 互斥，保证任意时刻只有一个来源发布。
     */
    public static boolean mayPublishNetwork(@NonNull State state) {
        return state.source == Source.NETWORK;
    }

    /**
     * 兜底是否已整曲锁定（网络请求失败）：锁定后晚到的网络响应不得接管发布。
     * 与 {@link #mayPublishFallback(State)} 不同——占位期间来源同为兜底但未锁定，
     * 网络就绪仍可接管。
     */
    public static boolean isFallbackLocked(@NonNull State state) {
        return state.contract == Contract.FALLBACK;
    }

    /**
     * 是否应安排下一次曲内重试：兜底已锁定且失败次数未达上限。
     */
    public static boolean shouldRetry(@NonNull State state) {
        return state.contract == Contract.FALLBACK && state.failedAttempts < state.maxRetries;
    }

    /**
     * 退避延迟：按失败次数指数退避（5s / 10s / 20s …，上限 60s），纯函数可单测。
     */
    public static long retryDelayMs(int failedAttempts) {
        if (failedAttempts <= 1) return RETRY_BASE_DELAY_MS;
        // 限制移位阶数，避免极端失败次数下 long 位移溢出为负数
        int shift = Math.min(failedAttempts - 1, 16);
        return Math.min(RETRY_BASE_DELAY_MS << shift, RETRY_MAX_DELAY_MS);
    }

    /**
     * 纯转移函数：给定当前状态与事件，返回新状态（无变化时返回原快照）。
     */
    @NonNull
    public static State transition(@NonNull State state, @NonNull Event event) {
        switch (event.type) {
            case TRACK_CHANGED:
                return trackChanged(state, event.trackId);
            case AD_OR_UNKNOWN:
                return adOrUnknown(state);
            case PLAYING:
                return playing(state);
            case STOPPED:
                return stopped(state);
            case FETCH_SUCCEEDED:
                return fetchSucceeded(state, event.trackId);
            case FETCH_FAILED:
                return fetchFailed(state, event.trackId);
            case RETRY_SUCCEEDED:
                return retrySucceeded(state, event.trackId);
            case FETCH_EMPTY:
                return fetchEmpty(state, event.trackId);
            case FALLBACK_LINE:
                // 发布许可由 mayPublishFallback 声明式判定，状态不因行到达而改变
                return state;
            default:
                return state;
        }
    }

    /**
     * 切歌：进入网络拉取等待期——网易云由兜底占位发布（{@link Source#FALLBACK}），
     * 荣耀音乐无兜底保持无来源（{@link Source#NONE}）；契约 {@link Contract#WAITING}。
     */
    @NonNull
    private static State trackChanged(@NonNull State state, long id) {
        if (state.trackId == id && state.contract == Contract.WAITING) return state;
        Source placeholder = state.fallbackAllowed ? Source.FALLBACK : Source.NONE;
        return new State(id, placeholder, Contract.WAITING, state.fallbackAllowed, 0, state.maxRetries);
    }

    /** 广告 / 无有效音轨：清空音轨、来源与契约。 */
    @NonNull
    private static State adOrUnknown(@NonNull State state) {
        if (state.trackId == -1L && state.source == Source.NONE && state.contract == Contract.NONE) {
            return state;
        }
        return new State(-1L, Source.NONE, Contract.NONE, state.fallbackAllowed, 0, state.maxRetries);
    }

    /**
     * 恢复播放：按音轨契约恢复来源——网络就绪回网络；兜底锁定 / 网络拉取中且支持兜底回兜底；
     * 契约已定为无歌词时保持无来源。
     */
    @NonNull
    private static State playing(@NonNull State state) {
        Source resumed;
        switch (state.contract) {
            case NETWORK:
                resumed = Source.NETWORK;
                break;
            case FALLBACK:
            case WAITING:
                resumed = state.fallbackAllowed ? Source.FALLBACK : Source.NONE;
                break;
            default:
                return state;
        }
        if (state.source == resumed) return state;
        return new State(
            state.trackId, resumed, state.contract, state.fallbackAllowed,
            state.failedAttempts, state.maxRetries
        );
    }

    /**
     * 停止：清空当前发布来源（停止兜底 / 停发网络行），保留音轨契约，
     * 同曲恢复播放时由 {@link #playing(State)} 恢复来源。
     */
    @NonNull
    private static State stopped(@NonNull State state) {
        if (state.source == Source.NONE) return state;
        return new State(
            state.trackId, Source.NONE, state.contract, state.fallbackAllowed,
            state.failedAttempts, state.maxRetries
        );
    }

    /**
     * 网络歌词就绪：非本音轨 / 兜底已锁定时忽略（晚到响应不接管），
     * 否则切到网络并固化契约（占位兜底立即让位，允许一次性临时时间回跳）。
     */
    @NonNull
    private static State fetchSucceeded(@NonNull State state, long id) {
        if (state.trackId != id || state.contract == Contract.FALLBACK) return state;
        if (state.source == Source.NETWORK && state.contract == Contract.NETWORK) return state;
        return new State(state.trackId, Source.NETWORK, Contract.NETWORK, state.fallbackAllowed, 0, state.maxRetries);
    }

    /**
     * 网络请求失败：当前音轨且未成功时整曲锁定兜底并累加失败次数；
     * 已成功（契约网络就绪）时晚到失败不改变决策；荣耀音乐不支持兜底，失败保持空白。
     */
    @NonNull
    private static State fetchFailed(@NonNull State state, long id) {
        if (state.trackId != id || !state.fallbackAllowed) return state;
        if (state.contract == Contract.NETWORK) return state;
        if (state.contract == Contract.FALLBACK) {
            // 重试失败：仅累加失败次数（由 shouldRetry 决定是否继续 / 最终放弃）
            return new State(
                state.trackId, Source.FALLBACK, Contract.FALLBACK, true,
                state.failedAttempts + 1, state.maxRetries
            );
        }
        // 占位 / 未确定：首次失败，锁定兜底
        return new State(state.trackId, Source.FALLBACK, Contract.FALLBACK, true, 1, state.maxRetries);
    }

    /**
     * 曲内重试成功：仅锁定兜底期间允许接管回网络（遵守 03 单向切换与无双发规则），
     * 失败次数清零；非锁定状态按普通成功处理（兜底保护）。
     */
    @NonNull
    private static State retrySucceeded(@NonNull State state, long id) {
        if (state.trackId != id) return state;
        if (state.contract != Contract.FALLBACK) return fetchSucceeded(state, id);
        if (state.source == Source.NETWORK && state.contract == Contract.NETWORK) return state;
        return new State(state.trackId, Source.NETWORK, Contract.NETWORK, state.fallbackAllowed, 0, state.maxRetries);
    }

    /**
     * 网络结果为空（无歌词 / 纯音乐 / 404）：结束占位，契约定为无歌词并保持空白；
     * 兜底已锁定 / 网络已就绪时忽略（不改变既有决策）。
     */
    @NonNull
    private static State fetchEmpty(@NonNull State state, long id) {
        if (state.trackId != id) return state;
        if (state.contract == Contract.FALLBACK || state.contract == Contract.NETWORK) return state;
        if (state.source == Source.NONE && state.contract == Contract.NONE) return state;
        return new State(state.trackId, Source.NONE, Contract.NONE, state.fallbackAllowed, 0, state.maxRetries);
    }
}
