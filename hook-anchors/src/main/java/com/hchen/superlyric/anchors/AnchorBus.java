/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.anchors;

import androidx.annotation.NonNull;

import com.hchen.hooktool.log.AndroidLog;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 一个 anchor 的监听者集合
 *
 * 同一事件按注册顺序同步派发；单个监听者异常不会中断其余监听者或宿主。
 * 新监听者会收到最后一次事件，避免安装时的同步 capture 丢失。
 */
public final class AnchorBus<T> {
    private static final String TAG = "AnchorBus";

    private final CopyOnWriteArrayList<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private volatile T last;

    @NonNull
    public AutoCloseable subscribe(@NonNull Consumer<T> listener) {
        listeners.add(listener);
        T current = last;
        if (current != null) {
            try {
                listener.accept(current);
            } catch (Throwable t) {
                AndroidLog.logE(TAG, "Anchor listener failed on replay", t);
            }
        }
        return () -> listeners.remove(listener);
    }

    public void emit(@NonNull T event) {
        last = event;
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Throwable t) {
                AndroidLog.logE(TAG, "Anchor listener failed", t);
            }
        }
    }
}
