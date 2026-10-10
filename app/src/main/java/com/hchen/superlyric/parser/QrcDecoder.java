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
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESedeKeySpec;

/**
 * QQ 音乐 QRC 原始高精逐字歌词解密与解析器。
 * <p>
 * <b>技术原理：</b>
 * <ul>
 *   <li><b>Triple-DES 官方密钥：</b>使用全网通用的静态 3DES 密钥 {@code "!@#)(*$%123ZXC!@!@#)(NHL"}。</li>
 *   <li><b>ECB/PKCS5 解密：</b>对十六进制或 Base64 编码的密文执行 {@code DESede/ECB/PKCS5Padding} 解密。</li>
 *   <li><b>ZLib 解压缩：</b>解密数据经标准 ZLib {@link Inflater} 解压得到标准 XML (QrcInfos 结构)。</li>
 *   <li><b>XML 内容提取：</b>提取 {@code Lyric_1} 原文与 {@code Lyric_2} 翻译。</li>
 *   <li><b>时序解析与词元规整：</b>按行匹配 {@code [start,duration]} 与词元 {@code (startRel,duration)word}，
 *       集成 {@link LyricSanitizer#healWordTimings} 自愈。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public final class QrcDecoder {
    private static final String TAG = "QrcDecoder";

    private static final byte[] QRC_DES_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.UTF_8);

    private static final Pattern LINE_PATTERN = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern WORD_PATTERN = Pattern.compile("\\((\\d+),(\\d+)\\)([^(]*)");
    private static final Pattern TAG_PATTERN = Pattern.compile("^\\[([a-zA-Z]+):(.*)\\]$");

    private static final Pattern XML_CONTENT_PATTERN_1 = Pattern.compile("<Lyric_1[^>]*LyricContent=\"([^\"]*)\"");
    private static final Pattern XML_CONTENT_PATTERN_2 = Pattern.compile("<Lyric_2[^>]*LyricContent=\"([^\"]*)\"");

    private QrcDecoder() {
    }

    /**
     * 从 Hex 十六进制或 Base64 格式的加密字符串中解密解压 QRC 逐字歌词。
     *
     * @param encryptedData 加密串 (Hex 或 Base64)
     * @return 解析后的歌词行数组，失败时返回 null
     */
    @Nullable
    public static SuperLyricLine[] decodeFromHexOrBase64(@Nullable String encryptedData) {
        if (encryptedData == null || encryptedData.trim().isEmpty()) {
            return null;
        }

        String trimmed = encryptedData.trim();
        byte[] rawBytes = null;

        // 尝试十六进制解码
        if (trimmed.length() % 2 == 0 && trimmed.matches("^[0-9a-fA-F]+$")) {
            try {
                rawBytes = hexStringToBytes(trimmed);
            } catch (Throwable ignored) {
            }
        }

        // 尝试 Base64 解码
        if (rawBytes == null) {
            try {
                rawBytes = Base64.decode(trimmed, Base64.DEFAULT);
            } catch (Throwable ignored) {
            }
        }

        if (rawBytes == null) {
            rawBytes = trimmed.getBytes(StandardCharsets.UTF_8);
        }

        return decode(rawBytes);
    }

    /**
     * 对原始二进制密文进行 3DES 解密与 ZLib 解压。
     *
     * @param cipherBytes 原始密文字节
     * @return 歌词行数组
     */
    @Nullable
    public static SuperLyricLine[] decode(@Nullable byte[] cipherBytes) {
        if (cipherBytes == null || cipherBytes.length == 0) {
            return null;
        }

        byte[] decryptedBytes = null;

        // 1. 若已经是 XML 格式，跳过解密
        String candidateXml = new String(cipherBytes, StandardCharsets.UTF_8);
        if (candidateXml.contains("<QrcInfos") || candidateXml.contains("<LyricInfo")) {
            return parseQrcXml(candidateXml);
        }

        // 2. 执行 Triple-DES (DESede) 解密
        try {
            KeySpec keySpec = new DESedeKeySpec(QRC_DES_KEY);
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("DESede");
            SecretKey secretKey = keyFactory.generateSecret(keySpec);

            Cipher cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            decryptedBytes = cipher.doFinal(cipherBytes);
        } catch (Throwable t) {
            AndroidLog.logD(TAG, "3DES decrypt failed: " + t.getMessage());
            decryptedBytes = cipherBytes;
        }

        // 3. 执行 ZLib 解压
        String xmlContent = null;
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(decryptedBytes);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(decryptedBytes.length * 3);
            byte[] buffer = new byte[2048];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count <= 0) break;
                baos.write(buffer, 0, count);
            }
            inflater.end();
            xmlContent = baos.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable t) {
            AndroidLog.logD(TAG, "ZLib inflate failed for QRC, falling back to direct string: " + t.getMessage());
            try {
                xmlContent = new String(decryptedBytes, StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
        }

        if (xmlContent == null || xmlContent.isEmpty()) {
            return null;
        }

        return parseQrcXml(xmlContent);
    }

    /**
     * 解析 QRC 标准 XML 内容。
     *
     * @param xmlContent XML 字符串
     * @return 规整化歌词行
     */
    @NonNull
    public static SuperLyricLine[] parseQrcXml(@NonNull String xmlContent) {
        String mainLyricContent = null;
        String transLyricContent = null;

        Matcher m1 = XML_CONTENT_PATTERN_1.matcher(xmlContent);
        if (m1.find()) {
            mainLyricContent = m1.group(1);
        }

        Matcher m2 = XML_CONTENT_PATTERN_2.matcher(xmlContent);
        if (m2.find()) {
            transLyricContent = m2.group(1);
        }

        if (mainLyricContent == null) {
            mainLyricContent = xmlContent;
        }

        // 解析翻译映射：按行时间戳匹配 (startMs -> text)
        Map<Long, String> transMap = new HashMap<>();
        if (transLyricContent != null && !transLyricContent.isEmpty()) {
            String[] transLines = transLyricContent.split("\\r?\\n");
            for (String tLine : transLines) {
                tLine = tLine.trim();
                Matcher lm = LINE_PATTERN.matcher(tLine);
                if (lm.matches()) {
                    try {
                        long start = Long.parseLong(lm.group(1));
                        String transText = LyricSanitizer.cleanInvisibleChars(lm.group(3).replaceAll("<[^>]*>", "").replaceAll("\\([^)]*\\)", "").trim());
                        if (transText != null && !transText.isEmpty()) {
                            transMap.put(start, transText);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        String[] lines = mainLyricContent.split("\\r?\\n");
        List<SuperLyricLine> resultList = new ArrayList<>(lines.length);
        long globalOffset = 0L;

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

            // QRC 格式示例：[0,3500]爱是无畏的冒险(0,500)爱(500,400)是(900,1100)无畏
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
                // 如果没有找到 (start,dur) 格式，剥离标签取整行
                mainText = LyricSanitizer.cleanInvisibleChars(body.replaceAll("\\([^)]*\\)", "").replaceAll("<[^>]*>", "").trim());
            }

            if (mainText == null || mainText.isEmpty()) {
                continue;
            }

            SuperLyricLine lyricLine;
            if (!words.isEmpty()) {
                LyricSanitizer.healWordTimings(words, lineStart, lineEnd);
                lyricLine = new SuperLyricLine(mainText, words.toArray(new SuperLyricWord[0]), null, lineStart, lineEnd);
            } else {
                lyricLine = new SuperLyricLine(mainText, lineStart, lineEnd);
            }

            // 关联翻译
            String translation = transMap.get(lineStart - globalOffset);
            if (translation != null && !translation.isEmpty()) {
                lyricLine.setTranslation(translation);
            }

            resultList.add(lyricLine);
        }

        return resultList.toArray(new SuperLyricLine[0]);
    }

    private static byte[] hexStringToBytes(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }
}
