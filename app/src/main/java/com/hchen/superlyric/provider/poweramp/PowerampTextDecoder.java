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
package com.hchen.superlyric.provider.poweramp;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Poweramp LRC 文本解码：UTF-8 严格解码，失败再回退 GBK。
 *
 * <p>不能用 {@code new String(bytes, UTF_8)} 做探测：它会把非法序列替换成 U+FFFD，
 * 乱码也能“成功”解码，永远走不到 GBK 分支。
 *
 * <p>这个类同时供 Java 锚点与 Kotlin Hook 来源使用，避免两份实现漂移。
 */
public final class PowerampTextDecoder {

    private PowerampTextDecoder() {
    }

    @NonNull
    public static String decode(@NonNull byte[] bytes) {
        int offset = 0;
        int length = bytes.length;
        if (length >= 3 && bytes[0] == (byte) 0xEF
            && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            offset = 3;
        } else if (length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return new String(bytes, 2, length - 2, StandardCharsets.UTF_16LE);
        } else if (length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return new String(bytes, 2, length - 2, StandardCharsets.UTF_16BE);
        }

        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length - offset))
                .toString();
        } catch (CharacterCodingException ignored) {
            try {
                return Charset.forName("GBK").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes, offset, length - offset))
                    .toString();
            } catch (CharacterCodingException e) {
                return new String(bytes, offset, length - offset, StandardCharsets.UTF_8);
            }
        }
    }
}
