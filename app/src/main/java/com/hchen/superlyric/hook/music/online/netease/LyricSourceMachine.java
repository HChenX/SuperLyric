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
package com.hchen.superlyric.hook.music.online.netease;

import androidx.annotation.NonNull;

/**
 * 歌词来源状态机：网络路径的纯决策逻辑。
 * <p>
 * {@code transition(state, event)} 为纯函数：入参不可变快照，返回不可变新快照，
 * 不依赖 Android / Xposed 运行时，可 JVM 单测。状态含来源决策（source）与
 * 音轨契约（contract）；切歌后网络就绪立即发布，请求失败保持空白并按指数退避
 * 进行有限次曲内重试，重试成功即切回网络，达上限后放弃直到下次切歌。
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
public final class LyricSourceMachine {
    /**
     * 曲内重试基础退避延迟。
     */
    private static final long RETRY_BASE_DELAY_MS = 5000L;
    /**
     * 曲内重试退避延迟上限。
     */
    private static final long RETRY_MAX_DELAY_MS = 60000L;

    /**
     * 当前发布来源决策。
     */
    public enum Source {
        /**
         * 无任何来源发布（网络拉取中 / 未开始 / 停止）。
         */
        NONE,
        /**
         * 网络路径发布（MediaSession + eapi + 42ms 推进）。
         */
        NETWORK,
    }

    /**
     * 当前音轨已确定的来源契约；切歌 / 广告时清空，停止时保留。
     */
    public enum Contract {
        /**
         * 已确定无歌词 / 未开始。
         */
        NONE,
        /**
         * 网络拉取中。
         */
        WAITING,
        /**
         * 网络已就绪。
         */
        NETWORK,
    }

    /**
     * 状态机事件类型。
     */
    public enum EventType {
        /**
         * 切歌：携带新音轨标识。
         */
        TRACK_CHANGED,
        /**
         * 广告 / 无有效音轨标识。
         */
        AD_OR_UNKNOWN,
        /**
         * 播放中（恢复来源发布）。
         */
        PLAYING,
        /**
         * 停止（清当前来源，保留契约）。
         */
        STOPPED,
        /**
         * 网络歌词就绪：携带音轨标识。
         */
        FETCH_SUCCEEDED,
        /**
         * 网络请求失败：携带音轨标识。
         */
        FETCH_FAILED,
        /**
         * 曲内重试成功：携带音轨标识。
         */
        RETRY_SUCCEEDED,
        /**
         * 网络结果为空（无歌词 / 纯音乐 / 404）：保持空白。
         */
        FETCH_EMPTY,
    }

    /**
     * 状态机事件：类型 + 可选音轨标识（与状态机同样不可变）。
     */
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
    }

    /**
     * 状态机不可变快照：音轨标识 + 当前来源 + 音轨契约 + 失败次数。
     */
    public static final class State {
        /**
         * 当前音轨标识；-1 = 无音轨（未初始化 / 广告）。
         */
        public final long trackId;
        @NonNull
        public final Source source;
        @NonNull
        public final Contract contract;
        /**
         * 当前音轨已失败的网络尝试次数（含首次失败与每次重试失败）。
         */
        public final int failedAttempts;
        /**
         * 当前音轨允许的最大重试次数（超过后最终放弃，直到下次切歌）。
         */
        public final int maxRetries;

        private State(
            long trackId,
            @NonNull Source source,
            @NonNull Contract contract,
            int failedAttempts,
            int maxRetries
        ) {
            this.trackId = trackId;
            this.source = source;
            this.contract = contract;
            this.failedAttempts = failedAttempts;
            this.maxRetries = maxRetries;
        }
    }

    private LyricSourceMachine() {
    }

    /**
     * 初始状态：无音轨、无来源、无契约。
     *
     * @param maxRetries 当前音轨允许的最大重试次数（0 = 不重试）
     */
    @NonNull
    public static State initial(int maxRetries) {
        return new State(-1L, Source.NONE, Contract.NONE, 0, maxRetries);
    }

    /**
     * 网络行发布许可：仅当来源决策为网络。
     */
    public static boolean mayPublishNetwork(@NonNull State state) {
        return state.source == Source.NETWORK;
    }

    /**
     * 是否应安排下一次曲内重试：失败次数未达上限。
     */
    public static boolean shouldRetry(@NonNull State state) {
        return state.failedAttempts < state.maxRetries;
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
        return switch (event.type) {
            case TRACK_CHANGED -> trackChanged(state, event.trackId);
            case AD_OR_UNKNOWN -> adOrUnknown(state);
            case PLAYING -> playing(state);
            case STOPPED -> stopped(state);
            case FETCH_SUCCEEDED, RETRY_SUCCEEDED -> fetchSucceeded(state, event.trackId);
            case FETCH_FAILED -> fetchFailed(state, event.trackId);
            case FETCH_EMPTY -> fetchEmpty(state, event.trackId);
        };
    }

    /**
     * 切歌：进入网络拉取等待期；契约 {@link Contract#WAITING}。
     */
    @NonNull
    private static State trackChanged(@NonNull State state, long id) {
        if (state.trackId == id && state.contract == Contract.WAITING) return state;
        return new State(id, Source.NONE, Contract.WAITING, 0, state.maxRetries);
    }

    /**
     * 广告 / 无有效音轨：清空音轨、来源与契约。
     */
    @NonNull
    private static State adOrUnknown(@NonNull State state) {
        if (state.trackId == -1L && state.source == Source.NONE && state.contract == Contract.NONE) {
            return state;
        }
        return new State(-1L, Source.NONE, Contract.NONE, 0, state.maxRetries);
    }

    /**
     * 恢复播放：按音轨契约恢复来源——网络就绪回网络；
     * 契约已定为无歌词时保持无来源。
     */
    @NonNull
    private static State playing(@NonNull State state) {
        Source resumed;
        switch (state.contract) {
            case NETWORK:
                resumed = Source.NETWORK;
                break;
            case WAITING:
                resumed = Source.NONE;
                break;
            default:
                return state;
        }
        if (state.source == resumed) return state;
        return new State(state.trackId, resumed, state.contract, state.failedAttempts, state.maxRetries);
    }

    /**
     * 停止：清空当前发布来源（停发网络行），保留音轨契约，
     * 同曲恢复播放时由 {@link #playing(State)} 恢复来源。
     */
    @NonNull
    private static State stopped(@NonNull State state) {
        if (state.source == Source.NONE) return state;
        return new State(state.trackId, Source.NONE, state.contract, state.failedAttempts, state.maxRetries);
    }

    /**
     * 网络歌词就绪：非本音轨时忽略（晚到响应不接管）。
     */
    @NonNull
    private static State fetchSucceeded(@NonNull State state, long id) {
        if (state.trackId != id) return state;
        if (state.source == Source.NETWORK && state.contract == Contract.NETWORK) return state;
        return new State(state.trackId, Source.NETWORK, Contract.NETWORK, 0, state.maxRetries);
    }

    /**
     * 网络请求失败：当前音轨且未成功时累加失败次数（由 {@link #shouldRetry(State)}
     * 决定是否继续曲内重试 / 最终放弃），来源保持无发布（等待重试或保持空白）；
     * 网络已就绪时晚到失败不改变决策。
     */
    @NonNull
    private static State fetchFailed(@NonNull State state, long id) {
        if (state.trackId != id) return state;
        if (state.contract == Contract.NETWORK) return state;
        if (state.failedAttempts == state.maxRetries) return state;
        return new State(state.trackId, Source.NONE, Contract.WAITING, state.failedAttempts + 1, state.maxRetries);
    }

    /**
     * 网络结果为空（无歌词 / 纯音乐 / 404）：契约定为无歌词并保持空白；
     * 网络已就绪时忽略（不改变既有决策）。
     */
    @NonNull
    private static State fetchEmpty(@NonNull State state, long id) {
        if (state.trackId != id) return state;
        if (state.contract == Contract.NETWORK) return state;
        if (state.source == Source.NONE && state.contract == Contract.NONE) return state;
        return new State(state.trackId, Source.NONE, Contract.NONE, 0, state.maxRetries);
    }
}
