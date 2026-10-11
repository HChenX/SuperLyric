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
 * QQ 音乐 QRC 载荷解密：{@code byte[] → 已解密的 QRC 明文}。
 * <p>
 * <b>只做解密，不做解析。</b>解密后的明文（{@code <QrcInfos>…} XML 或裸的
 * {@code [start,dur](w,dur)chars} 行）交由 {@code QrcParser} 解析。
 * <p>
 * <b>关于算法：</b>QQ 使用的是**经过改造的 Triple-DES**，与标准 DES 不兼容，因此
 * 无法使用 JCE 的 {@code DESede}。改造点在于 S 盒查表：{@link #sboxBit(byte)} 的
 * 取值展开后等价于标准 DES 的 {@code row * 16 + col}（已逐位验算），
 * 但 {@code SBOX2} 的第 24 项被改为 {@code 15}（标准 DES 为 {@code 14}）。
 * 任何"顺手换成 JCE"的改动都会静默产生乱码，务必保持本实现字节级不变。
 * <p>
 * <b>格式：</b>3DES 逐 8 字节块解密（ECB，不剥离 PKCS5 填充）→ ZLib 解压 → UTF-8 明文。
 * 未压缩的尾部填充由 {@link Zlib} 在流末尾自然停止来处理。
 *
 * @author 焕晨HChen
 */
public final class QrcCipher {
    private static final byte[] QRC_DES_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.UTF_8);

    private QrcCipher() {
    }

    /**
     * 判断文本是否已经是明文 QRC 报文（即无需解密）。
     *
     * @param text 候选文本
     * @return 是否包含 QRC 明文特征
     */
    public static boolean isPlainQrc(String text) {
        return text != null && (text.contains("<QrcInfos") || text.contains("<LyricInfo"));
    }

    /**
     * 将 QRC 载荷还原为明文。
     * <p>
     * 若载荷本身已是明文 XML 则原样返回；否则执行 3DES 解密 + ZLib 解压。
     * 任一环节失败即抛 {@link DecodeException}，不回退到错误缓冲区。
     *
     * @param cipherBytes 原始载荷
     * @return 已解密的 QRC 明文
     * @throws DecodeException 载荷为空、3DES 解密失败或解压失败
     */
    public static String decrypt(byte[] cipherBytes) throws DecodeException {
        if (cipherBytes == null || cipherBytes.length == 0) {
            throw new DecodeException("empty qrc payload");
        }

        String asText = new String(cipherBytes, StandardCharsets.UTF_8);
        if (isPlainQrc(asText)) {
            return asText;
        }

        byte[] decrypted = tripleDesDecrypt(cipherBytes);
        return Zlib.inflateToString(decrypted);
    }

    private static byte[] tripleDesDecrypt(byte[] cipherBytes) throws DecodeException {
        if (cipherBytes.length < 8) {
            throw new DecodeException("qrc ciphertext shorter than one DES block");
        }
        int blocks = cipherBytes.length / 8;
        byte[] decrypted = new byte[blocks * 8];
        byte[] inBlock = new byte[8];
        byte[] outBlock = new byte[8];
        byte[][][] schedule = ScheduleHolder.QRC_SCHEDULE;

        for (int b = 0; b < blocks; b++) {
            System.arraycopy(cipherBytes, b * 8, inBlock, 0, 8);
            tripleDesCrypt(inBlock, outBlock, schedule);
            System.arraycopy(outBlock, 0, decrypted, b * 8, 8);
        }
        return decrypted;
    }

    // =========================================================================
    // 腾讯改造版 Triple-DES（与标准 DES 不兼容，切勿替换为 JCE）
    // =========================================================================

    private static final class ScheduleHolder {
        static final byte[][][] QRC_SCHEDULE = initSchedule();

        private static byte[][][] initSchedule() {
            byte[][][] schedule = new byte[3][16][6];
            // 0 = DECRYPT
            tripleDesKeySetup(QRC_DES_KEY, schedule, 0);
            return schedule;
        }
    }

    private static int bitnum(byte[] a, int b, int c) {
        return (((a[(b) / 32 * 4 + 3 - (b) % 32 / 8] & 0xff) >>> (7 - (b % 8))) & 0x01) << (c);
    }

    private static byte bitnumintr(int a, int b, int c) {
        return (byte) ((((a >>> (31 - b)) & 0x01) << c) & 0xff);
    }

    private static int bitnumintl(int a, int b, int c) {
        return (((a << b) & 0x80000000) >>> c);
    }

    private static int sboxBit(byte a) {
        int val = a & 0xff;
        return ((val & 0x20) | ((val & 0x1f) >>> 1) | ((val & 0x01) << 4)) & 0x3f;
    }

    private static final byte[] SBOX1 = {
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
    };

    private static final byte[] SBOX2 = {
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
    };

    private static final byte[] SBOX3 = {
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
    };

    private static final byte[] SBOX4 = {
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
    };

    private static final byte[] SBOX5 = {
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
    };

    private static final byte[] SBOX6 = {
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
    };

    private static final byte[] SBOX7 = {
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
    };

    private static final byte[] SBOX8 = {
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
    };

    private static final int[] KEY_RND_SHIFT = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};

    private static final int[] KEY_PERM_C = {
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
        9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35
    };

    private static final int[] KEY_PERM_D = {
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
        13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3
    };

    private static final int[] KEY_COMPRESSION = {
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9,
        22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    };

    private static void keySchedule(byte[] key, int keyOffset, byte[][] schedule, int mode) {
        int c = 0;
        int d = 0;
        byte[] keySlice = new byte[8];
        System.arraycopy(key, keyOffset, keySlice, 0, 8);

        for (int i = 0, j = 31; i < 28; ++i, --j) {
            c |= bitnum(keySlice, KEY_PERM_C[i], j);
        }
        for (int i = 0, j = 31; i < 28; ++i, --j) {
            d |= bitnum(keySlice, KEY_PERM_D[i], j);
        }

        for (int i = 0; i < 16; ++i) {
            int shift = KEY_RND_SHIFT[i];
            c = ((c << shift) | (c >>> (28 - shift))) & 0xfffffff0;
            d = ((d << shift) | (d >>> (28 - shift))) & 0xfffffff0;

            int toGen = (mode == 0) ? (15 - i) : i;

            for (int j = 0; j < 6; ++j) {
                schedule[toGen][j] = 0;
            }
            for (int j = 0; j < 24; ++j) {
                schedule[toGen][j / 8] |= bitnumintr(c, KEY_COMPRESSION[j], 7 - (j % 8));
            }
            for (int j = 24; j < 48; ++j) {
                schedule[toGen][j / 8] |= bitnumintr(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8));
            }
        }
    }

    private static void tripleDesKeySetup(byte[] key, byte[][][] schedule, int mode) {
        if (mode == 1) { // ENCRYPT
            keySchedule(key, 0, schedule[0], 1);
            keySchedule(key, 8, schedule[1], 0);
            keySchedule(key, 16, schedule[2], 1);
        } else { // DECRYPT
            keySchedule(key, 0, schedule[2], 0);
            keySchedule(key, 8, schedule[1], 1);
            keySchedule(key, 16, schedule[0], 0);
        }
    }

    private static void tripleDesCrypt(byte[] input, byte[] output, byte[][][] key) {
        crypt(input, output, key[0]);
        crypt(output, output, key[1]);
        crypt(output, output, key[2]);
    }

    private static void crypt(byte[] input, byte[] output, byte[][] key) {
        int[] state = new int[2];
        ip(state, input);

        for (int idx = 0; idx < 15; ++idx) {
            int t = state[1];
            state[1] = f(state[1], key[idx]) ^ state[0];
            state[0] = t;
        }

        state[0] = f(state[1], key[15]) ^ state[0];

        invIp(state, output);
    }

    private static void ip(int[] state, byte[] input) {
        state[0] = bitnum(input, 57, 31) | bitnum(input, 49, 30) | bitnum(input, 41, 29) | bitnum(input, 33, 28)
            | bitnum(input, 25, 27) | bitnum(input, 17, 26) | bitnum(input, 9, 25) | bitnum(input, 1, 24)
            | bitnum(input, 59, 23) | bitnum(input, 51, 22) | bitnum(input, 43, 21) | bitnum(input, 35, 20)
            | bitnum(input, 27, 19) | bitnum(input, 19, 18) | bitnum(input, 11, 17) | bitnum(input, 3, 16)
            | bitnum(input, 61, 15) | bitnum(input, 53, 14) | bitnum(input, 45, 13) | bitnum(input, 37, 12)
            | bitnum(input, 29, 11) | bitnum(input, 21, 10) | bitnum(input, 13, 9) | bitnum(input, 5, 8)
            | bitnum(input, 63, 7) | bitnum(input, 55, 6) | bitnum(input, 47, 5) | bitnum(input, 39, 4)
            | bitnum(input, 31, 3) | bitnum(input, 23, 2) | bitnum(input, 15, 1) | bitnum(input, 7, 0);

        state[1] = bitnum(input, 56, 31) | bitnum(input, 48, 30) | bitnum(input, 40, 29) | bitnum(input, 32, 28)
            | bitnum(input, 24, 27) | bitnum(input, 16, 26) | bitnum(input, 8, 25) | bitnum(input, 0, 24)
            | bitnum(input, 58, 23) | bitnum(input, 50, 22) | bitnum(input, 42, 21) | bitnum(input, 34, 20)
            | bitnum(input, 26, 19) | bitnum(input, 18, 18) | bitnum(input, 10, 17) | bitnum(input, 2, 16)
            | bitnum(input, 60, 15) | bitnum(input, 52, 14) | bitnum(input, 44, 13) | bitnum(input, 36, 12)
            | bitnum(input, 28, 11) | bitnum(input, 20, 10) | bitnum(input, 12, 9) | bitnum(input, 4, 8)
            | bitnum(input, 62, 7) | bitnum(input, 54, 6) | bitnum(input, 46, 5) | bitnum(input, 38, 4)
            | bitnum(input, 30, 3) | bitnum(input, 22, 2) | bitnum(input, 14, 1) | bitnum(input, 6, 0);
    }

    private static void invIp(int[] state, byte[] input) {
        input[3] = (byte) (bitnumintr(state[1], 7, 7) | bitnumintr(state[0], 7, 6) | bitnumintr(state[1], 15, 5)
            | bitnumintr(state[0], 15, 4) | bitnumintr(state[1], 23, 3) | bitnumintr(state[0], 23, 2)
            | bitnumintr(state[1], 31, 1) | bitnumintr(state[0], 31, 0));

        input[2] = (byte) (bitnumintr(state[1], 6, 7) | bitnumintr(state[0], 6, 6) | bitnumintr(state[1], 14, 5)
            | bitnumintr(state[0], 14, 4) | bitnumintr(state[1], 22, 3) | bitnumintr(state[0], 22, 2)
            | bitnumintr(state[1], 30, 1) | bitnumintr(state[0], 30, 0));

        input[1] = (byte) (bitnumintr(state[1], 5, 7) | bitnumintr(state[0], 5, 6) | bitnumintr(state[1], 13, 5)
            | bitnumintr(state[0], 13, 4) | bitnumintr(state[1], 21, 3) | bitnumintr(state[0], 21, 2)
            | bitnumintr(state[1], 29, 1) | bitnumintr(state[0], 29, 0));

        input[0] = (byte) (bitnumintr(state[1], 4, 7) | bitnumintr(state[0], 4, 6) | bitnumintr(state[1], 12, 5)
            | bitnumintr(state[0], 12, 4) | bitnumintr(state[1], 20, 3) | bitnumintr(state[0], 20, 2)
            | bitnumintr(state[1], 28, 1) | bitnumintr(state[0], 28, 0));

        input[7] = (byte) (bitnumintr(state[1], 3, 7) | bitnumintr(state[0], 3, 6) | bitnumintr(state[1], 11, 5)
            | bitnumintr(state[0], 11, 4) | bitnumintr(state[1], 19, 3) | bitnumintr(state[0], 19, 2)
            | bitnumintr(state[1], 27, 1) | bitnumintr(state[0], 27, 0));

        input[6] = (byte) (bitnumintr(state[1], 2, 7) | bitnumintr(state[0], 2, 6) | bitnumintr(state[1], 10, 5)
            | bitnumintr(state[0], 10, 4) | bitnumintr(state[1], 18, 3) | bitnumintr(state[0], 18, 2)
            | bitnumintr(state[1], 26, 1) | bitnumintr(state[0], 26, 0));

        input[5] = (byte) (bitnumintr(state[1], 1, 7) | bitnumintr(state[0], 1, 6) | bitnumintr(state[1], 9, 5)
            | bitnumintr(state[0], 9, 4) | bitnumintr(state[1], 17, 3) | bitnumintr(state[0], 17, 2)
            | bitnumintr(state[1], 25, 1) | bitnumintr(state[0], 25, 0));

        input[4] = (byte) (bitnumintr(state[1], 0, 7) | bitnumintr(state[0], 0, 6) | bitnumintr(state[1], 8, 5)
            | bitnumintr(state[0], 8, 4) | bitnumintr(state[1], 16, 3) | bitnumintr(state[0], 16, 2)
            | bitnumintr(state[1], 24, 1) | bitnumintr(state[0], 24, 0));
    }

    private static int f(int state, byte[] key) {
        byte[] lrgstate = new byte[6];

        int t1 = bitnumintl(state, 31, 0) | ((state & 0xf0000000) >>> 1) | bitnumintl(state, 4, 5)
            | bitnumintl(state, 3, 6) | ((state & 0x0f000000) >>> 3) | bitnumintl(state, 8, 11)
            | bitnumintl(state, 7, 12) | ((state & 0x00f00000) >>> 5) | bitnumintl(state, 12, 17)
            | bitnumintl(state, 11, 18) | ((state & 0x000f0000) >>> 7) | bitnumintl(state, 16, 23);

        int t2 = bitnumintl(state, 15, 0) | ((state & 0x0000f000) << 15) | bitnumintl(state, 20, 5)
            | bitnumintl(state, 19, 6) | ((state & 0x00000f00) << 13) | bitnumintl(state, 24, 11)
            | bitnumintl(state, 23, 12) | ((state & 0x000000f0) << 11) | bitnumintl(state, 28, 17)
            | bitnumintl(state, 27, 18) | ((state & 0x0000000f) << 9) | bitnumintl(state, 0, 23);

        lrgstate[0] = (byte) ((t1 >>> 24) & 0xff);
        lrgstate[1] = (byte) ((t1 >>> 16) & 0xff);
        lrgstate[2] = (byte) ((t1 >>> 8) & 0xff);
        lrgstate[3] = (byte) ((t2 >>> 24) & 0xff);
        lrgstate[4] = (byte) ((t2 >>> 16) & 0xff);
        lrgstate[5] = (byte) ((t2 >>> 8) & 0xff);

        lrgstate[0] ^= key[0];
        lrgstate[1] ^= key[1];
        lrgstate[2] ^= key[2];
        lrgstate[3] ^= key[3];
        lrgstate[4] ^= key[4];
        lrgstate[5] ^= key[5];

        int b0 = lrgstate[0] & 0xff;
        int b1 = lrgstate[1] & 0xff;
        int b2 = lrgstate[2] & 0xff;
        int b3 = lrgstate[3] & 0xff;
        int b4 = lrgstate[4] & 0xff;
        int b5 = lrgstate[5] & 0xff;

        state = (SBOX1[sboxBit((byte) (b0 >>> 2))] << 28)
            | (SBOX2[sboxBit((byte) (((b0 & 0x03) << 4) | (b1 >>> 4)))] << 24)
            | (SBOX3[sboxBit((byte) (((b1 & 0x0f) << 2) | (b2 >>> 6)))] << 20)
            | (SBOX4[sboxBit((byte) (b2 & 0x3f))] << 16)
            | (SBOX5[sboxBit((byte) (b3 >>> 2))] << 12)
            | (SBOX6[sboxBit((byte) (((b3 & 0x03) << 4) | (b4 >>> 4)))] << 8)
            | (SBOX7[sboxBit((byte) (((b4 & 0x0f) << 2) | (b5 >>> 6)))] << 4)
            | SBOX8[sboxBit((byte) (b5 & 0x3f))];

        state = bitnumintl(state, 15, 0) | bitnumintl(state, 6, 1) | bitnumintl(state, 19, 2)
            | bitnumintl(state, 20, 3) | bitnumintl(state, 28, 4) | bitnumintl(state, 11, 5)
            | bitnumintl(state, 27, 6) | bitnumintl(state, 16, 7) | bitnumintl(state, 0, 8)
            | bitnumintl(state, 14, 9) | bitnumintl(state, 22, 10) | bitnumintl(state, 25, 11)
            | bitnumintl(state, 4, 12) | bitnumintl(state, 17, 13) | bitnumintl(state, 30, 14)
            | bitnumintl(state, 9, 15) | bitnumintl(state, 1, 16) | bitnumintl(state, 7, 17)
            | bitnumintl(state, 23, 18) | bitnumintl(state, 13, 19) | bitnumintl(state, 31, 20)
            | bitnumintl(state, 26, 21) | bitnumintl(state, 2, 22) | bitnumintl(state, 8, 23)
            | bitnumintl(state, 18, 24) | bitnumintl(state, 12, 25) | bitnumintl(state, 29, 26)
            | bitnumintl(state, 5, 27) | bitnumintl(state, 21, 28) | bitnumintl(state, 10, 29)
            | bitnumintl(state, 3, 30) | bitnumintl(state, 24, 31);

        return state;
    }
}
