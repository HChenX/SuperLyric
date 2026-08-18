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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
    private static final long MAX_PROVIDER_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_PROVIDER_FILES = 256;
    private static final long MAX_CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    /**
     * 单个歌词原始响应上限；正常歌词响应远低于此值。
     */
    public static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    private static final byte[] VERSION_PREFIX = ("{\"v\":" + CACHE_VERSION + ",\"d\":")
        .getBytes(StandardCharsets.UTF_8);
    private static final String[] ONLINE_PROVIDERS = {"Netease", "Hihonor", "Spotify"};
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
        if (data.length == 0 || data.length > MAX_PAYLOAD_BYTES) {
            XposedLog.logW(TAG, "Reject lyric cache payload: provider=" + provider + ", bytes=" + data.length);
            return;
        }
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
                    XposedLog.logW(TAG, "Failed to migrate old lyric cache", e);
                }
            }
            File temp = null;
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return;
                }
                temp = File.createTempFile(file.getName() + ".", ".tmp", parent);
                Files.write(temp.toPath(), wrap(data));
                try {
                    Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                pruneProviderLocked(ctx, provider);
            } catch (IOException | SecurityException e) {
                XposedLog.logW(TAG, "Failed to write lyric cache", e);
            } finally {
                if (temp != null && temp.exists()) {
                    deleteCachePath(temp);
                }
            }
        }
    }

    /**
     * 封装原始响应为带版本字段的格式：{@code {"v":1,"d":<原始响应>}}。
     */
    @NonNull
    private static byte[] wrap(@NonNull byte[] data) {
        byte[] suffix = new byte[]{'}'};
        byte[] framed = new byte[VERSION_PREFIX.length + data.length + suffix.length];
        System.arraycopy(VERSION_PREFIX, 0, framed, 0, VERSION_PREFIX.length);
        System.arraycopy(data, 0, framed, VERSION_PREFIX.length, data.length);
        System.arraycopy(suffix, 0, framed, VERSION_PREFIX.length + data.length, suffix.length);
        return framed;
    }

    /**
     * 从带版本字段的文件字节中解出原始响应；格式不符返回 {@code null}。
     */
    @Nullable
    private static byte[] unpack(@NonNull byte[] data) {
        int len = data.length;
        int head = VERSION_PREFIX.length;
        if (len <= head + 1 || len > head + MAX_PAYLOAD_BYTES + 1) return null;
        for (int i = 0; i < head; i++) {
            if (data[i] != VERSION_PREFIX[i]) return null;
        }
        if (data[len - 1] != '}') return null;
        return Arrays.copyOfRange(data, head, len - 1);
    }

    /**
     * 文件是否为当前版本格式（内容合法且带 v 字段）。
     */
    private static boolean hasVersion(@NonNull File file) {
        if (!isReadableCacheSize(file)) return false;
        try {
            return unpack(Files.readAllBytes(file.toPath())) != null;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static boolean isReadableCacheSize(@NonNull File file) {
        long length = file.length();
        return length > VERSION_PREFIX.length + 1L
            && length <= VERSION_PREFIX.length + MAX_PAYLOAD_BYTES + 1L;
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
        synchronized (WRITE_LOCK) {
            try {
                if (!isReadableCacheSize(file)) {
                    Files.deleteIfExists(file.toPath());
                    return null;
                }
                byte[] payload = unpack(Files.readAllBytes(file.toPath()));
                if (payload == null) {
                    Files.deleteIfExists(file.toPath());
                    return null;
                }
                return payload;
            } catch (IOException | SecurityException e) {
                XposedLog.logW(TAG, "Failed to read lyric cache", e);
                return null;
            }
        }
    }

    /**
     * 删除磁盘缓存；不存在或删除失败时静默忽略。
     */
    public static void delete(@Nullable Context context, @NonNull String provider, @NonNull String key) {
        Context ctx = resolveContext(context);
        if (ctx == null) return;

        File file = getFile(ctx, provider, key);
        if (file == null) return;
        synchronized (WRITE_LOCK) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException | SecurityException e) {
                XposedLog.logW(TAG, "Failed to delete lyric cache", e);
            }
        }
    }

    private static void pruneProviderLocked(@NonNull Context context, @NonNull String provider) {
        String providerDir = sanitizeSegment(provider);
        if (providerDir == null) return;
        File root = new File(new File(context.getCacheDir(), CACHE_ROOT), providerDir);
        if (!root.isDirectory() || hasSymbolicLinkBetween(root, context.getCacheDir())) return;

        List<File> cacheFiles = new ArrayList<>();
        collectCacheFiles(root, cacheFiles);
        long now = System.currentTimeMillis();
        for (File file : new ArrayList<>(cacheFiles)) {
            long age = now - file.lastModified();
            long maxAge = file.getName().endsWith(".tmp") ? TEMP_MAX_AGE_MS : MAX_CACHE_AGE_MS;
            if (age > maxAge && deleteCachePath(file)) cacheFiles.remove(file);
        }
        cacheFiles.removeIf(file -> !file.isFile() || file.getName().endsWith(".tmp"));
        cacheFiles.sort(Comparator.comparingLong(File::lastModified));
        long total = 0L;
        for (File file : cacheFiles) {
            long length = Math.max(0L, file.length());
            total = length > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + length;
        }
        int remainingFiles = cacheFiles.size();
        int index = 0;
        while ((remainingFiles > MAX_PROVIDER_FILES || total > MAX_PROVIDER_BYTES)
            && index < cacheFiles.size()) {
            File file = cacheFiles.get(index++);
            long length = Math.max(0L, file.length());
            if (deleteCachePath(file)) {
                remainingFiles--;
                total = Math.max(0L, total - length);
            }
        }
    }

    private static void collectCacheFiles(@NonNull File directory, @NonNull List<File> result) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) collectCacheFiles(child, result);
            else if (child.isFile() && (child.getName().endsWith(".json") || child.getName().endsWith(".tmp"))) {
                result.add(child);
            }
        }
    }

    /**
     * 清空全部在线歌词提供者的缓存命名空间。
     *
     * @param context 宿主 Context；为 {@code null} 时尝试解析当前 Application
     * @return 所有提供者均清理成功时返回 {@code true}；Context 不可用、路径非法或任一项删除失败时返回 {@code false}
     */
    public static boolean clearOnlineProviderCaches(@Nullable Context context) {
        Context ctx = resolveContext(context);
        if (ctx == null) return false;

        boolean success = true;
        synchronized (WRITE_LOCK) {
            for (String provider : ONLINE_PROVIDERS) {
                if (!clearProviderCacheLocked(ctx, provider)) {
                    success = false;
                }
            }
        }
        return success;
    }

    /**
     * 递归清空指定提供者的缓存命名空间，用于版本升级后的整体失效。
     * 删除范围严格限制在合法 provider 目录内，不跟随符号链接。
     *
     * @param context  宿主 Context；为 {@code null} 时尝试解析当前 Application
     * @param provider 提供者命名空间
     * @return 命名空间不存在或全部内容删除成功时返回 {@code true}
     */
    public static boolean clearProviderCache(@Nullable Context context, @NonNull String provider) {
        Context ctx = resolveContext(context);
        if (ctx == null) return false;

        synchronized (WRITE_LOCK) {
            return clearProviderCacheLocked(ctx, provider);
        }
    }

    private static boolean clearProviderCacheLocked(@NonNull Context context, @NonNull String provider) {
        String providerDir = sanitizeSegment(provider);
        if (providerDir == null) return false;

        File cacheDir = context.getCacheDir();
        File root = new File(cacheDir, CACHE_ROOT);
        if (hasSymbolicLinkBetween(root, cacheDir)) return false;
        File dir = new File(root, providerDir);
        if (!dir.exists()) return true;
        if (hasSymbolicLinkBetween(dir, cacheDir)) {
            return Files.isSymbolicLink(dir.toPath()) && deleteCachePath(dir);
        }
        if (!dir.isDirectory()) {
            return deleteCachePath(dir);
        }
        return deleteCacheTree(dir, true);
    }

    private static boolean deleteCacheTree(@NonNull File file, boolean keepRoot) {
        if (Files.isSymbolicLink(file.toPath())) {
            return deleteCachePath(file);
        }

        boolean success = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                XposedLog.logW(TAG, "Failed to list lyric cache directory");
                return false;
            }
            for (File child : children) {
                if (!deleteCacheTree(child, false)) {
                    success = false;
                }
            }
        }
        if (!keepRoot && !deleteCachePath(file)) {
            success = false;
        }
        return success;
    }

    private static boolean deleteCachePath(@NonNull File file) {
        try {
            Files.deleteIfExists(file.toPath());
            return true;
        } catch (IOException | SecurityException e) {
            XposedLog.logW(TAG, "Failed to clear lyric cache", e);
            return false;
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

        File file = new File(
            new File(context.getCacheDir(), CACHE_ROOT),
            providerDir + File.separator + keyPath + ".json"
        );
        return isSafeCachePath(context, providerDir, file) ? file : null;
    }

    private static boolean hasSymbolicLinkBetween(@NonNull File path, @NonNull File boundary) {
        File current = path;
        while (current != null && !current.equals(boundary)) {
            if (current.exists() && Files.isSymbolicLink(current.toPath())) return true;
            current = current.getParentFile();
        }
        return current == null;
    }

    private static boolean isSafeCachePath(@NonNull Context context, @NonNull String provider,
                                           @NonNull File target) {
        try {
            File rootPath = new File(context.getCacheDir(), CACHE_ROOT);
            if (rootPath.exists() && Files.isSymbolicLink(rootPath.toPath())) return false;
            File root = rootPath.getCanonicalFile();
            File providerRoot = new File(root, provider).getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            String prefix = providerRoot.getPath() + File.separator;
            if (!canonicalTarget.getPath().startsWith(prefix)) return false;

            File current = target.getParentFile();
            while (current != null && !current.equals(root)) {
                if (current.exists() && Files.isSymbolicLink(current.toPath())) return false;
                current = current.getParentFile();
            }
            return current != null;
        } catch (IOException | SecurityException e) {
            return false;
        }
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
        if (segment.equals(".") || segment.equals("..")) return null;
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
