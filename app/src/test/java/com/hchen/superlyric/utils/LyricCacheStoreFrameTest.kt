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
package com.hchen.superlyric.utils

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 缓存帧的封装与校验。
 *
 * 这一层出的错不会报错，只会让**坏数据被当成好数据**喂给解析器：载荷本身（JSON、protobuf）
 * 里到处是 `}`，所以"末尾一个 `}`"这条判据挡不住任何截断。帧里因此写入了载荷长度，
 * 本测试钉住的就是"声明长度与实长必须精确吻合"。
 *
 * 体积闸也在这里钉住：它错了同样不报错，只是把**合法**条目当损坏删掉。
 *
 * `wrap` / `unpack` 是私有的纯字节函数，不需要 Context，用反射直接调用。
 */
class LyricCacheStoreFrameTest {

    private fun wrap(payload: ByteArray): ByteArray =
        invoke("wrap", payload) as ByteArray

    private fun unpack(frame: ByteArray): ByteArray? =
        invoke("unpack", frame) as ByteArray?

    private fun invoke(name: String, frame: ByteArray): Any? =
        LyricCacheStore::class.java
            .getDeclaredMethod(name, ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(null, frame)

    @Test
    fun `a wrapped payload round trips byte for byte`() {
        val payload = """{"code":200,"lrc":{"lyric":"[00:01.00]Hi"}}""".toByteArray()

        val restored = unpack(wrap(payload))

        assertNotNull(restored)
        assertArrayEquals(payload, restored)
    }

    @Test
    fun `an arbitrary byte payload survives the round trip`() {
        // 载荷不一定是可读文本，Spotify 那一路是 protobuf 原始字节
        val payload = byteArrayOf(0x00, 0x7B, 0x7D, 0x7D, 0x7D, 0x0A, 0x7D)

        val restored = unpack(wrap(payload))

        assertNotNull(restored)
        assertArrayEquals(payload, restored)
    }

    @Test
    fun `a frame truncated inside the payload is rejected even though it ends with a brace`() {
        val frame = wrap("""{"a":1}""".toByteArray())
        // 只丢最后一个字节（收尾的 '}'），于是末尾恰好成了载荷自己的 '}'——
        // 只验"末字节是 '}'"的旧判据会放它过去，并交出被截短的载荷
        val truncated = frame.copyOfRange(0, frame.size - 1)

        assertTrue(truncated[truncated.size - 1] == '}'.code.toByte())
        assertNull(unpack(truncated), "声明长度与帧内剩余空间对不上，必须判废")
    }

    @Test
    fun `a frame that lies about its payload length is rejected`() {
        // 长度字段本身被改：帧结构完好、末尾就是 '}'，只有长度对不上
        val liar = String(wrap("""{"a":1}""".toByteArray()), Charsets.UTF_8)
            .replace("\"n\":7", "\"n\":6")
            .toByteArray()

        assertNull(unpack(liar))
    }

    @Test
    fun `a frame from the previous cache format is rejected`() {
        // 旧格式 {"v":1,"d":…} 没有长度字段。必须判废，读取路径才会删掉它并按未命中重取
        assertNull(unpack("""{"v":1,"d":{"a":1}}""".toByteArray()))
    }

    @Test
    fun `malformed frames never throw`() {
        assertNull(unpack(ByteArray(0)))
        assertNull(unpack("not a frame at all".toByteArray()))
        assertNull(unpack("""{"v":2,"n":""".toByteArray()))
        assertNull(unpack("""{"v":2,"n":99999999,"d":{}}""".toByteArray()))
    }

    @Test
    fun `the size gate accepts everything the writer can produce`() {
        // 读侧的体积闸与写侧的封装必须对得上。上界若按"一位长度字段"的帧长来算，
        // 满额载荷（长度字段有 7 位）就会超界，被判成损坏文件**删掉**——而它是合法的
        assertTrue(
            isReadableSize(wrap(ByteArray(LyricCacheStore.MAX_PAYLOAD_BYTES))),
            "满额载荷的帧必须被读侧接受",
        )
        assertTrue(
            isReadableSize(wrap(ByteArray(1))),
            "只有一个载荷字节的帧必须被读侧接受",
        )
    }

    /** 读侧的体积闸只看文件长度，因此用临时文件喂它 */
    private fun isReadableSize(frame: ByteArray): Boolean {
        val file = File.createTempFile("frame", ".json")
        try {
            file.writeBytes(frame)
            return LyricCacheStore::class.java
                .getDeclaredMethod("isReadableCacheSize", File::class.java)
                .apply { isAccessible = true }
                .invoke(null, file) as Boolean
        } finally {
            file.delete()
        }
    }
}
