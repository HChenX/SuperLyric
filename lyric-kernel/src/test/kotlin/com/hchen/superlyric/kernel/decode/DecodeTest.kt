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
package com.hchen.superlyric.kernel.decode

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

/**
 * 解密层的已知答案测试。
 *
 * KRC 的编码过程在本文件中**独立重写**（而非调用被测代码），
 * 这样"编码 → 解码"才是真正的往返验证，而不是同义反复。
 */
class DecodeTest {

    private val krcKey = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    private fun deflate(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(StandardCharsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun encodeKrc(plainText: String): ByteArray {
        val compressed = deflate(plainText)
        val out = ByteArrayOutputStream()
        out.write("krc1".toByteArray(StandardCharsets.US_ASCII))
        compressed.forEachIndexed { index, byte ->
            out.write((byte.toInt() xor krcKey[index % krcKey.size].toInt()) and 0xff)
        }
        return out.toByteArray()
    }

    // =========================================================================
    // KRC
    // =========================================================================

    @Test
    fun `krc round trip recovers the original plaintext`() {
        val plainText = "[0,3500]<0,500,0>Hello <500,400,0>world\n[3500,2000]<0,800,0>Second line"

        val encoded = encodeKrc(plainText)
        val decoded = KrcCipher.decrypt(encoded)

        assertEquals(plainText, decoded)
    }

    @Test
    fun `krc magic detection is exact`() {
        assertTrue(KrcCipher.hasKrcMagic(encodeKrc("x")))
        assertFalse(KrcCipher.hasKrcMagic("krc2".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(1, 2, 3, 4, 5)))
        assertFalse(KrcCipher.hasKrcMagic(byteArrayOf()))
        assertFalse(KrcCipher.hasKrcMagic(null))
    }

    @Test
    fun `krc payload without magic is treated as already decrypted plaintext`() {
        val plainText = "[0,1000]<0,500,0>plain"

        assertEquals(plainText, KrcCipher.decrypt(plainText.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `corrupted krc payload raises instead of silently returning garbage`() {
        // 带魔数但异或后不是合法 ZLib 流：必须抛错，绝不能回退到未解密的缓冲区
        val corrupted = "krc1".toByteArray(StandardCharsets.US_ASCII) + ByteArray(64) { 0x55 }

        assertThrows(DecodeException::class.java) { KrcCipher.decrypt(corrupted) }
    }

    @Test
    fun `empty krc payload raises`() {
        assertThrows(DecodeException::class.java) { KrcCipher.decrypt(ByteArray(0)) }
    }

    // =========================================================================
    // ZLib 严格性
    // =========================================================================

    @Test
    fun `zlib tolerates trailing padding after a complete stream`() {
        // QRC 的 3DES 输出保留 PKCS5 填充，填充位于 ZLib 流末尾之后
        val compressed = deflate("payload")
        val withPadding = compressed + byteArrayOf(8, 8, 8, 8, 8, 8, 8, 8)

        assertEquals("payload", Zlib.inflateToString(withPadding))
    }

    @Test
    fun `truncated zlib stream raises`() {
        val compressed = deflate("a much longer payload that will definitely be truncated")

        assertThrows(DecodeException::class.java) {
            Zlib.inflateToString(compressed.copyOf(compressed.size / 2))
        }
    }

    // =========================================================================
    // 载荷还原
    // =========================================================================

    @Test
    fun `hex payloads are restored as bytes`() {
        assertArrayEquals(
            byteArrayOf(0x0f, 0xff.toByte(), 0x10),
            PayloadBytes.decode("0fFF10"),
        )
    }

    @Test
    fun `base64 payloads are restored as bytes`() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val encoded = java.util.Base64.getEncoder().encodeToString(raw)

        assertArrayEquals(raw, PayloadBytes.decode(encoded))
    }

    @Test
    fun `payloads that are neither hex nor base64 fall back to utf8`() {
        val text = "plain text ✓"

        assertArrayEquals(text.toByteArray(StandardCharsets.UTF_8), PayloadBytes.decode(text))
    }

    @Test
    fun `a decompression bomb is rejected instead of exhausting the host`() {
        // 炸弹的可怕之处在于体积不对称：压缩后只有几 KB，解出来是数 MB 乃至数 GB。
        // 若不设界，宿主进程会被 OOM 杀掉——这是本模块绝不能发生的事
        val compressed = deflate("A".repeat(4 * 1024 * 1024))

        assertTrue(compressed.size < 64 * 1024, "压缩后应当很小，实际 ${compressed.size} 字节")
        assertThrows(DecodeException::class.java) { Zlib.inflateToString(compressed) }
    }
}
