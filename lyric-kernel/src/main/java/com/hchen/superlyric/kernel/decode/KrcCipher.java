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
 * 酷狗 KRC 载荷解密：{@code byte[] → 已解密的 KRC 明文}。
 * <p>
 * <b>只做解密，不做解析。</b>解密后的明文（形如 {@code [start,duration]<rel,dur,0>word}）
 * 交由 {@code lyrics-core} 的 {@code KugouKrcParser} 解析。
 * 这一层与解析层的分离是本次重构的硬性接缝之一：解密是稳定的（协议级），解析是易错的。
 * <p>
 * <b>格式：</b>
 * <ol>
 *   <li>前 4 字节魔数 {@code "krc1"}（0x6B 0x72 0x63 0x31）；</li>
 *   <li>自第 5 字节起，与 16 字节静态密钥循环异或；</li>
 *   <li>异或结果符合 RFC 1950，经 ZLib 解压为 UTF-8 明文。</li>
 * </ol>
 *
 * @author 焕晨HChen
 */
public final class KrcCipher {
    /**
     * 酷狗 KRC 官方 16 字节静态异或密钥。
     */
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
     * @param bytes 待检测的字节流
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
     * <p>
     * 若载荷带 {@code krc1} 魔数，则执行「剥离魔数 → 异或 → ZLib 解压」；解压失败即抛
     * {@link DecodeException}，**绝不**回退到未解密的密文缓冲区。
     * 若载荷不带魔数，则按调用方的声明视为**已解密明文**直接返回。
     *
     * @param krcBytes 原始载荷
     * @return 已解密的 KRC 明文
     * @throws DecodeException 载荷为空，或异或后的数据不是合法 ZLib 流
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
