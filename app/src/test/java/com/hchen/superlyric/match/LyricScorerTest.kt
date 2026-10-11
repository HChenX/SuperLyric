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
package com.hchen.superlyric.match

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 多源检索打分器测试。
 *
 * 这一层决定「检索到的候选能不能被采纳」，因此**漏采与误采**都直接体现为
 * "这首歌没有歌词"或"显示了别的歌的歌词"。
 *
 * 后一半的用例（从「采纳线的两侧」开始）不是随手写的样例，而是
 * [LyricScorer.MIN_ACCEPT_SCORE] 那条线的**定义**：改动权重或时长分档，
 * 必须让它们重新成立，否则采纳线就失去依据。
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
        )
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `an identical title and artist is accepted`() {
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "kiroro", 333_000L,
        )
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `an unrelated title is still vetoed`() {
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "残酷な天使のテーゼ", "高橋洋子", 333_000L,
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
        )
        assertEquals(0.0, score, 0.001)
    }

    // =========================================================================
    // 采纳线的两侧
    // =========================================================================

    @Test
    fun `a sloppy but genuine match still clears the line`() {
        // 采纳线**下沿**：歌名相似度 0.8（一个字符之差）+ 歌手完全一致 + 时长差 4 秒 = 80。
        // 再往下压就会把这类真实匹配判成"没有歌词"
        val score = LyricScorer.scoreCandidate(
            "夜に駆ける", "kiroro", 333_000L,
            "夜に驅ける", "kiroro", 329_000L,
        )
        assertEquals(80.0, score, 0.01)
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `the same title with a different artist is rejected`() {
        // 采纳线**上沿**：歌名完全一致但歌手完全不同 = 70。再往上放就会把同名异曲的
        // 另一首歌的歌词配上去
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "高橋洋子", 333_000L,
        )
        assertEquals(70.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a strictly better candidate outscores a slightly worse one`() {
        // 调用方是"取最高分"。分数一旦在饱和区被压平，这句话就退化成"取检索首位"，
        // 同名的另一版随之随机胜出——日语歌配上韩语歌词正是这么来的
        val exact = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "kiroro", 333_000L,
        )
        val offByFourSeconds = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "kiroro", 329_000L,
        )

        assertEquals(100.0, exact, 0.01)
        assertEquals(90.0, offByFourSeconds, 0.01)
        assertTrue(exact > offByFourSeconds, "exact=$exact, off=$offByFourSeconds")
    }

    // =========================================================================
    // 缺失字段：候选侧缺失要扣分，目标侧缺失则重新归一
    // =========================================================================

    @Test
    fun `a candidate that shares only the title is rejected`() {
        // 平台检索回来一条只有歌名的记录（歌手与时长都缺）。缺了就无法核验，
        // 不能因为"不知道"就当成"匹配上了"——两个维度各丢满分，只剩歌名的 50 分
        val score = LyricScorer.scoreCandidate(
            "晴天", "周杰伦", 269_000L,
            "晴天", "", 0L,
        )
        assertEquals(50.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a title-only target cannot decide identity`() {
        // 宿主只报了歌名。同名曲目遍地都是（Intro / Interlude），一个维度定不了身份，
        // 归一后本会得满分，由上限压到 60
        val score = LyricScorer.scoreCandidate(
            "Intro", "", 0L,
            "Intro", "Somebody", 180_000L,
        )
        assertEquals(60.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a target with no artist still accepts an exact title and duration`() {
        // 目标侧缺歌手 ⇒ 那一栏从分母里去掉（50 + 20 = 70 的满分），而不是补一个常数分：
        // 常数分只会把采纳线悄悄往下挪一截
        val score = LyricScorer.scoreCandidate(
            "未来へ", "", 333_000L,
            "未来へ", "kiroro", 333_000L,
        )
        assertEquals(100.0, score, 0.01)
    }

    @Test
    fun `a target with no duration still accepts an exact title and artist`() {
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 0L,
            "未来へ", "kiroro", 333_000L,
        )
        assertEquals(100.0, score, 0.01)
    }

    @Test
    fun `without a target duration a wrong artist is no longer affordable`() {
        // 少了时长这一栏，歌手那一栏就得独自扛住：歌名完全相同但歌手完全不同，只剩
        // 50 / 80 = 62.5，进不去
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 0L,
            "未来へ", "高橋洋子", 333_000L,
        )
        assertEquals(62.5, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a six second duration gap no longer annihilates an otherwise exact match`() {
        // 宿主上报的时长常常是**流的长度**而非录音长度。原来这种事会把整条结果归零，
        // 现在只丢时长那一栏的 20 分
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "kiroro", 327_000L,
        )
        assertEquals(80.0, score, 0.01)
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    private companion object {
        const val ACCEPT_SCORE = 75.0
    }
}
