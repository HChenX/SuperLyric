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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 缓存路径校验
 *
 * provider 与 key 都来自载荷（平台返回的 id、locale 前缀），这里是输入侧的安全边界：
 * 放行一个 `../` 就能把文件写到缓存目录之外。白名单正则与 canonical 前缀校验
 * 分别在此固定；符号链接检查需要真实链接且依赖 `getCacheDir()`，不在本测试覆盖。
 *
 * 被测方法都是私有的纯函数（只吃字符串与 `File`），用反射直接调用，不需要 Context。
 */
class LyricCacheStorePathTest {

    // 白名单正则

    @Test
    fun `a provider name must be a single plain segment`() {
        assertNotNull(sanitizeSegment("Netease"))
        assertNotNull(sanitizeSegment("a.b-c_1"))

        assertNull(sanitizeSegment(".."), "上一级目录")
        assertNull(sanitizeSegment("."))
        assertNull(sanitizeSegment("a/b"), "provider 名不允许再分层")
        assertNull(sanitizeSegment("..\\.."), "Windows 分隔符同样不允许")
        assertNull(sanitizeSegment(""), "空名会让路径落到父目录")
    }

    @Test
    fun `a cache key may nest but never climb`() {
        assertNotNull(sanitizeKey("12345"))
        assertNotNull(sanitizeKey("zh-CN/12345"), "允许 {locale}/{id} 两层")

        assertNull(sanitizeKey("../../etc/passwd"))
        assertNull(sanitizeKey("a/../../b"))
        assertNull(sanitizeKey("a/./b"), "当前目录段同样拒绝")
        assertNull(sanitizeKey("/etc/passwd"), "绝对路径直接拒")
        assertNull(sanitizeKey("C:/Windows/System32"), "盘符同样拒")
        assertNull(sanitizeKey(""))
        assertNull(sanitizeKey("a//b"), "空段会让路径含义含糊")
    }

    // canonical 前缀

    @Test
    fun `a target inside the provider directory is accepted`() {
        val providerDir = createTempDir()
        try {
            assertTrue(isSafeCachePath(providerDir, File(providerDir, "12345.json")))
        } finally {
            providerDir.delete()
        }
    }

    @Test
    fun `a target outside the provider directory is rejected`() {
        // 第二道守卫校验目标能否跳出 provider 目录，与白名单无关：
        // 白名单只保证每一段干净，不能约束拼接后的位置
        val root = createTempDir()
        val providerDir = File(root, "Netease").apply { mkdirs() }
        try {
            assertFalse(isSafeCachePath(providerDir, root), "父目录在 provider 目录之外")
            assertFalse(isSafeCachePath(providerDir, File(root, "Kugou/x.json")))
        } finally {
            root.deleteRecursively()
        }
    }

    // 反射入口

    private fun sanitizeSegment(segment: String): String? = invoke("sanitizeSegment", segment)

    private fun sanitizeKey(key: String): String? = invoke("sanitizeKey", key)

    private fun invoke(name: String, argument: String): String? =
        LyricCacheStore::class.java
            .getDeclaredMethod(name, String::class.java)
            .apply { isAccessible = true }
            .invoke(null, argument) as String?

    private fun isSafeCachePath(providerDir: File, target: File): Boolean =
        LyricCacheStore::class.java
            .getDeclaredMethod("isSafeCachePath", File::class.java, File::class.java)
            .apply { isAccessible = true }
            .invoke(null, providerDir, target) as Boolean

    /**
     * 建一个临时目录
     *
     * 这里需要目录，不使用 `createTempFile`。Windows 上创建符号链接需要额外权限，
     * 符号链接守卫不在本测试覆盖。
     */
    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "superlyric-test-${System.nanoTime()}")
            .apply { mkdirs() }
}
