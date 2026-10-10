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
package com.hchen.superlyric.parser;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * 酷狗 KRC (KuGou Resource Center) 原始高精逐字歌词解密与解析器。
 * <p>
 * <b>技术原理：</b>
 * <ul>
 *   <li><b>固定魔数：</b>前 4 字节为 {@code "krc1"} (ASCII: {@code 0x6b, 0x72, 0x63, 0x31})。</li>
 *   <li><b>静态密钥异或：</b>从第 5 字节起，每个字节与 16 字节固定密钥循环异或 (XOR)。</li>
 *   <li><b>ZLib 流解压缩：</b>异或后的字节流符合 RFC 1950 标准 ZLib 压缩格式，经 {@link Inflater} 解压为 UTF-8 明文。</li>
 *   <li><b>时序解析与自愈：</b>按行匹配 {@code [start,duration]} 与词元 {@code <startRel,duration,0>word}，
 *       集成 {@link LyricSanitizer#healWordTimings} 杜绝 0ms 坍缩缺陷。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public final class KrcDecoder {
    private static final String TAG = "KrcDecoder";

    /**
     * 酷狗 KRC 官方 16 字节静态异或密钥（全网通用，逾 15 年未变）。
     */
    private static final byte[] KRC_KEY = new byte[]{
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, (byte) 0xce, (byte) 0xd2, 0x6e, 0x69
    };

    private static final Pattern LINE_PATTERN = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern WORD_PATTERN = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)");
    private static final Pattern TAG_PATTERN = Pattern.compile("^\\[([a-zA-Z]+):(.*)\\]$");

    private KrcDecoder() {
    }

    /**
     * 从 Base64 编码的 KRC 原始报文字符串中解密并解析出逐字歌词行数组。
     *
     * @param base64Content Base64 编码字符串
     * @return 解析后的歌词行数组，失败时返回 null
     */
    @Nullable
    public static SuperLyricLine[] decodeFromBase64(@Nullable String base64Content) {
        if (base64Content == null || base64Content.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(base64Content.trim(), Base64.DEFAULT);
            return decode(bytes);
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Base64 decode failed for KRC: " + t.getMessage());
            return null;
        }
    }

    /**
     * 从原始二进制字节数组中解密解压并解析 KRC 歌词。
     *
     * @param krcBytes 包含 krc1 魔数的原始密文
     * @return 解析后的歌词行数组，失败时返回 null
     */
    @Nullable
    public static SuperLyricLine[] decode(@Nullable byte[] krcBytes) {
        if (krcBytes == null || krcBytes.length <= 4) {
            return null;
        }

        // 1. 检查文件头是否以 krc1 开头
        boolean isKrc1 = krcBytes[0] == 'k' && krcBytes[1] == 'r' && krcBytes[2] == 'c' && krcBytes[3] == '1';
        byte[] payload;
        if (isKrc1) {
            // 剥离魔数，执行 XOR 异或解密
            payload = new byte[krcBytes.length - 4];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (krcBytes[i + 4] ^ KRC_KEY[i % KRC_KEY.length]);
            }
        } else {
            // 兜底：可能外部已剥离魔数或直接为明文
            payload = krcBytes;
        }

        // 2. ZLib 解压
        String plainText = null;
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(payload);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(payload.length * 2);
            byte[] buffer = new byte[2048];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count <= 0) break;
                baos.write(buffer, 0, count);
            }
            inflater.end();
            plainText = baos.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable t) {
            AndroidLog.logD(TAG, "ZLib inflate failed, trying as plain UTF-8 text: " + t.getMessage());
            try {
                plainText = new String(krcBytes, StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
        }

        if (plainText == null || plainText.isEmpty()) {
            return null;
        }

        return parsePlainText(plainText);
    }

    /**
     * 解析已解密的 KRC 格式纯文本。
     *
     * @param plainText 解密后的 KRC 文本
     * @return 规整化的逐字歌词行数组
     */
    @NonNull
    public static SuperLyricLine[] parsePlainText(@NonNull String plainText) {
        String[] lines = plainText.split("\\r?\\n");
        List<SuperLyricLine> resultList = new ArrayList<>(lines.length);
        long globalOffset = 0L;

        // 首次遍历处理全局标签 (如 [offset:xxx])
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher tagMatcher = TAG_PATTERN.matcher(line);
            if (tagMatcher.matches()) {
                String tagKey = tagMatcher.group(1);
                String tagVal = tagMatcher.group(2);
                if ("offset".equalsIgnoreCase(tagKey) && tagVal != null) {
                    try {
                        globalOffset = Long.parseLong(tagVal.trim());
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher lineMatcher = LINE_PATTERN.matcher(line);
            if (!lineMatcher.matches()) {
                continue;
            }

            long lineStart;
            long lineDuration;
            try {
                lineStart = Long.parseLong(lineMatcher.group(1)) + globalOffset;
                lineDuration = Long.parseLong(lineMatcher.group(2));
            } catch (Throwable ignored) {
                continue;
            }

            if (lineDuration <= 0) {
                lineDuration = 1000L;
            }
            long lineEnd = lineStart + lineDuration;

            String body = lineMatcher.group(3);
            if (body == null) body = "";

            // 解析行内逐字切片
            Matcher wordMatcher = WORD_PATTERN.matcher(body);
            List<SuperLyricWord> words = new ArrayList<>();
            StringBuilder textBuilder = new StringBuilder();

            while (wordMatcher.find()) {
                try {
                    long relStart = Long.parseLong(wordMatcher.group(1));
                    long wDuration = Long.parseLong(wordMatcher.group(2));
                    String wText = LyricSanitizer.cleanInvisibleChars(wordMatcher.group(3));
                    if (wText == null || wText.isEmpty()) {
                        continue;
                    }

                    long wStart = lineStart + relStart;
                    long wEnd = wStart + wDuration;
                    if (wEnd <= wStart) {
                        wEnd = wStart + Math.max(120L, (long) wText.length() * 150L);
                    }
                    words.add(new SuperLyricWord(wText, wStart, wEnd));
                    textBuilder.append(wText);
                } catch (Throwable ignored) {
                }
            }

            String mainText = textBuilder.toString().trim();
            if (mainText.isEmpty()) {
                // 如果没有逐字标签，尝试提取无标签文本作为单行
                mainText = LyricSanitizer.cleanInvisibleChars(body.replaceAll("<[^>]*>", "").trim());
            }

            if (mainText == null || mainText.isEmpty()) {
                continue;
            }

            if (!words.isEmpty()) {
                LyricSanitizer.healWordTimings(words, lineStart, lineEnd);
                resultList.add(new SuperLyricLine(mainText, words.toArray(new SuperLyricWord[0]), null, lineStart, lineEnd));
            } else {
                resultList.add(new SuperLyricLine(mainText, lineStart, lineEnd));
            }
        }

        return resultList.toArray(new SuperLyricLine[0]);
    }
}
