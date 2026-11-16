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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.cider;

import androidx.annotation.Nullable;

import com.hchen.superlyric.kernel.decode.PayloadLimits;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 原始歌词按曲目与来源暂存，协程恢复时仍保留最初请求的曲目标识。
 *
 * <p>自动模式只重放宿主最终选择的来源；切换选择后，旧请求不得覆盖缓存。
 */
final class CiderRawStore {
    private static final int MAX_ENTRIES = 30;
    private static final int MAX_CHARACTERS = PayloadLimits.MAX_PAYLOAD_BYTES / Character.BYTES;

    enum Source { APPLE, MUSIXMATCH, NETEASE, COMMUNITY }

    record Request(String catalogId, Source source, long selectionEpoch, boolean enhanced) {
    }

    record Key(String catalogId, Source source) {
    }

    private record Payload(List<String> texts, int characters, boolean enhanced) {
    }

    private final ThreadLocal<Deque<Request>> frames = new ThreadLocal<>();
    private final Map<Object, Request> continuations = new WeakHashMap<>();
    private final LinkedHashMap<Key, Payload> payloads = new LinkedHashMap<>(16, 0.75f, true);
    private Source selectedSource;
    private boolean initialized;
    private long selectionEpoch;
    private int storedCharacters;

    synchronized boolean setSelection(@Nullable Source source) {
        if (!initialized) {
            initialized = true;
            selectedSource = source;
            return false;
        }
        if (selectedSource == source) return false;
        selectedSource = source;
        selectionEpoch++;
        return true;
    }

    void enter(@Nullable String catalogId, Source source, @Nullable Object continuation, boolean enhanced) {
        Request request;
        synchronized (this) {
            if (catalogId != null && !catalogId.isBlank()) {
                request = new Request(catalogId, source, selectionEpoch, enhanced);
            } else {
                Request resumed = continuations.get(continuation);
                request = resumed != null && resumed.source() == source
                    ? resumed : new Request("", source, selectionEpoch, enhanced);
            }
        }
        Deque<Request> stack = frames.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            frames.set(stack);
        }
        stack.addLast(request);
    }

    @Nullable
    Request current() {
        Deque<Request> stack = frames.get();
        Request request = stack == null ? null : stack.peekLast();
        return request != null && !request.catalogId().isBlank() ? request : null;
    }

    synchronized void bind(Object continuation) {
        Request request = current();
        if (request != null) continuations.put(continuation, request);
    }

    void exit() {
        Deque<Request> stack = frames.get();
        if (stack == null) return;
        stack.removeLast();
        if (stack.isEmpty()) frames.remove();
    }

    void capture(@Nullable Request request, List<String> texts) {
        if (request == null || request.catalogId().isBlank()) return;
        List<String> candidates = List.copyOf(texts.stream().filter(Objects::nonNull)
            .filter(text -> !text.isBlank()).distinct().collect(Collectors.toList()));
        long characters = candidates.stream().mapToLong(String::length).sum();
        if (candidates.isEmpty() || characters > MAX_CHARACTERS) return;
        synchronized (this) {
            if (request.selectionEpoch() != selectionEpoch) return;
            Key key = new Key(request.catalogId(), request.source());
            Payload previous = payloads.get(key);
            boolean enhanced = request.enhanced();
            // 宿主增强 TTML 失败后会请求普通 TTML，模块仍须优先尝试未经裁切的增强原文。
            if (request.source() == Source.APPLE && previous != null && previous.enhanced() && !enhanced) {
                candidates = List.copyOf(Stream.concat(previous.texts().stream(), candidates.stream())
                    .distinct().collect(Collectors.toList()));
                characters = candidates.stream().mapToLong(String::length).sum();
                if (characters > MAX_CHARACTERS) return;
                enhanced = true;
            }
            payloads.put(key, new Payload(candidates, (int) characters, enhanced));
            if (previous != null) storedCharacters -= previous.characters();
            storedCharacters += (int) characters;
            while (payloads.size() > MAX_ENTRIES || storedCharacters > MAX_CHARACTERS) {
                var entries = payloads.entrySet().iterator();
                storedCharacters -= entries.next().getValue().characters();
                entries.remove();
            }
        }
    }

    synchronized List<String> selected(@Nullable String catalogId, @Nullable Source resolvedSource) {
        if (catalogId == null || catalogId.isBlank() || resolvedSource == null) return List.of();
        if (selectedSource != null && selectedSource != resolvedSource) return List.of();
        Payload payload = payloads.get(new Key(catalogId, resolvedSource));
        return payload == null ? List.of() : payload.texts();
    }

    synchronized void invalidate(@Nullable String catalogId, @Nullable Source source) {
        if (catalogId == null || catalogId.isBlank()) return;
        if (source != null) {
            Payload removed = payloads.remove(new Key(catalogId, source));
            if (removed != null) storedCharacters -= removed.characters();
            return;
        }
        var entries = payloads.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, Payload> entry = entries.next();
            if (catalogId.equals(entry.getKey().catalogId())) {
                storedCharacters -= entry.getValue().characters();
                entries.remove();
            }
        }
    }
}
