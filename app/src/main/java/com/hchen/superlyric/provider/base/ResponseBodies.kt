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
package com.hchen.superlyric.provider.base

import com.hchen.superlyric.utils.LyricCacheStore

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

import okhttp3.ResponseBody

/**
 * 读取响应体文本，**超过上限即中止**
 *
 * ## 为什么不能用 `ResponseBody.string()`
 * 它把整个响应体一次物化成 String——UTF-16，约等于字节数的两倍，还要额外留一份
 * `byte[]`。响应体的大小完全由对端决定：一个坏掉的端点、运营商劫持页、被构造的响应
 * 都能返回一个超大 body，把**宿主进程 OOM 掉**。这是本项目最不能碰的一条线
 * （同 zlib 解压炸弹，见 `Zlib.MAX_OUTPUT_BYTES` 的说明）。
 *
 * ## 两道闸
 * 1. `Content-Length` 预检——廉价，但它可能缺席（分块传输、brotli 压缩后长度未知）
 * 2. 边读边计数——真正的兜底。超限**抛出**，由调用方的既有路径归类为可重试失败
 *
 * 绝不能在超限时返回被截断的文本：那不是"这次没取到"，而是把半截报文体当成完整报文
 * 喂给解析器，比失败更难查。
 *
 * ## 上限
 * 与 Spotify 那条路（Java 侧的同名逻辑 `SpotifyLyricAnalysis.readLimited`）共用
 * [LyricCacheStore.MAX_PAYLOAD_BYTES]。它是"单条原始歌词响应"的上限，正文实测在百 KB 量级。
 *
 * @param maxBytes 上限字节数
 * @return 响应体文本，字符集取自 `Content-Type`
 * @throws IOException 超过上限，或读取过程中出错
 */
internal fun ResponseBody.readTextBounded(
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
