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

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.publisher.AbsPublisher;

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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 通用歌词磁盘缓存储存器。
 * <p>
 * 按提供者命名空间独立存储接口原始响应（JSON 或 protobuf 字节），
 * 直接写入宿主 cache 目录：{@code cacheDir/superlyric/lyric/{provider}/{key}.json}。
 * 文件带版本与长度字段（{@code {"v":2,"n":<len>,"d":<原始响应>}}），版本不符视为未命中并删除。
 * key 仅允许 {@code [A-Za-z0-9._-]} 与 {@code '/'} 分隔，杜绝路径穿越。
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
public final class LyricCacheStore {
    private static final String TAG = "LyricCacheStore";
    private static final String CACHE_ROOT = "superlyric" + File.separator + "lyric";
    private static final int CACHE_VERSION = 2;
    private static final long MAX_PROVIDER_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_PROVIDER_FILES = 1000;
    private static final int PRUNE_INTERVAL = 30;
    private static final long MAX_CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    /**
     * 单个歌词原始响应上限；正常歌词响应远低于此值。
     */
    public static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    /**
     * 帧头：{@code {"v":<版本>,"n":<载荷字节数>,"d":}。
     * <p>
     * 长度字段是**校验**用的：只靠"末尾一个 {@code }}"无法发现截断——载荷本身
     * （JSON、protobuf）里到处是 {@code }}，断在哪都可能恰好以它收尾。声明长度与
     * 帧内剩余空间必须精确吻合，见 {@link #unpack}
     */
    private static final String FRAME_HEAD = "{\"v\":" + CACHE_VERSION + ",\"n\":";
    private static final String FRAME_SEPARATOR = ",\"d\":";
    private static final byte[] FRAME_HEAD_BYTES = FRAME_HEAD.getBytes(StandardCharsets.UTF_8);
    private static final byte[] FRAME_SEPARATOR_BYTES = FRAME_SEPARATOR.getBytes(StandardCharsets.UTF_8);
    /** 帧的最小可能字节数：帧头 + 至少一位长度 + 分隔符 + 一个载荷字节 + 收尾 '}' */
    private static final long FRAME_MIN_BYTES =
        FRAME_HEAD_BYTES.length + 1L + FRAME_SEPARATOR_BYTES.length + 1L + 1L;
    /** {@link #MAX_PAYLOAD_BYTES} 的十进制位数 */
    private static final int MAX_LENGTH_DIGITS = String.valueOf(MAX_PAYLOAD_BYTES).length();
    /**
     * 帧的最大可能字节数：帧头 + 长度字段的最大位数 + 分隔符 + 上限载荷 + 收尾 '}'
     * <p>
     * 不能拿 {@link #FRAME_MIN_BYTES} 当基数再加大载荷：那是按**一位**长度字段算的，
     * 而接近上限的帧其长度字段有 7 位，于是合法的满额条目会被当成损坏文件删掉
     */
    private static final long FRAME_MAX_BYTES =
        FRAME_HEAD_BYTES.length + MAX_LENGTH_DIGITS + FRAME_SEPARATOR_BYTES.length
            + MAX_PAYLOAD_BYTES + 1L;
    private static final String[] ONLINE_PROVIDERS = {"Netease", "Kugou", "QQ", "Spotify"};
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*");
    private static final AtomicInteger sWriteCounter = new AtomicInteger(0);
    /**
     * 写锁：同 key 并发 put 时串行化"写 tmp + 原子替换"，避免内容交错损坏。
     */
    private static final Object WRITE_LOCK = new Object();
    /**
     * 存储自有的异步写线程：调用方无需自带执行器，且所有落盘串行执行。
     */
    private static final Executor WRITE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SuperLyric-LyricCache");
        thread.setDaemon(true);
        return thread;
    });

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
     * 异步将接口返回的 JSON 文本写入磁盘缓存，使用存储自有的串行写线程。
     *
     * @param context  宿主 Context；为 {@code null} 时自动解析
     * @param provider 提供者命名空间（如 Netease / Spotify）
     * @param key      缓存键
     * @param json     接口返回的原始 JSON 字符串
     */
    public static void putAsync(@Nullable Context context, @NonNull String provider, @NonNull String key, @NonNull String json) {
        putAsync(WRITE_EXECUTOR, context, provider, key, json);
    }

    /**
     * 异步将接口返回的原始响应字节写入磁盘缓存，使用存储自有的串行写线程。
     *
     * @param context  宿主 Context；为 {@code null} 时自动解析
     * @param provider 提供者命名空间（如 Netease / Spotify）
     * @param key      缓存键
     * @param data     接口返回的原始响应字节
     */
    public static void putAsync(@Nullable Context context, @NonNull String provider, @NonNull String key, @NonNull byte[] data) {
        putAsync(WRITE_EXECUTOR, context, provider, key, data);
    }

    /**
     * 异步将接口返回的 JSON 文本写入磁盘缓存。
     *
     * @param executor 执行器，为 {@code null} 时同步写入
     * @param context  宿主 Context；为 {@code null} 时自动解析
     * @param provider 提供者命名空间（如 Netease / Spotify）
     * @param key      缓存键
     * @param json     接口返回的原始 JSON 字符串
     */
    public static void putAsync(@Nullable Executor executor, @Nullable Context context,
                                @NonNull String provider, @NonNull String key, @NonNull String json) {
        putAsync(executor, context, provider, key, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 异步将接口返回的原始响应字节写入磁盘缓存。
     *
     * @param executor 执行器，为 {@code null} 时同步写入
     * @param context  宿主 Context；为 {@code null} 时自动解析
     * @param provider 提供者命名空间（如 Netease / Spotify）
     * @param key      缓存键
     * @param data     接口返回的原始响应字节
     */
    public static void putAsync(@Nullable Executor executor, @Nullable Context context,
                                @NonNull String provider, @NonNull String key, @NonNull byte[] data) {
        if (executor == null) {
            put(context, provider, key, data);
            return;
        }
        try {
            executor.execute(() -> put(context, provider, key, data));
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to dispatch async cache write, falling back to sync: " + t.getMessage());
            put(context, provider, key, data);
        }
    }

    /**
     * 将接口返回的原始响应字节（JSON 或 protobuf）写入磁盘缓存。
     * <p>
     * 写入只做"临时文件 + 原子改名（{@code REPLACE_EXISTING}）"，**不检查既有文件**：
     * 旧格式的迁移发生在读取侧——{@link #get} 发现版本不符就删掉并报未命中，
     * 下一次取词自然按新格式重写。
     *
     * @param provider 提供者命名空间（如 Spotify / Netease），须为纯名称
     * @param key      缓存键（可为 {locale}/{id} 或纯 id）
     * @param data     接口返回的原始响应字节
     */
    public static void put(@Nullable Context context, @NonNull String provider, @NonNull String key, @NonNull byte[] data) {
        if (data.length == 0 || data.length > MAX_PAYLOAD_BYTES) {
            AndroidLog.logW(TAG, "Reject lyric cache payload: provider=" + provider + ", bytes=" + data.length);
            return;
        }
        Context ctx = resolveContext(context);
        if (ctx == null) {
            AndroidLog.logW(TAG, "Cannot write cache, Context unresolved for provider=" + provider + ", key=" + key);
            return;
        }

        File file = getFile(ctx, provider, key);
        if (file == null) {
            AndroidLog.logW(TAG, "Cannot write cache, getFile returned null for provider=" + provider + ", key=" + key);
            return;
        }
        synchronized (WRITE_LOCK) {
            File temp = null;
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    AndroidLog.logW(TAG, "Failed to create directory: " + parent.getAbsolutePath());
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
                AndroidLog.logI(TAG, "Successfully cached lyric on disk: " + file.getAbsolutePath() + " (" + data.length + " bytes)");

                // 周期性节流：每写 PRUNE_INTERVAL 次才触发一次磁盘扫描与容量清理，避免每次遍历排序目录
                if (sWriteCounter.incrementAndGet() % PRUNE_INTERVAL == 0) {
                    AndroidLog.logD(TAG, "Triggering scheduled disk cache prune for provider=" + provider);
                    pruneProviderLocked(ctx, provider);
                }
            } catch (IOException | SecurityException e) {
                AndroidLog.logE(TAG, "Failed to write lyric cache: " + file.getAbsolutePath(), e);
            } finally {
                if (temp != null && temp.exists()) {
                    deleteCachePath(temp);
                }
            }
        }
    }

    /**
     * 封装原始响应为带版本与长度字段的帧：{@code {"v":<版本>,"n":<载荷字节数>,"d":<原始响应>}}。
     * <p>
     * 帧头由 {@link #CACHE_VERSION} 拼出来，文档里不写死版本号：写死过一次（v1），
     * 于是版本升到 2 之后这里就成了假话
     */
    @NonNull
    private static byte[] wrap(@NonNull byte[] data) {
        byte[] head = (FRAME_HEAD + data.length + FRAME_SEPARATOR).getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[head.length + data.length + 1];
        System.arraycopy(head, 0, framed, 0, head.length);
        System.arraycopy(data, 0, framed, head.length, data.length);
        framed[framed.length - 1] = '}';
        return framed;
    }

    /**
     * 从帧字节中解出原始响应；帧不完整、版本不符或声明长度与实长不符时返回 {@code null}。
     */
    @Nullable
    private static byte[] unpack(@NonNull byte[] data) {
        int cursor = FRAME_HEAD_BYTES.length;
        if (data.length <= cursor) return null;
        for (int i = 0; i < FRAME_HEAD_BYTES.length; i++) {
            if (data[i] != FRAME_HEAD_BYTES[i]) return null;
        }

        long declared = 0L;
        int digits = 0;
        while (cursor < data.length && data[cursor] >= '0' && data[cursor] <= '9') {
            declared = declared * 10 + (data[cursor] - '0');
            // 边解析边设界：定长读取，绝不会因为长度字段本身很长而溢出
            if (declared > MAX_PAYLOAD_BYTES) return null;
            cursor++;
            digits++;
        }
        if (digits == 0) return null;

        if (data.length - cursor < FRAME_SEPARATOR_BYTES.length) return null;
        for (int i = 0; i < FRAME_SEPARATOR_BYTES.length; i++) {
            if (data[cursor + i] != FRAME_SEPARATOR_BYTES[i]) return null;
        }
        cursor += FRAME_SEPARATOR_BYTES.length;

        // 声明长度必须与帧内剩余空间**精确**吻合，且结尾就是那一个 '}'。
        // 这一步才是"校验"：截断的帧剩余空间与声明长度对不上，直接判废
        if (declared <= 0 || data.length - cursor - 1 != declared) return null;
        if (data[data.length - 1] != '}') return null;
        return Arrays.copyOfRange(data, cursor, cursor + (int) declared);
    }

    private static boolean isReadableCacheSize(@NonNull File file) {
        long length = file.length();
        return length >= FRAME_MIN_BYTES && length <= FRAME_MAX_BYTES;
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
        if (ctx == null) {
            AndroidLog.logD(TAG, "get: Context unresolved for provider=" + provider + ", key=" + key);
            return null;
        }

        File file = getFile(ctx, provider, key);
        if (file == null) {
            AndroidLog.logD(TAG, "get: Invalid cache path for provider=" + provider + ", key=" + key);
            return null;
        }
        if (!file.isFile()) {
            AndroidLog.logD(TAG, "get: Disk cache MISS (file not found): " + file.getAbsolutePath());
            return null;
        }
        // **不加写锁**：写入走 `tmp + 原子改名`，读到的要么是旧文件、要么是新文件，
        // 不存在读到写了一半的中间态；而与全局写锁共享会让本方法被别的 provider 的慢写
        // 或清理扫描堵住，直接拖慢取词热路径。与删除并发也安全：删掉后读会抛 IOException，
        // 由下面的 catch 兜住并返回未命中
        try {
            if (!isReadableCacheSize(file)) {
                AndroidLog.logW(TAG, "get: Corrupted file size (" + file.length() + "), deleting: " + file.getAbsolutePath());
                Files.deleteIfExists(file.toPath());
                return null;
            }
            byte[] payload = unpack(Files.readAllBytes(file.toPath()));
            if (payload == null) {
                AndroidLog.logW(TAG, "get: Unpack version mismatch, deleting: " + file.getAbsolutePath());
                Files.deleteIfExists(file.toPath());
                return null;
            }
            AndroidLog.logI(TAG, "get: Disk cache HIT: " + file.getAbsolutePath() + " (" + payload.length + " bytes)");
            return payload;
        } catch (IOException | SecurityException e) {
            AndroidLog.logW(TAG, "Failed to read lyric cache from " + file.getAbsolutePath(), e);
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
        if (file == null) return;
        synchronized (WRITE_LOCK) {
            try {
                Files.deleteIfExists(file.toPath());
                AndroidLog.logD(TAG, "Deleted cache file: " + file.getAbsolutePath());
            } catch (IOException | SecurityException e) {
                AndroidLog.logW(TAG, "Failed to delete lyric cache: " + file.getAbsolutePath(), e);
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
        if (ctx == null) {
            AndroidLog.logW(TAG, "clearOnlineProviderCaches: Context unresolved");
            return false;
        }

        boolean success = true;
        synchronized (WRITE_LOCK) {
            for (String provider : ONLINE_PROVIDERS) {
                if (!clearProviderCacheLocked(ctx, provider)) {
                    AndroidLog.logW(TAG, "Failed to clear cache namespace: provider=" + provider
                        + ", cacheDir=" + ctx.getCacheDir());
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
        if (providerDir == null) {
            AndroidLog.logW(TAG, "clearProviderCache rejected: illegal provider name=" + provider);
            return false;
        }

        File cacheDir = context.getCacheDir();
        File root = new File(cacheDir, CACHE_ROOT);
        if (hasSymbolicLinkBetween(root, cacheDir)) {
            AndroidLog.logW(TAG, "clearProviderCache rejected: symlink between cacheDir=" + cacheDir
                + " and cacheRoot=" + root);
            return false;
        }
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
                AndroidLog.logW(TAG, "Failed to list lyric cache directory");
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
            AndroidLog.logW(TAG, "Failed to clear lyric cache", e);
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
        if (providerDir == null || keyPath == null) {
            AndroidLog.logW(TAG, "getFile rejected: provider=" + provider + ", key=" + key);
            return null;
        }

        File cacheDir = context.getCacheDir();
        if (cacheDir == null) {
            AndroidLog.logW(TAG, "getFile failed: context.getCacheDir() returned null");
            return null;
        }

        File providerDirFile = new File(new File(cacheDir, CACHE_ROOT), providerDir);
        File file = new File(providerDirFile, keyPath + ".json");
        if (!isSafeCachePath(providerDirFile, file)) {
            AndroidLog.logW(TAG, "getFile rejected by isSafeCachePath: " + file.getPath());
            return null;
        }
        return file;
    }

    private static boolean hasSymbolicLinkBetween(@NonNull File path, @NonNull File boundary) {
        File current = path;
        while (current != null && !current.equals(boundary)) {
            if (current.exists() && Files.isSymbolicLink(current.toPath())) return true;
            current = current.getParentFile();
        }
        return current == null;
    }

    private static boolean isSafeCachePath(@NonNull File providerDirFile, @NonNull File target) {
        try {
            File canonicalProviderDir = providerDirFile.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            String prefix = canonicalProviderDir.getPath() + File.separator;
            return canonicalTarget.getPath().startsWith(prefix);
        } catch (IOException | SecurityException e) {
            AndroidLog.logW(TAG, "isSafeCachePath failed for target " + target.getPath() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 解析宿主 Context：优先使用传入的宿主 Application Context，
     * 缺失时（onApplicationCreated 未触发的兜底场景）反射获取当前 Application。
     * <p>
     * 注意 {@code getApplicationContext()} **可能返回 null**：模块在 Hook
     * {@code Application.attach(Context)} 的时点就会调用 {@code onApplicationCreated}，
     * 那一刻 Application 尚未挂上，取不到 Application Context。此时必须退回使用
     * **传入的这个 Context 本身**（它同样是可用的宿主 Context，能正常取 cacheDir），
     * 否则整条缓存迁移链路每次启动都会静默失败。
     */
    @Nullable
    private static Context resolveContext(@Nullable Context context) {
        Context direct = applicationOf(context);
        if (direct != null) return direct;

        try {
            Context published = applicationOf(AbsPublisher.getAppContext());
            if (published != null) return published;
        } catch (Throwable ignored) {
        }
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
     * 取 Application Context；取不到时退回该 Context 本身，仍然取不到返回 {@code null}。
     */
    @Nullable
    private static Context applicationOf(@Nullable Context context) {
        if (context == null) return null;
        Context application = context.getApplicationContext();
        return application != null ? application : context;
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
