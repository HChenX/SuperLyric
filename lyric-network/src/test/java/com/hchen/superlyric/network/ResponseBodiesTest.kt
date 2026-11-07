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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.network

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * 响应体读取上限
 *
 * 响应体大小完全由对端决定，`ResponseBody.string()` 会把整块物化成 String（UTF-16，约两倍字节数），
 * 超大响应可导致宿主 OOM。
 *
 * 超限必须抛，不能返回被截断的文本：半截报文喂给解析器比失败更难定位。
 */
class ResponseBodiesTest {

    private val json = "application/json; charset=utf-8".toMediaType()

    @Test
    fun `a small body round trips`() {
        val text = """{"code":200,"message":"ok"}"""

        assertEquals(text, text.toResponseBody(json).readTextBounded(1024))
    }

    @Test
    fun `a body with a declared oversize length is rejected up front`() {
        val body = "x".repeat(4096).toResponseBody(json)

        val thrown = assertThrows(IOException::class.java) { body.readTextBounded(1024) }

        assertTrue(thrown.message!!.contains("1024"), "message=${thrown.message}")
    }

    @Test
    fun `a body without a declared length is bounded while streaming`() {
        // 分块传输或经 brotli 压缩后长度未知的响应，Content-Length 为 -1，
        // 只能边读边计数
        val body = UnknownLengthBody("x".repeat(4096).toByteArray())

        assertThrows(IOException::class.java) { body.readTextBounded(1024) }
    }

    @Test
    fun `a body right at the limit is accepted`() {
        val payload = "x".repeat(1024)

        assertEquals(payload, payload.toResponseBody(null).readTextBounded(1024))
    }

    /** 声明的长度未知的响应体（分块 / 压缩后） */
    private class UnknownLengthBody(private val payload: ByteArray) : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = Buffer().write(payload)
    }
}