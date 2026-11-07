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

/**
 * 酷狗 KRC 载荷解密。
 *
 * <p>只做解密，不解析。解密后的明文交给 KRC 解析器。
 *
 * <p>格式：前 4 字节为 {@code "krc1"}；其后与 16 字节静态密钥循环异或；
 * 异或结果经 ZLib 解压为 UTF-8。
 *
 */
public final class KrcCipher {
    private static final byte[] KRC_KEY = new byte[]{
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, (byte) 0xce, (byte) 0xd2, 0x6e, 0x69
    };

    private static final byte[] KRC_MAGIC = new byte[]{0x6b, 0x72, 0x63, 0x31};

    private KrcCipher() {
    }

    /**
     * 判断字节流是否携带 KRC 魔数。
     *
     * @return 前四字节是否为 {@code "krc1"}
     */
    public static boolean hasKrcMagic(byte[] bytes) {
        if (bytes == null || bytes.length <= KRC_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < KRC_MAGIC.length; i++) {
            if (bytes[i] != KRC_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将 KRC 载荷还原为明文。
     *
     * <p>带 {@code krc1} 魔数时剥离魔数、异或并解压；否则按已解密明文返回。
     *
     * @return 解密后的 KRC 明文
     * @throws DecodeException 载荷为空或解压失败
     */
    public static String decrypt(byte[] krcBytes) throws DecodeException {
        if (krcBytes == null || krcBytes.length == 0) {
            throw new DecodeException("empty krc payload");
        }
        if (!hasKrcMagic(krcBytes)) {
            return new String(krcBytes, StandardCharsets.UTF_8);
        }

        byte[] payload = new byte[krcBytes.length - KRC_MAGIC.length];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (krcBytes[i + KRC_MAGIC.length] ^ KRC_KEY[i % KRC_KEY.length]);
        }
        return Zlib.inflateToString(payload);
    }
}
