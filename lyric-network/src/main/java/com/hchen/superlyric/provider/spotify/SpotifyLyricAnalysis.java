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
package com.hchen.superlyric.provider.spotify;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.utils.LyricCacheStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Spotify 歌词数据链路辅助：会话头捕获与私有歌词接口拉取
 * <p>
 * Inspired from tomakino/LyricProvider/spotify-music.
 *
 */
public final class SpotifyLyricAnalysis {
    private static final String TAG = "SpotifyLyricAnalysis";

    private static final String BASE_URL = "https://gae2-spclient.spotify.com/color-lyrics/v2/track/";

    /**
     * 私有歌词接口所需的 4 个关键会话头（不可变）
     */
    private static final List<String> KEYS_REQUIRED = List.of(
        "authorization",
        "client-token",
        "user-agent",
        "x-client-id"
    );

    public static final class HeaderSnapshot {
        @NonNull
        final Map<String, String> headers;
        public final boolean valid;

        private HeaderSnapshot(@NonNull Map<String, String> headers, boolean valid) {
            this.headers = headers;
            this.valid = valid;
        }
    }

    private static final AtomicReference<HeaderSnapshot> sHeaders =
        new AtomicReference<>(new HeaderSnapshot(Collections.emptyMap(), false));

    private static volatile boolean sHeaderCompleteLogged = false;

    private static final Map<String, Call> activeCalls = new ConcurrentHashMap<>();

    private static final OkHttpClient client = new OkHttpClient.Builder()
        // 比 QQ / 酷狗 / 网易云的 4/6/12 宽松：本家取回后还要跑一遍 protobuf 分析，
        // 服务端也更慢
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build();

    private SpotifyLyricAnalysis() {
    }

    private static boolean isKeyRequired(@NonNull String keyLowercase) {
        return KEYS_REQUIRED.contains(keyLowercase);
    }

    /**
     * 从同一批 okhttp Headers 构造参数发布完整会话头快照
     *
     * <p>只有 4 个关键头在同一批里齐全时才发布；快照不可变，供并发取词读取。
     */
    public static void updateHeaders(@NonNull String[] namesAndValues) {
        Map<String, String> captured = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            String name = namesAndValues[i];
            String value = namesAndValues[i + 1];
            if (name == null || value == null) continue;
            String lower = name.toLowerCase(Locale.ENGLISH);
            if (isKeyRequired(lower)) captured.put(lower, value);
        }
        if (!captured.keySet().containsAll(KEYS_REQUIRED)) return;

        Map<String, String> immutable = Collections.unmodifiableMap(captured);
        while (true) {
            HeaderSnapshot current = sHeaders.get();
            if (current.valid && current.headers.equals(immutable)) return;
            HeaderSnapshot next = new HeaderSnapshot(immutable, true);
            if (sHeaders.compareAndSet(current, next)) {
                if (!sHeaderCompleteLogged) {
                    sHeaderCompleteLogged = true;
                    AndroidLog.logI(TAG, "All required session headers captured");
                }
                return;
            }
        }
    }

    @Nullable
    public static HeaderSnapshot currentHeaders() {
        HeaderSnapshot snapshot = sHeaders.get();
        return snapshot.valid ? snapshot : null;
    }

    public static void invalidate(@NonNull HeaderSnapshot requestSnapshot) {
        sHeaders.compareAndSet(requestSnapshot,
            new HeaderSnapshot(Collections.emptyMap(), false));
    }

    /**
     * 拉取指定音轨的歌词原始响应（protobuf 编码）
     *
     * @param id 音轨标识（已剥离 spotify:track: 前缀）
     * @return 歌词接口原始响应字节
     * @throws LyricNotFoundException 接口 404（无歌词）
     * @throws IOException            网络错误 / 非成功状态码
     */
    @NonNull
    public static byte[] fetchLyric(@NonNull String id, @NonNull HeaderSnapshot headers) throws IOException {
        String url = BASE_URL + id
            + "?vocalRemoval=true&clientLanguage=" + Locale.getDefault().toLanguageTag()
            + "&preview=false";

        Request.Builder builder = new Request.Builder()
            .url(url)
            .get()
            // 请求头切到 protobuf 编码：同一接口的 JSON 响应不带逐字（syllables）数据，
            // 仅 protobuf 响应携带逐字时间轴
            .addHeader("accept", "application/protobuf")
            .addHeader("content-type", "application/protobuf")
            .addHeader("app-platform", "Android");
        headers.headers.forEach(builder::addHeader);

        Request request = builder.build();
        Call call = client.newCall(request);
        Call previous = activeCalls.put(id, call);
        if (previous != null) previous.cancel();
        try (Response response = call.execute()) {
            int code = response.code();
            if (code == 401 || code == 403) {
                invalidate(headers);
                throw new AuthenticationException(code);
            }
            if (code == 404) {
                throw new LyricNotFoundException("No lyric found for " + id);
            }
            if (!response.isSuccessful()) {
                throw new HttpStatusException(code);
            }

            long contentLength = response.body().contentLength();
            if (contentLength > LyricCacheStore.MAX_PAYLOAD_BYTES) {
                throw new OversizeException(LyricCacheStore.MAX_PAYLOAD_BYTES);
            }
            return readLimited(response.body().byteStream(), LyricCacheStore.MAX_PAYLOAD_BYTES);
        } finally {
            activeCalls.remove(id, call);
        }
    }

    public static void cancelExcept(@Nullable String currentId) {
        activeCalls.forEach((id, call) -> {
            if (currentId == null || !currentId.equals(id)) call.cancel();
        });
    }

    @NonNull
    private static byte[] readLimited(@NonNull InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - total) throw new OversizeException(maxBytes);
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }
}
