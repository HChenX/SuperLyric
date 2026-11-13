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
 * Apple Music 的净化逻辑与回调归属判定
 *
 * 歌词由 JNI 侧以裸指针交给 Java 层，Provider 层中不依赖宿主对象的是
 * [AppleHookSource.cleanHtml] 与 [AppleHookSource.judgeTrack]。
 *
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

    // 回调归属判定：切歌窗口内的过期结论必须被拦下

    @Test
    fun `a verdict for the current track is kept`() {
        assertEquals(AppleHookSource.TrackVerdict.SAME, source.judgeTrack("123456", "123456"))
        // 命名空间只出现在一侧时，归一化后仍应可比
        assertEquals(AppleHookSource.TrackVerdict.SAME, source.judgeTrack("123456", "apple:123456"))
    }

    @Test
    fun `a verdict for another track is dropped`() {
        // 锚点取词有 350ms 去抖，`requestLyrics(item)` 可能在切歌之后才返回。
        // 上一首的"无词"结论若被采纳，会经由纯音乐占位把有歌词的曲目永久写成纯音乐
        assertEquals(AppleHookSource.TrackVerdict.DIFFERENT, source.judgeTrack("123456", "654321"))
    }

    @Test
    fun `a verdict without comparable ids is unverifiable`() {
        // 任一侧取不到标识即判为无从判定；无从判定与不同曲目一样不采纳，
        // 否则上一首的结论会挂到当前曲目上
        assertEquals(AppleHookSource.TrackVerdict.UNVERIFIABLE, source.judgeTrack(null, "123456"))
        assertEquals(AppleHookSource.TrackVerdict.UNVERIFIABLE, source.judgeTrack("123456", null))
        assertEquals(AppleHookSource.TrackVerdict.UNVERIFIABLE, source.judgeTrack("   ", "123456"))
        assertEquals(AppleHookSource.TrackVerdict.UNVERIFIABLE, source.judgeTrack(null, null))
    }

    @Test
    fun `the containment tolerance is deliberate and bounded`() {
        // 判等规则是互相包含即算同一首，其代价记在 judgeTrack 的注释中；
        // 此处固定该判据的实际范围
        assertEquals(AppleHookSource.TrackVerdict.SAME, source.judgeTrack("12345", "123456"))
        assertEquals(AppleHookSource.TrackVerdict.DIFFERENT, source.judgeTrack("12345", "54321"))
    }
}
