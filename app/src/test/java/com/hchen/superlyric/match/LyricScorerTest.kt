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
package com.hchen.superlyric.match

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 多源检索打分器测试。
 *
 * 这一层决定「检索到的候选能不能被采纳」，门槛被刻意收紧过（85 分），
 * 因此**漏采与误采**都直接体现为"这首歌没有歌词"或"显示了别的歌的歌词"。
 *
 * @author 焕晨HChen
 */
class LyricScorerTest {

    @Test
    fun `a parenthesised translation of the title must not sink an otherwise exact match`() {
        // 实测（酷狗概念版 5.2.9）：宿主上报 "未来へ (向着未来)"，平台曲库是 "未来へ"，
        // 歌手与时长完全一致，却因括号内的中文译名被计入相似度而落选，最终判为无歌词。
        val score = LyricScorer.scoreCandidate(
            "未来へ (向着未来)", "kiroro", 333_000L,
            "未来へ", "kiroro", 333_000L,
            true,
        )
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `an identical title and artist is accepted`() {
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "kiroro", 333_000L,
            true,
        )
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `an unrelated title is still vetoed`() {
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "残酷な天使のテーゼ", "高橋洋子", 333_000L,
            true,
        )
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun `only a trailing bracket is ignored, a leading one is not`() {
        // 末尾括号按译名/注解处理
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("未来へ (向着未来)", "未来へ"),
            0.001,
        )
        // 前置括号是标题的一部分（如 "(What's) The Story"），不做剥离，只按包含比例给分
        assertTrue(LyricScorer.calculateTitleSimilarity("(Intro) Song", "Song") < 0.5)
    }

    @Test
    fun `a version marker in the trailing bracket is never stripped`() {
        // 实测（酷狗概念版 5.2.9）：无条件剥离末尾括号后，这些"同一首歌的另一版"
        // 会从勉强过线/被否决直接顶到满分，与原版同分后靠候选顺序随机胜出——
        // 日语歌配上韩语歌词、录音室版配上演唱会版。
        assertTrue(
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER (Korean Ver)", "NIGHT DANCER") < 0.5,
            "语言版本标记不得被剥离",
        )
        assertTrue(
            LyricScorer.calculateTitleSimilarity("土坡上的狗尾草 (DJ豪大大版)", "土坡上的狗尾草") < 0.5,
            "改编版本标记不得被剥离",
        )
        assertTrue(
            LyricScorer.calculateTitleSimilarity("孤独患者 (1.1x版)", "孤独患者") < 0.5,
            "带数字的版本标记不得被剥离",
        )
        // 纯拉丁标题里的括号一律不剥：这是"另一版"，不是译名
        assertTrue(
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER (From THE FIRST TAKE)", "NIGHT DANCER") < 0.5,
            "拉丁标题的括号不得被当作译名剥离",
        )
    }

    @Test
    fun `a concert recording of the same song is still vetoed`() {
        // 实测：这一条在"无条件剥离"的改法下会从 0.0 变成 100.0
        val score = LyricScorer.scoreCandidate(
            "孤独患者", "陈奕迅", 273_000L,
            "孤独患者 (2025 FEAR and DREAMS世界巡回演唱会合肥站)", "陈奕迅", 269_000L,
            true,
        )
        assertEquals(0.0, score, 0.001)
    }

    private companion object {
        const val ACCEPT_SCORE = 85.0
    }
}
