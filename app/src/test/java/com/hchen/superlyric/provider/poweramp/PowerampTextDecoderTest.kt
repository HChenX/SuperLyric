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
package com.hchen.superlyric.provider.poweramp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class PowerampTextDecoderTest {

    @Test
    fun `utf8 bom is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "你好".toByteArray(Charsets.UTF_8)

        assertEquals("你好", PowerampTextDecoder.decode(bytes))
    }

    @Test
    fun `gbk bytes fall back instead of becoming replacement characters`() {
        val bytes = "中文".toByteArray(Charset.forName("GBK"))

        assertEquals("中文", PowerampTextDecoder.decode(bytes))
    }

    @Test
    fun `utf16le bom is honoured`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                "hello".toByteArray(Charsets.UTF_16LE)

        assertEquals("hello", PowerampTextDecoder.decode(bytes))
    }
}
