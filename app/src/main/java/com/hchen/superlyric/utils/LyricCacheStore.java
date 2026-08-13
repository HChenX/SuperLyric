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
package com.hchen.superlyric.utils;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 通用歌词磁盘缓存储存器。
 * <p>
 * 按提供者命名空间独立存储接口原始响应（JSON 或 protobuf 字节），
 * 直接写入宿主 cache 目录：{@code cacheDir/superlyric/lyric/{provider}/{key}.json}。
 * 文件带版本字段（{@code {"v":1,"d":<原始响应>}}），版本不符视为未命中并删除。
 * key 仅允许 {@code [A-Za-z0-9._-]} 与 {@code '/'} 分隔，杜绝路径穿越。
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
public final class LyricCacheStore {
    private static final String TAG = "LyricCacheStore";
    private static final String CACHE_ROOT = "superlyric" + File.separator + "lyric";
    private static final int CACHE_VERSION = 1;
    private static final String VERSION_MARKER = "{\"v\":1,\"d\":";
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*");
    /**
     * 写锁：同 key 并发 put 时串行化"写 tmp + 原子替换"，避免内容交错损坏。
     */
    private static final Object WRITE_LOCK = new Object();

    private LyricCacheStore() {
    }

    /**
     * 当前缓存格式版本号；缓存写 / 读时与文件内版本比对，不匹配视为失效。
     */
    public static int cacheVersion() {
        return CACHE_VERSION;
    }

    /**
     * 将接口返回的 JSON 文本（UTF-8）写入磁盘缓存。
     *
     * @param provider 提供者命名空间（如 Spotify / Netease），须为纯名称
     * @param key      缓存键（可为 {locale}/{id} 或纯 id）
     * @param json     接口返回的原始 JSON 字符串
     */
    public static void put(@Nullable Context context, @NonNull String provider, @NonNull String key, @NonNull String json) {
        put(context, provider, key, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将接口返回的原始响应字节（JSON 或 protobuf）写入磁盘缓存。
     * <p>
     * 写入前若既有文件为无版本字段的旧格式缓存，先删除再写新格式（迁移）。
     *
     * @param provider 提供者命名空间（如 Spotify / Netease），须为纯名称
     * @param key      缓存键（可为 {locale}/{id} 或纯 id）
     * @param data     接口返回的原始响应字节
     */
    public static void put(@Nullable Context context, @NonNull String provider, @NonNull String key, @NonNull byte[] data) {
        Context ctx = resolveContext(context);
        if (ctx == null) return;

        File file = getFile(ctx, provider, key);
        if (file == null) return;
        synchronized (WRITE_LOCK) {
            // 旧格式（无 v 字段）迁移：先删，避免脏数据在版本升级后继续被命中
            if (file.isFile() && !hasVersion(file)) {
                try {
                    Files.delete(file.toPath());
                } catch (IOException e) {
                    XposedLog.logW(TAG, "Failed to migrate old cache: " + file.getAbsolutePath(), e);
                }
            }
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return;
                }
                // 先写临时文件再原子替换：避免进程被杀时留下半截 JSON，缓存只读到完整内容
                File temp = parent != null
                    ? new File(parent, file.getName() + ".tmp")
                    : new File(file.getName() + ".tmp");
                byte[] framed = wrap(data);
                Files.write(temp.toPath(), framed);
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                XposedLog.logW(TAG, "Failed to write lyric cache: " + file.getAbsolutePath(), e);
            } catch (Throwable t) {
                XposedLog.logW(TAG, "Failed to write lyric cache: " + file.getAbsolutePath(), t);
            }
        }
    }

    /**
     * 封装原始响应为带版本字段的格式：{@code {"v":1,"d":<原始响应>}}。
     */
    @NonNull
    private static byte[] wrap(@NonNull byte[] data) {
        byte[] prefix = VERSION_MARKER.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = new byte[]{'}'};
        byte[] framed = new byte[prefix.length + data.length + suffix.length];
        System.arraycopy(prefix, 0, framed, 0, prefix.length);
        System.arraycopy(data, 0, framed, prefix.length, data.length);
        System.arraycopy(suffix, 0, framed, prefix.length + data.length, suffix.length);
        return framed;
    }

    /**
     * 从带版本字段的文件字节中解出原始响应；格式不符返回 {@code null}。
     */
    @Nullable
    private static byte[] unpack(@NonNull byte[] data) {
        int len = data.length;
        int head = VERSION_MARKER.length();
        if (len <= head + 1) return null;
        for (int i = 0; i < head; i++) {
            if (data[i] != VERSION_MARKER.getBytes(StandardCharsets.UTF_8)[i]) return null;
        }
        if (data[len - 1] != '}') return null;
        return Arrays.copyOfRange(data, head, len - 1);
    }

    /**
     * 文件是否为当前版本格式（内容合法且带 v 字段）。
     */
    private static boolean hasVersion(@NonNull File file) {
        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            return false;
        }
        return unpack(data) != null;
    }

    /**
     * 读取磁盘缓存，未命中返回 {@code null}。
     */
    @Nullable
    public static String get(@Nullable Context context, @NonNull String provider, @NonNull String key) {
        byte[] data = getBytes(context, provider, key);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 读取磁盘缓存原始字节（protobuf 响应），未命中或版本不符返回 {@code null}。
     */
    @Nullable
    public static byte[] getBytes(@Nullable Context context, @NonNull String provider, @NonNull String key) {
        Context ctx = resolveContext(context);
        if (ctx == null) return null;

        File file = getFile(ctx, provider, key);
        if (file == null || !file.isFile()) return null;
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            byte[] payload = unpack(data);
            // 旧格式（无 v 字段）或损坏内容：删除并视为未命中，由调用方回退网络
            if (payload == null) {
                Files.delete(file.toPath());
                return null;
            }
            return payload;
        } catch (IOException e) {
            XposedLog.logW(TAG, "Failed to read lyric cache: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 删除磁盘缓存；不存在或删除失败时静默忽略。
     */
    public static void delete(@Nullable Context context, @NonNull String provider, @NonNull String key) {
        Context ctx = resolveContext(context);
        if (ctx == null) return;

        File file = getFile(ctx, provider, key);
        if (file == null || !file.isFile()) return;
        try {
            Files.delete(file.toPath());
        } catch (IOException e) {
            XposedLog.logW(TAG, "Failed to delete lyric cache: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 清空指定提供者的全部缓存文件（保留目录），用于版本升级后的整体失效。
     */
    public static void clearProviderCache(@Nullable Context context, @NonNull String provider) {
        Context ctx = resolveContext(context);
        if (ctx == null) return;

        String providerDir = sanitizeSegment(provider);
        if (providerDir == null) return;
        File dir = new File(new File(ctx.getCacheDir(), CACHE_ROOT), providerDir);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                try {
                    Files.delete(file.toPath());
                } catch (IOException e) {
                    XposedLog.logW(TAG, "Failed to clear lyric cache: " + file.getAbsolutePath(), e);
                }
            }
        }
    }

    /**
     * 计算缓存文件路径；provider / key 非法时返回 {@code null}。
     */
    @Nullable
    private static File getFile(@NonNull Context context, @NonNull String provider, @NonNull String key) {
        String providerDir = sanitizeSegment(provider);
        String keyPath = sanitizeKey(key);
        if (providerDir == null || keyPath == null) return null;

        return new File(
            new File(context.getCacheDir(), CACHE_ROOT),
            providerDir + File.separator + keyPath + ".json"
        );
    }

    /**
     * 解析宿主 Context：优先使用传入的宿主 Application Context，
     * 缺失时（onApplicationCreated 未触发的兜底场景）反射获取当前 Application。
     */
    @Nullable
    private static Context resolveContext(@Nullable Context context) {
        if (context != null) return context.getApplicationContext();
        try {
            @SuppressLint("PrivateApi")
            Object app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 清洗单个路径段：仅保留 {@code [A-Za-z0-9._-]}，非法返回 {@code null}。
     */
    @Nullable
    private static String sanitizeSegment(@NonNull String segment) {
        return SAFE_SEGMENT.matcher(segment).matches() ? segment : null;
    }

    /**
     * 清洗 key：允许 {@code [A-Za-z0-9._-]} 与多层 {@code '/'} 分隔；
     * 拒绝空段、{@code ..} 与首尾 {@code '/'}。
     */
    @Nullable
    private static String sanitizeKey(@NonNull String key) {
        if (!SAFE_KEY.matcher(key).matches()) return null;
        if (Arrays.stream(key.split("/")).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            return null;
        }
        return key.replace('/', File.separatorChar);
    }

    /**
     * 获取当前默认语言的 locale 段（如 zh-CN），用作按语言隔离的缓存键前缀。
     */
    @NonNull
    public static String currentLocaleTag() {
        return Locale.getDefault().toLanguageTag();
    }
}
