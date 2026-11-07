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
 * 多源检索打分器的行为
 *
 * 本层决定检索到的候选能否被采纳，漏采与误采分别表现为"这首歌没有歌词"与
 * "显示了别的歌的歌词"。
 *
 * 从「采纳线的两侧」起的用例是 [LyricScorer.MIN_ACCEPT_SCORE] 的定义：
 * 改动权重或时长分档后，必须让它们重新成立。
 *
 * @author 焕晨HChen
 */
class LyricScorerTest {

    @Test
    fun `a parenthesised translation of the title must not sink an otherwise exact match`() {
        // 酷狗概念版 5.2.9 上报 "未来へ (向着未来)"，平台曲库为 "未来へ"，歌手与时长一致；
        // 括号内的中文译名若计入相似度，该候选落选并判为无歌词
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
        // 前置括号属于标题（如 "(What's) The Story"），不剥离，只按包含比例给分
        assertTrue(LyricScorer.calculateTitleSimilarity("(Intro) Song", "Song") < 0.5)
    }


    @Test
    fun versionMarkersWithDotAreRejectedBeforeTitleCleaning() {
        // 版本标记的真实形态带点；整段括号先被删掉会使 VARIANT 守卫看不到它
        assertTrue(LyricScorer.calculateTitleSimilarity("孤独患者 (Korean Ver.)", "孤独患者") < 0.5)
        assertTrue(LyricScorer.calculateTitleSimilarity("NIGHT DANCER (Live)", "NIGHT DANCER") < 0.5)
        assertTrue(LyricScorer.calculateTitleSimilarity("土坡上的狗尾草 (伴奏)", "土坡上的狗尾草") < 0.5)
    }

    @Test
    fun `an English word containing live is not a variant marker`() {
        // "Oliver" 含子串 live；没有词边界时整段会被误判为另一版本。
        assertTrue(LyricScorer.calculateTitleSimilarity("未来へ (Oliver)", "未来へ") > 0.5)
    }

    @Test
    fun `a version marker in the trailing bracket is never stripped`() {
        // 剥离末尾括号会让同一首歌的另一版从被否决顶到满分，与原版同分后
        // 靠候选顺序胜出，出现日语歌配韩语歌词、录音室版配演唱会版
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
        // 演唱会录音不得与录音室版本同分；剥离版本标记会使其从 0.0 变成 100.0
        val score = LyricScorer.scoreCandidate(
            "孤独患者", "陈奕迅", 273_000L,
            "孤独患者 (2025 FEAR and DREAMS世界巡回演唱会合肥站)", "陈奕迅", 269_000L,
        )
        assertEquals(0.0, score, 0.001)
    }

    // 采纳线的两侧

    @Test
    fun `a sloppy but genuine match still clears the line`() {
        // 采纳线下沿：歌名相似度 0.8（一个字符之差）、歌手一致、时长差 4 秒，合计 80。
        // 再往下压会把这类真实匹配判成没有歌词
        val score = LyricScorer.scoreCandidate(
            "夜に駆ける", "kiroro", 333_000L,
            "夜に驅ける", "kiroro", 329_000L,
        )
        assertEquals(80.0, score, 0.01)
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `the same title with a different artist is rejected`() {
        // 采纳线上沿：歌名一致但歌手不同，合计 70。再放宽会把同名异曲的歌词配上去
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "高橋洋子", 333_000L,
        )
        assertEquals(70.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a strictly better candidate outscores a slightly worse one`() {
        // 调用方取最高分。分数在饱和区被压平后，该规则退化为取检索首位，
        // 同名的另一版随之随机胜出，日语歌得以配上韩语歌词
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

    // 缺失字段：候选侧缺失扣分，目标侧缺失则重新归一

    @Test
    fun `a candidate that shares only the title is rejected`() {
        // 候选只有歌名，歌手与时长都缺，无法核验；两个维度各丢满分，只剩歌名的 50 分
        val score = LyricScorer.scoreCandidate(
            "晴天", "周杰伦", 269_000L,
            "晴天", "", 0L,
        )
        assertEquals(50.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a title-only target cannot decide identity`() {
        // 目标只报歌名。同名曲目常见（Intro / Interlude），单一维度无法确定身份；
        // 归一后的满分被上限压到 60
        val score = LyricScorer.scoreCandidate(
            "Intro", "", 0L,
            "Intro", "Somebody", 180_000L,
        )
        assertEquals(60.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a target with no artist still accepts an exact title and duration`() {
        // 目标侧缺歌手，该栏从分母中去掉（50 + 20 = 70 的满分），不补常数分；
        // 补分会把采纳线向下挪
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
        // 缺时长栏时由歌手栏单独承担：歌名相同、歌手不同，得分 50 / 80 = 62.5，低于采纳线
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 0L,
            "未来へ", "高橋洋子", 333_000L,
        )
        assertEquals(62.5, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a six second duration gap no longer annihilates an otherwise exact match`() {
        // 宿主上报的时长常是流的长度而非录音长度；6 秒差只丢时长栏的 20 分，
        // 不将整条结果归零
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
