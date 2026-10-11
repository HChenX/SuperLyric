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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    // =========================================================================
    // 回调归属判定（切歌窗口里的过期结论必须被拦下）
    // =========================================================================

    @Test
    fun `a verdict for the current track is kept`() {
        assertEquals(AppleHookSource.TrackVerdict.SAME, source.judgeTrack("123456", "123456"))
        // 命名空间只出现在一侧时，归一化后仍应可比
        assertEquals(AppleHookSource.TrackVerdict.SAME, source.judgeTrack("123456", "apple:123456"))
    }

    @Test
    fun `a verdict for another track is dropped`() {
        // 核心那一条：锚点的取词有 350ms 去抖，`requestLyrics(item)` 可能在切歌**之后**
        // 才跑完。上一首的"无词"结论若被采纳，会经由纯音乐占位把有歌词的曲目永久钉死
        assertEquals(AppleHookSource.TrackVerdict.DIFFERENT, source.judgeTrack("123456", "654321"))
    }

    @Test
    fun `an unverifiable verdict is delivered rather than silently dropped`() {
        // 任一侧取不到标识 ⇒ 判"无从判定"，上层照旧投递并记 W。
        // 静默丢弃会让 Apple 的**合法**结论凭空消失，而那条路只会让曲目显示为空
        assertEquals(AppleHookSource.TrackVerdict.UNVERIFIABLE, source.judgeTrack(null, "123456"))
        assertEquals(AppleHookSource.TrackVerdict.UNVERIFIABLE, source.judgeTrack("123456", null))
        assertEquals(AppleHookSource.TrackVerdict.UNVERIFIABLE, source.judgeTrack("   ", "123456"))
        assertEquals(AppleHookSource.TrackVerdict.UNVERIFIABLE, source.judgeTrack(null, null))
    }

    @Test
    fun `the containment tolerance is deliberate and bounded`() {
        // 宽容判等的代价写在 judgeTrack 的注释里：互相包含即算同一首。
        // 把它钉死，免得以后有人以为这个判据比实际更严
        assertEquals(AppleHookSource.TrackVerdict.SAME, source.judgeTrack("12345", "123456"))
        assertEquals(AppleHookSource.TrackVerdict.DIFFERENT, source.judgeTrack("12345", "54321"))
    }

    // =========================================================================
    // 判等结果的采纳（六格）
    // =========================================================================

    @Test
    fun `only a confirmed same track is admitted, and only the payload path is lenient`() {
        // 这条规则写错过两次：先是不拦（过期结论投给了新曲目），后是 enforce 形同虚设
        // （注释写"从严、不采纳"，代码却照放行）。六格逐一钉住
        assertTrue(source.admits(AppleHookSource.TrackVerdict.SAME, enforce = true))
        assertTrue(source.admits(AppleHookSource.TrackVerdict.SAME, enforce = false))

        // 终态结论（无词）：判据不成立就不采纳——判错的代价是永久钉死，而"无从判定"
        // 恰恰说明这条判据不成立
        assertFalse(source.admits(AppleHookSource.TrackVerdict.DIFFERENT, enforce = true))
        assertFalse(source.admits(AppleHookSource.TrackVerdict.UNVERIFIABLE, enforce = true))

        // 载荷：判错只会显示一首歌的词、下一次投递就纠正，因此不丢投递
        assertTrue(source.admits(AppleHookSource.TrackVerdict.DIFFERENT, enforce = false))
        assertTrue(source.admits(AppleHookSource.TrackVerdict.UNVERIFIABLE, enforce = false))
    }
}
