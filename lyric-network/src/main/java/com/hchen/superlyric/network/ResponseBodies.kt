/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
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

import com.hchen.superlyric.utils.LyricCacheStore

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

import okhttp3.ResponseBody

/**
 * 读取响应体文本，超过上限即中止
 *
 * `ResponseBody.string()` 会将整个响应体物化为 String（UTF-16，约为字节数的两倍），
 * 并额外保留一份 `byte[]`。响应体大小由对端决定，超大响应可能耗尽宿主内存；
 * 与 zlib 解压炸弹相同，见 `Zlib.MAX_OUTPUT_BYTES` 的说明。
 */
fun ResponseBody.readTextBounded(
    maxBytes: Int = LyricCacheStore.MAX_PAYLOAD_BYTES,
): String {
    val declared = contentLength()
    if (declared > maxBytes) {
        throw IOException("Response body declared $declared bytes, over the $maxBytes limit")
    }

    val stream = byteStream()
    val buffer = ByteArray(8 * 1024)
    val collected = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    var total = 0
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw IOException("Response body exceeded the $maxBytes byte limit")
        }
        collected.write(buffer, 0, read)
    }

    val charset = contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
    return collected.toString(charset.name())
}