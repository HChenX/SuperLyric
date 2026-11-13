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
package com.hchen.superlyric.core;

import android.os.IBinder;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 两代 Binder 订阅者共用的配额与慢链账本
 *
 * <p>全局上限 64 个、每 uid 上限 16 个、单次回调超时 500ms、连续 3 次超时驱逐。
 * 只记账，不持有回调接口：死亡回调、广播与载荷由调用方负责，两代广播器各自保留自己的
 * 线程池与线上格式。
 */
final class SubscriberBudget {

    static final long CALL_TIMEOUT_MS = 500L;
    static final int MAX_SUBSCRIBERS = 64;
    static final int MAX_PER_UID = 16;
    static final int MAX_CONSECUTIVE_TIMEOUTS = 3;

    enum Admission {
        OK,
        DUPLICATE,
        AT_GLOBAL_LIMIT,
        AT_UID_LIMIT,
    }

    private final Object lock = new Object();
    private final Map<IBinder, Integer> subscriberUids = new HashMap<>();
    private final Map<Integer, Integer> uidCounts = new HashMap<>();
    private final Map<IBinder, Integer> consecutiveTimeouts = new HashMap<>();

    /**
     * 登记一个订阅者；已登记时返回 {@link Admission#DUPLICATE} 且不重复计数
     */
    @NonNull
    Admission admit(@NonNull IBinder subscriber, int uid) {
        synchronized (lock) {
            if (subscriberUids.containsKey(subscriber)) return Admission.DUPLICATE;
            if (subscriberUids.size() >= MAX_SUBSCRIBERS) return Admission.AT_GLOBAL_LIMIT;
            if (uidCounts.getOrDefault(uid, 0) >= MAX_PER_UID) return Admission.AT_UID_LIMIT;
            subscriberUids.put(subscriber, uid);
            uidCounts.merge(uid, 1, Integer::sum);
            return Admission.OK;
        }
    }

    /**
     * 注销订阅者并回退配额
     *
     * @return 确实移除了一个订阅者时为 {@code true}
     */
    boolean release(@NonNull IBinder subscriber) {
        synchronized (lock) {
            Integer uid = subscriberUids.remove(subscriber);
            if (uid == null) return false;
            uidCounts.computeIfPresent(uid, (key, count) -> count <= 1 ? null : count - 1);
            consecutiveTimeouts.remove(subscriber);
            return true;
        }
    }

    /** 一次成功回调会重置连续超时计数 */
    void recordSuccess(@NonNull IBinder subscriber) {
        synchronized (lock) {
            consecutiveTimeouts.remove(subscriber);
        }
    }

    /**
     * 记录一次回调超时
     *
     * @return 连续超时次数；达到 {@link #MAX_CONSECUTIVE_TIMEOUTS} 时调用方应驱逐该订阅者
     */
    int recordTimeout(@NonNull IBinder subscriber) {
        synchronized (lock) {
            if (!subscriberUids.containsKey(subscriber)) return 1;
            return consecutiveTimeouts.merge(subscriber, 1, Integer::sum);
        }
    }
}
