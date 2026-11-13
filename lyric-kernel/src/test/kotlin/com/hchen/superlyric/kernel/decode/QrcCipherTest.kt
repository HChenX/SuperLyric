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

import com.hchen.superlyric.kernel.parse.QrcParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * QRC 加密载荷的已知答案测试。
 *
 * 固定载荷由平台线上接口取得，只保存密文与明文摘要；解密路径必须还原出同一份 QRC XML。
 */
class QrcCipherTest {

    @Test
    fun `live encrypted qrc payload decrypts to qrc xml`() {
        val hex = requireNotNull(javaClass.getResourceAsStream("/qrc/live-qrc-20261113.hex")) {
            "missing qrc fixture"
        }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()

        val plain = QrcCipher.decrypt(PayloadBytes.decode(hex))

        assertTrue(plain.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(plain.contains("<QrcInfos>"))
        assertTrue(plain.contains("LyricContent="))
        assertEquals(LIVE_PAYLOAD_SHA256, sha256(plain))

        val doc = QrcParser.parse(plain)
        assertNotNull(doc)
        assertFalse(doc!!.isEmpty)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val LIVE_PAYLOAD_SHA256 =
            "47bcad29fe4fdf7d39c67460466585ebb76692963b687244c67cfd3a3a149c39"
    }
}
