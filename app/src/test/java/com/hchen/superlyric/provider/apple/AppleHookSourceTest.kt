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
package com.hchen.superlyric.provider.apple

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Apple Music 的**纯净化逻辑**测试。
 *
 * Apple 是订阅制服务，且歌词由 JNI 侧以裸指针交给 Java 层，
 * 本机无法做真机验证（无 VIP）。它的 Provider 层里唯一不依赖宿主对象的就是
 * [AppleHookSource.cleanHtml]，这里把它钉死。
 *
 * @author 焕晨HChen
 */
class AppleHookSourceTest {

    private val source = AppleHookSource()

    @Test
    fun `html markup is stripped from lyric text`() {
        assertEquals("Hello world", source.cleanHtml("Hello <i>world</i>"))
        assertEquals("no tags", source.cleanHtml("<span style=\"x\">no tags</span>"))
    }

    @Test
    fun `line breaks become spaces so word boundaries survive`() {
        // `<br>` 直接删除会把两段文字粘在一起；必须换成空格
        assertEquals("first second", source.cleanHtml("first<br>second"))
        assertEquals("first second", source.cleanHtml("first<br/>second"))
        assertEquals("first second", source.cleanHtml("first<br />second"))
        assertEquals("first second", source.cleanHtml("first<BR>second"))
    }

    @Test
    fun `entities are decoded after markup removal`() {
        assertEquals("it's & that", source.cleanHtml("it&#39;s &amp; that"))
    }

    @Test
    fun `invisible characters are stripped`() {
        // 零宽字符在宿主文本里很常见；下游按字符索引做逐字高亮时会因此越界
        assertEquals("ab", source.cleanHtml("a​b﻿"))
    }

    @Test
    fun `blank input yields an empty string`() {
        assertEquals("", source.cleanHtml(null))
        assertEquals("", source.cleanHtml(""))
        assertEquals("", source.cleanHtml("   "))
    }
}
