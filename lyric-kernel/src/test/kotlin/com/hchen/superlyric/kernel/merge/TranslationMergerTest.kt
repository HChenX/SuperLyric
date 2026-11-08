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
package com.hchen.superlyric.kernel.merge

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * 翻译合并的容差矩阵
 *
 * 覆盖翻译丢失与翻译错位两类边界。
 */
class TranslationMergerTest {

    private fun doc(vararg starts: Long) = LyricDoc(
        lines = starts.map { LyricLine(startMs = it, endMs = it + 1000, text = "line-$it") }
    )

    @Test
    fun `exact timestamp is attached`() {
        val result = TranslationMerger.attach(doc(1000), listOf(TimeText(1000, "译文")), SidecarChannel.TRANSLATION)

        assertEquals("译文", result.lines[0].translation)
    }

    @Test
    fun `one millisecond drift is tolerated`() {
        // 1ms 漂移仍在容差内，译文必须对齐而不是静默丢弃
        val result = TranslationMerger.attach(doc(1000), listOf(TimeText(1001, "译文")), SidecarChannel.TRANSLATION)

        assertEquals("译文", result.lines[0].translation)
    }

    @Test
    fun `a 100ms drift is still attached under the retained 150ms tolerance`() {
        // Taskbar 的 60ms 严格窗口会丢弃这条译文；本项目保留 150ms，需金标准测试钉住。
        val result = TranslationMerger.attach(doc(1000), listOf(TimeText(1100, "译文")), SidecarChannel.TRANSLATION)

        assertEquals("译文", result.lines[0].translation)
    }

    @Test
    fun `drift just inside the tolerance is attached`() {
        val result = TranslationMerger.attach(
            doc(1000),
            listOf(TimeText(1000 + TranslationMerger.TOLERANCE_MS, "译文")),
            SidecarChannel.TRANSLATION,
        )

        assertEquals("译文", result.lines[0].translation)
    }


    @Test
    fun `Long MIN_VALUE does not overflow into a false match`() {
        val result = TranslationMerger.attach(
            doc(0L),
            listOf(TimeText(Long.MIN_VALUE, "bad")),
            SidecarChannel.TRANSLATION,
        )

        assertNull(result.lines[0].translation)
    }

    @Test
    fun `drift beyond the tolerance is dropped rather than misattached`() {
        val result = TranslationMerger.attach(
            doc(1000),
            listOf(TimeText(1000 + TranslationMerger.TOLERANCE_MS + 1, "译文")),
            SidecarChannel.TRANSLATION,
        )

        assertNull(result.lines[0].translation)
    }

    @Test
    fun `nearest line wins among several candidates`() {
        val result = TranslationMerger.attach(
            doc(1000, 1150),
            listOf(TimeText(1140, "译文")),
            SidecarChannel.TRANSLATION,
        )

        assertNull(result.lines[0].translation, "不应挂到较远的那一行")
        assertEquals("译文", result.lines[1].translation)
    }

    @Test
    fun `inline translation is never overwritten`() {
        val base = LyricDoc(
            lines = listOf(LyricLine(startMs = 1000, endMs = 2000, text = "main", translation = "行内译文"))
        )

        val result = TranslationMerger.attach(base, listOf(TimeText(1000, "伴随译文")), SidecarChannel.TRANSLATION)

        assertEquals("行内译文", result.lines[0].translation)
    }

    @Test
    fun `each sidecar entry is consumed at most once`() {
        val result = TranslationMerger.attach(
            doc(1000, 1050),
            listOf(TimeText(1000, "唯一译文")),
            SidecarChannel.TRANSLATION,
        )

        val attached = result.lines.count { it.hasTranslation }
        assertEquals(1, attached, "单条伴随记录不得重复挂载到多行")
    }

    @Test
    fun `phonetic channel is independent of translation`() {
        val result = TranslationMerger.attach(
            TranslationMerger.attach(doc(1000), listOf(TimeText(1000, "译文")), SidecarChannel.TRANSLATION),
            listOf(TimeText(1000, "romanization")),
            SidecarChannel.PHONETIC,
        )

        assertEquals("译文", result.lines[0].translation)
        assertEquals("romanization", result.lines[0].phonetic)
    }

    @Test
    fun `empty inputs return the original instance unchanged`() {
        val base = doc(1000)

        assertSame(base, TranslationMerger.attach(base, emptyList(), SidecarChannel.TRANSLATION))
        assertSame(
            LyricDoc.EMPTY,
            TranslationMerger.attach(LyricDoc.EMPTY, listOf(TimeText(0, "x")), SidecarChannel.TRANSLATION),
        )
    }
}
