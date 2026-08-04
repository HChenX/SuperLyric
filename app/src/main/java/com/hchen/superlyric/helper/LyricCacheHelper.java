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
package com.hchen.superlyric.helper;

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
 * 通用歌词磁盘缓存助手。
 * <p>
 * Spotify 与网易云特性共享的基建：按提供者命名空间独立存储接口原始 JSON
 * <p>
 * 路径：{@code cacheDir/SuperLyric/lyric/{provider}/{key}.json}
 * <p>
 * Spotify 传入 {@code key = {locale}/{id}}（locale 段用于区分不同语言的歌词）；
 * 网易云传入 {@code key = {id}}。key 会被清洗，仅允许 {@code [A-Za-z0-9._-]}
 * 与 {@code '/'} 分隔（可多层，如 {@code locale/id}），杜绝路径穿越。
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
public final class LyricCacheHelper {
    private static final String TAG = "LyricCacheHelper";
    private static final String CACHE_ROOT = "SuperLyric" + File.separator + "lyric";
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*");

    private LyricCacheHelper() {
    }

    /**
     * 将接口原始 JSON 写入磁盘缓存。
     *
     * @param provider 提供者命名空间（如 Spotify / Netease），须为纯名称
     * @param key      缓存键（可为 {locale}/{id} 或纯 id）
     * @param json     接口返回的原始 JSON 字符串
     */
    public static void put(@NonNull Context context, @NonNull String provider, @NonNull String key, @NonNull String json) {
        File file = getFile(context, provider, key);
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            // 先写临时文件再原子替换：避免进程被杀时留下半截 JSON，缓存只读到完整内容
            File temp = parent != null
                ? new File(parent, file.getName() + ".tmp")
                : new File(file.getName() + ".tmp");
            Files.write(temp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            XposedLog.logW(TAG, "Failed to write lyric cache: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 读取磁盘缓存，未命中返回 {@code null}。
     */
    @Nullable
    public static String get(@NonNull Context context, @NonNull String provider, @NonNull String key) {
        File file = getFile(context, provider, key);
        if (file == null || !file.isFile()) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            XposedLog.logW(TAG, "Failed to read lyric cache: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 删除磁盘缓存；不存在或删除失败时静默忽略。
     */
    public static void delete(@NonNull Context context, @NonNull String provider, @NonNull String key) {
        File file = getFile(context, provider, key);
        if (file == null || !file.isFile()) return;
        try {
            Files.delete(file.toPath());
        } catch (IOException e) {
            XposedLog.logW(TAG, "Failed to delete lyric cache: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 计算缓存文件路径；provider / key 非法时返回 {@code null}。
     */
    @Nullable
    private static File getFile(@NonNull Context context, @NonNull String provider, @NonNull String key) {
        String providerDir = sanitizeSegment(provider);
        if (providerDir == null) return null;

        String keyPath = sanitizeKey(key);
        if (keyPath == null) return null;

        return new File(new File(context.getCacheDir(), CACHE_ROOT), providerDir + File.separator + keyPath + ".json");
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
     * 获取当前默认语言的 locale 段（如 zh-CN），用于 Spotify 缓存键。
     */
    @NonNull
    public static String currentLocaleTag() {
        return Locale.getDefault().toLanguageTag();
    }
}
