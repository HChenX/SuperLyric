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
package com.hchen.superlyric.kernel.decode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 原始载荷字节的十六进制 / Base64 还原工具。
 *
 * @author 焕晨HChen
 */
public final class PayloadBytes {
    private PayloadBytes() {
    }

    /**
     * 将平台返回的字符串载荷还原为字节。
     * <p>
     * 判定顺序是**确定性**的，不依赖"解码失败再试下一个"的试探：
     * <ol>
     *   <li>偶数长度且全为十六进制字符 → 按十六进制还原；</li>
     *   <li>否则 → 按 MIME Base64 还原（容忍换行与空白）；</li>
     *   <li>两者皆不成立 → 视为原文的 UTF-8 字节。</li>
     * </ol>
     *
     * @param payload 原始字符串载荷
     * @return 还原后的字节；载荷为空时返回长度为 0 的数组
     */
    public static byte[] decode(String payload) {
        if (payload == null || payload.isEmpty()) {
            return new byte[0];
        }
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            return new byte[0];
        }

        if (isHex(trimmed)) {
            return fromHex(trimmed);
        }
        if (looksLikeBase64(trimmed)) {
            try {
                return Base64.getMimeDecoder().decode(trimmed);
            } catch (IllegalArgumentException ignored) {
                // 明确的 Base64 形态但解码失败：交由上层按原文处理
            }
        }
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isHex(String s) {
        if ((s.length() & 1) != 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeBase64(String s) {
        if (s.length() < 8) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * 十六进制字符串还原为字节。
     *
     * @param hex 偶数长度的十六进制字符串
     * @return 还原后的字节
     */
    public static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
