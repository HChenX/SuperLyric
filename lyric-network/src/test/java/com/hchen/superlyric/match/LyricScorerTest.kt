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
    fun `source attribution must not sink an exact match`() {
        // 酷狗音乐 20.8.8 上报 "Zoo (From \"Zootopia 2\")"，平台曲库为 "Zoo"，歌手与时长一致；
        // 若把 "From ..." 当成版本标记，所有候选都会落选并判为无歌词
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("Zoo (From \"Zootopia 2\")", "Zoo"),
            0.001,
        )
        assertEquals(
            100.0,
            LyricScorer.scoreCandidate(
                "Zoo (From \"Zootopia 2\")", "Disney、Shakira", 190_000L,
                "Zoo", "Disney、Shakira", 190_000L,
            ),
            0.01,
        )
        assertEquals(
            100.0,
            LyricScorer.scoreCandidate(
                "Zoo", "Disney、Shakira", 190_000L,
                "Zoo (From \"Zootopia 2\")", "Disney、Shakira", 190_000L,
            ),
            0.01,
        )
    }

    @Test
    fun `explicit source attributions match in either title direction`() {
        listOf(
            "Song (From \"Movie\")",
            "Song (From the movie \"Movie\")",
            "Song (From the album \"Album\")",
            "Song (From the original soundtrack)",
            "Song (Original Soundtrack)",
            "Song (Music from \"Movie\")",
            "Song (Theme from \"Movie\")",
            "Song (Original Motion Picture Soundtrack)",
            "Song - From \"Movie\"",
        ).forEach { decorated ->
            assertEquals(
                1.0,
                LyricScorer.calculateTitleSimilarity(decorated, "Song"),
                0.001,
                decorated,
            )
            assertEquals(
                1.0,
                LyricScorer.calculateTitleSimilarity("Song", decorated),
                0.001,
                decorated,
            )
        }
    }

    @Test
    fun `a version marker next to a source attribution is still rejected`() {
        listOf(
            "Song (From \"Movie\") (Live)",
            "Song (From \"Movie\") - Remix",
            "Song (Instrumental) (From \"Movie\")",
            "Song (Music from \"Movie\" Live)",
            "Song (Soundtrack Version)",
        ).forEach { decorated ->
            assertEquals(
                0.0,
                LyricScorer.calculateTitleSimilarity(decorated, "Song"),
                0.001,
                decorated,
            )
        }
    }

    @Test
    fun `unquoted from and alternate-release wording stay version markers`() {
        // 加引号的出处不改变录音；未加引号的 From 仍可能是另一版录音（如 From The Vault）
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Nobody - from Kaiju No. 8", "Nobody"), 0.001)
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Song (From The Vault)", "Song"), 0.001)
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
        val latinOliver = LyricScorer.calculateTitleSimilarity("NIGHT DANCER (Oliver)", "NIGHT DANCER")
        assertTrue(latinOliver > 0.0, "Oliver 不得被判成 Live")
        assertTrue(latinOliver < 0.5, "普通包含关系仍要压在采纳边界下")
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
        // 歌名相似度 0.893（Jaro-Winkler 对一次换位的短标题），歌手一致、时长差 4 秒
        val score = LyricScorer.scoreCandidate(
            "夜に駆ける", "kiroro", 333_000L,
            "夜に驅ける", "kiroro", 329_000L,
        )
        assertEquals(84.67, score, 0.01)
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `the same title with a different artist is rejected`() {
        // 采纳线上沿：歌名一致但同文字系统的歌手不同，合计 70
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "tove styrke", 333_000L,
        )
        assertEquals(70.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a strictly better candidate outscores a slightly worse one`() {
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
        val score = LyricScorer.scoreCandidate(
            "晴天", "周杰伦", 269_000L,
            "晴天", "", 0L,
        )
        assertEquals(50.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a title-only target cannot decide identity`() {
        val score = LyricScorer.scoreCandidate(
            "Intro", "", 0L,
            "Intro", "Somebody", 180_000L,
        )
        assertEquals(60.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a target with no artist still accepts an exact title and duration`() {
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
    fun `without a target duration a same-script wrong artist is no longer affordable`() {
        // 缺时长栏时由歌手栏单独承担：歌名相同、同文字系统歌手不同，得分 50 / 80 = 62.5
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 0L,
            "未来へ", "tove styrke", 333_000L,
        )
        assertEquals(62.5, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `a six second duration gap no longer annihilates an otherwise exact match`() {
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "kiroro", 327_000L,
        )
        assertEquals(80.0, score, 0.01)
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    // Jaro-Winkler 与归一化

    @Test
    fun `Jaro-Winkler lifts a short transposition typo`() {
        val similarity = LyricScorer.calculateTitleSimilarity("Martha", "Marhta")
        assertEquals(0.9611, similarity, 0.001)
        val score = LyricScorer.scoreCandidate(
            "Martha", "artist", 100_000L,
            "Marhta", "artist", 100_000L,
        )
        assertEquals(98.06, score, 0.01)
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `plain containment is capped below the acceptance boundary`() {
        // 缺词是普通包含关系，不是换位；比例低于 0.7 时不能靠歌手/时长抬过 75
        assertEquals(0.45, LyricScorer.calculateTitleSimilarity("Midnight City", "Midnight"), 0.001)
        val score = LyricScorer.scoreCandidate(
            "Midnight City", "M83", 244_000L,
            "Midnight", "M83", 244_000L,
        )
        assertEquals(72.5, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `short Latin titles need an exact token before Jaro-Winkler applies`() {
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Love", "Live"), 0.001)
        val wrong = LyricScorer.scoreCandidate(
            "Love", "artist", 200_000L,
            "Live", "artist", 200_000L,
        )
        assertTrue(wrong < ACCEPT_SCORE, "score=$wrong")
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("M83", "M38"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("M83", "M83"), 0.001)
        assertEquals(
            100.0,
            LyricScorer.scoreCandidate("M83", "artist", 200_000L, "M83", "artist", 200_000L),
            0.01,
        )
    }

    @Test
    fun `normalisation removes diacritics and punctuation`() {
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Beyoncé", "Beyonce"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Café", "Cafe"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Anti-Hero", "Anti Hero"), 0.001)
    }

    @Test
    fun `NFKC normalises full-width characters`() {
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Ａｎｔｉ－Ｈｅｒｏ", "Anti-Hero"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("未来へ（向着未来）", "未来へ"), 0.001)
    }

    @Test
    fun `spaced Latin artist letters collapse but CJK single characters do not`() {
        assertEquals(
            setOf("ritual", "tove styrke"),
            LyricScorer.splitArtists("R I T U A L / Tove Styrke"),
        )
        assertEquals(1.0, LyricScorer.calculateArtistSimilarity("R I T U A L", "RITUAL"), 0.001)
        assertEquals(setOf("周 杰 伦"), LyricScorer.splitArtists("周 杰 伦"))
        assertTrue("周杰伦" !in LyricScorer.splitArtists("周 杰 伦"))
        assertEquals(0.0, LyricScorer.calculateArtistSimilarity("周 杰 伦", "周杰伦"), 0.001)
    }

    @Test
    fun `artist punctuation and diacritics are normalised`() {
        assertEquals(1.0, LyricScorer.calculateArtistSimilarity("Tyler, The Creator", "Tyler The Creator"), 0.001)
        assertEquals(1.0, LyricScorer.calculateArtistSimilarity("Beyoncé", "Beyonce"), 0.001)
    }

    @Test
    fun `artist Jaro-Winkler supplements a near-identical name`() {
        assertEquals(0.9818, LyricScorer.calculateArtistSimilarity("Tove Styrke", "Tove Stryke"), 0.001)
    }

    @Test
    fun `artist token overlap supplements a partial credit`() {
        assertEquals(
            0.9143,
            LyricScorer.calculateArtistSimilarity("Taylor Swift", "Taylor Swift / ILLENIUM"),
            0.001,
        )
        val score = LyricScorer.scoreCandidate(
            "Anti-Hero - ILLENIUM Remix", "Taylor Swift", 267_000L,
            "Anti-Hero (Illenium Remix)", "Taylor Swift / ILLENIUM", 267_000L,
        )
        assertEquals(97.43, score, 0.01)
    }

    @Test
    fun `artist version extras do not get token or Jaro-Winkler credit`() {
        listOf(
            "The Weeknd" to "The Weeknd Tribute",
            "Taylor Swift" to "Taylor Swift Tribute",
            "Adele" to "Adele Cover",
            "Taylor Swift" to "Taylor Swift Version",
            "The Weeknd" to "The Weeknd Original",
            "Adele" to "Adele From Movie",
            "The Weeknd" to "The Weeknd Ver",
        ).forEach { (targetArtist, candidateArtist) ->
            assertEquals(
                0.0,
                LyricScorer.calculateArtistSimilarity(targetArtist, candidateArtist),
                0.001,
                candidateArtist,
            )
            val score = LyricScorer.scoreCandidate(
                "Song", targetArtist, 200_000L,
                "Song", candidateArtist, 200_000L,
            )
            assertEquals(70.0, score, 0.01, candidateArtist)
            assertTrue(score < ACCEPT_SCORE, "score=$score")
        }
    }

    @Test
    fun `letter overlap alone does not lift a wrong artist`() {
        assertEquals(0.0, LyricScorer.calculateArtistSimilarity("kiroro", "tove styrke"), 0.001)
        assertEquals(0.0, LyricScorer.calculateArtistSimilarity("M83", "Coldplay"), 0.001)
    }

    @Test
    fun `common words do not lift a wrong artist`() {
        assertEquals(0.0, LyricScorer.calculateArtistSimilarity("The Weeknd", "The Chainsmokers"), 0.001)
        assertEquals(0.0, LyricScorer.calculateArtistSimilarity("John Smith", "John Doe"), 0.001)
    }

    // 跨文字系统与错误歌手

    @Test
    fun `cross-script artist aliases are capped between acceptance and exact`() {
        val score = LyricScorer.scoreCandidate(
            "新宝島", "魚韻", 305_000L,
            "新宝島", "sakanaction", 306_000L,
        )
        assertEquals(82.0, score, 0.01)
        assertTrue(score >= ACCEPT_SCORE, "score=$score")
        assertTrue(score < 100.0, "score=$score")
    }

    @Test
    fun `cross-script version words fall back to the normal artist penalty`() {
        listOf("Tribute", "Version", "Original").forEach { extra ->
            val score = LyricScorer.scoreCandidate(
                "新宝島", "魚韻", 305_000L,
                "新宝島", "sakanaction $extra", 305_000L,
            )
            assertEquals(70.0, score, 0.01, extra)
            assertTrue(score < ACCEPT_SCORE, "score=$score")
        }
        assertEquals(
            82.0,
            LyricScorer.scoreCandidate(
                "新宝島", "魚韻", 305_000L,
                "新宝島", "sakanaction", 305_000L,
            ),
            0.01,
        )
    }

    @Test
    fun `kana and Latin artist names are not comparable`() {
        val score = LyricScorer.scoreCandidate(
            "新宝島", "サカナクション", 305_000L,
            "新宝島", "sakanaction", 305_000L,
        )
        assertEquals(82.0, score, 0.01)
    }

    @Test
    fun `cross-script alias with duration drift stays capped`() {
        assertEquals(
            82.0,
            LyricScorer.scoreCandidate(
                "新宝島", "魚韻", 300_000L,
                "新宝島", "sakanaction", 305_000L,
            ),
            0.01,
        )
        val sixSecondGap = LyricScorer.scoreCandidate(
            "新宝島", "魚韻", 305_000L,
            "新宝島", "sakanaction", 311_000L,
        )
        assertEquals(71.43, sixSecondGap, 0.01)
        assertTrue(sixSecondGap < ACCEPT_SCORE, "score=$sixSecondGap")
        val tenSecondGap = LyricScorer.scoreCandidate(
            "新宝島", "魚韻", 305_000L,
            "新宝島", "sakanaction", 315_000L,
        )
        assertEquals(71.43, tenSecondGap, 0.01)
        assertTrue(tenSecondGap < ACCEPT_SCORE, "score=$tenSecondGap")
    }

    @Test
    fun `a verified same-script candidate outranks a cross-script alias`() {
        val crossScript = LyricScorer.scoreCandidate(
            "夜に駆ける", "kiroro", 333_000L,
            "夜に駆ける", "高橋洋子", 333_000L,
        )
        val verified = LyricScorer.scoreCandidate(
            "夜に駆ける", "kiroro", 333_000L,
            "夜に驅ける", "kiroro", 329_000L,
        )
        assertEquals(82.0, crossScript, 0.01)
        assertEquals(84.67, verified, 0.01)
        assertTrue(verified > crossScript, "verified=$verified, cross=$crossScript")
    }

    @Test
    fun `acceptance boundary ordering remains intact`() {
        val leading = LyricScorer.scoreCandidate(
            "(What's) The Story", "artist", 200_000L,
            "The Story", "artist", 200_000L,
        )
        val containment = LyricScorer.scoreCandidate(
            "Midnight City", "M83", 244_000L,
            "Midnight", "M83", 244_000L,
        )
        val sameScriptWrong = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "tove styrke", 333_000L,
        )
        val crossScript = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 333_000L,
            "未来へ", "高橋洋子", 333_000L,
        )
        val verified = LyricScorer.scoreCandidate(
            "夜に駆ける", "kiroro", 333_000L,
            "夜に驅ける", "kiroro", 329_000L,
        )

        assertEquals(72.5, leading, 0.01)
        assertEquals(72.5, containment, 0.01)
        assertEquals(70.0, sameScriptWrong, 0.01)
        assertEquals(82.0, crossScript, 0.01)
        assertEquals(84.67, verified, 0.01)
        assertTrue(leading < ACCEPT_SCORE)
        assertTrue(containment < ACCEPT_SCORE)
        assertTrue(sameScriptWrong < ACCEPT_SCORE)
        assertTrue(crossScript > ACCEPT_SCORE)
        assertTrue(verified > crossScript)
    }

    @Test
    fun `a cross-script alias without duration is still capped`() {
        val score = LyricScorer.scoreCandidate(
            "未来へ", "kiroro", 0L,
            "未来へ", "高橋洋子", 333_000L,
        )
        assertEquals(82.0, score, 0.01)
    }

    @Test
    fun `mixed-script artist names are compared normally`() {
        val score = LyricScorer.scoreCandidate(
            "新宝島", "X玖少年团", 305_000L,
            "新宝島", "sakanaction", 305_000L,
        )
        assertEquals(70.0, score, 0.01)
        assertTrue(score < ACCEPT_SCORE, "score=$score")
    }

    @Test
    fun `candidate artist missing remains a candidate-side penalty`() {
        assertEquals(
            70.0,
            LyricScorer.scoreCandidate(
                "晴天", "周杰伦", 269_000L,
                "晴天", "", 269_000L,
            ),
            0.01,
        )
        assertEquals(
            50.0,
            LyricScorer.scoreCandidate(
                "晴天", "周杰伦", 269_000L,
                "晴天", "", 0L,
            ),
            0.01,
        )
    }

    // 括号、版本与分隔后缀

    @Test
    fun `version marker in any bracket or delimiter suffix is rejected`() {
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("(Live) Song", "Song"), 0.001)
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Song (Live) Remix", "Song Remix"), 0.001)
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Song - Live", "Song"), 0.001)
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Song (Instrumental)", "Song"), 0.001)
    }

    @Test
    fun `plain trailing suffix version markers are rejected`() {
        listOf(
            "NIGHT DANCER Live",
            "NIGHT DANCER Remastered",
            "NIGHT DANCER (Remastered)",
            "NIGHT DANCER (Cover)",
            "NIGHT DANCER (Tribute)",
            "NIGHT DANCER (Nightcore)",
            "NIGHT DANCER (Sped Up)",
            "NIGHT DANCER (Slowed)",
            "NIGHT DANCER (Orchestral)",
            "NIGHT DANCER Sped Up",
            "NIGHT DANCER 2025",
            "NIGHT DANCER 2",
            "NIGHT DANCER 1.1x",
            "NIGHT DANCER Korean",
            "NIGHT DANCER Japanese",
            "NIGHT DANCER Chinese",
            "NIGHT DANCER English",
            "NIGHT DANCER From Movie",
            "NIGHT DANCER 版",
            "NIGHT DANCER 伴奏",
            "NIGHT DANCER 纯音乐",
            "NIGHT DANCER 现场",
            "NIGHT DANCER 巡回",
            "NIGHT DANCER 演唱会",
            "NIGHT DANCER 片段",
            "NIGHT DANCER 试听",
            "NIGHT DANCER 铃声",
            "NIGHT DANCER 双语",
            "NIGHT DANCER 原版",
            "NIGHT DANCER 重制",
            "NIGHT DANCER 翻唱",
        ).forEach { title ->
            assertEquals(
                0.0,
                LyricScorer.calculateTitleSimilarity(title, "NIGHT DANCER"),
                0.001,
                title,
            )
        }
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER Live", "NIGHT DANCER (Live)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER Remastered", "NIGHT DANCER (Remastered)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 2025", "NIGHT DANCER (2025)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 1.1x", "NIGHT DANCER (1.1x)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER Korean", "NIGHT DANCER (Korean)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER From Movie", "NIGHT DANCER (From Movie)"),
            0.001,
        )
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("NIGHT DANCER版", "NIGHT DANCER"), 0.001)
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("未来へ版", "未来へ"), 0.001)
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 版", "NIGHT DANCER (版)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 伴奏", "NIGHT DANCER (伴奏)"),
            0.001,
        )
    }

    @Test
    fun `feat and VIP decorations do not hide a plain version suffix`() {
        listOf(
            "NIGHT DANCER Live feat. X",
            "NIGHT DANCER Live VIP",
            "NIGHT DANCER Live ft. X",
            "NIGHT DANCER Live featuring X",
            "NIGHT DANCER Live 试听",
        ).forEach { title ->
            assertEquals(
                0.0,
                LyricScorer.calculateTitleSimilarity(title, "NIGHT DANCER"),
                0.001,
                title,
            )
        }
    }

    @Test
    fun `CJK combined plain suffixes tokenize into markers`() {
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 重制版", "NIGHT DANCER (重制版)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 伴奏版", "NIGHT DANCER (伴奏版)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 演唱会版", "NIGHT DANCER (演唱会版)"),
            0.001,
        )
        assertEquals(
            0.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 重制版", "NIGHT DANCER"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER 重制", "NIGHT DANCER (重制)"),
            0.001,
        )
    }

    @Test
    fun `multiple plain trailing markers are all collected`() {
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER Live Remix", "NIGHT DANCER (Live) (Remix)"),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity(
                "NIGHT DANCER Live Remix 2025",
                "NIGHT DANCER (Live) (Remix) (2025)",
            ),
            0.001,
        )
        assertEquals(
            0.0,
            LyricScorer.calculateTitleSimilarity("NIGHT DANCER Live Remix", "NIGHT DANCER"),
            0.001,
        )
    }

    @Test
    fun `version signatures tokenize suffix and bracket contents`() {
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity(
                "NIGHT DANCER - Live Remix",
                "NIGHT DANCER (Live) (Remix)",
            ),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity(
                "NIGHT DANCER (Live Remix)",
                "NIGHT DANCER (Live) (Remix)",
            ),
            0.001,
        )
        assertEquals(
            0.0,
            LyricScorer.calculateTitleSimilarity(
                "NIGHT DANCER - Live at Wembley",
                "NIGHT DANCER (Live)",
            ),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity(
                "NIGHT DANCER - Live at Wembley",
                "NIGHT DANCER (Live at Wembley)",
            ),
            0.001,
        )
    }

    @Test
    fun `delimiter suffix and bracket with the same marker are equivalent`() {
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity(
                "Anti-Hero - ILLENIUM Remix",
                "Anti-Hero (Illenium Remix)",
            ),
            0.001,
        )
        assertEquals(
            1.0,
            LyricScorer.calculateTitleSimilarity(
                "Nobody - from Kaiju No. 8",
                "Nobody (from Kaiju No. 8)",
            ),
            0.001,
        )
    }

    @Test
    fun `non-version brackets are noise`() {
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Midnight City (Radio Edit)", "Midnight City"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("(Explicit) Midnight City", "Midnight City"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Song (Deluxe Edition)", "Song"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Song (Digital)", "Song"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Song (Album Edit)", "Song"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("Song (Explicit Version)", "Song"), 0.001)
        // 单独的 Version / Album Version 仍是版本标记，不得当噪声剥掉
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Song (Version)", "Song"), 0.001)
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Song (Album Version)", "Song"), 0.001)
    }

    @Test
    fun `leading meaningful bracket is not stripped`() {
        assertEquals(0.4, LyricScorer.calculateTitleSimilarity("(Intro) Song", "Song"), 0.001)
        assertEquals(0.45, LyricScorer.calculateTitleSimilarity("(What's) The Story", "The Story"), 0.001)
        val score = LyricScorer.scoreCandidate(
            "(What's) The Story", "artist", 200_000L,
            "The Story", "artist", 200_000L,
        )
        assertEquals(72.5, score, 0.01)
        assertTrue(score <= ACCEPT_SCORE - 2.0, "score=$score")
    }

    @Test
    fun `leading bracket text outside brackets still matches`() {
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("(What's) The Story", "What's The Story"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("(Intro) Song", "Intro Song"), 0.001)
    }

    @Test
    fun `instrumental and karaoke qualifiers are version markers`() {
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Song (Instrumental)", "Song"), 0.001)
        assertEquals(0.0, LyricScorer.calculateTitleSimilarity("Song (Karaoke)", "Song"), 0.001)
    }

    @Test
    fun `translation brackets do not lower the title`() {
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("未来へ (向着未来)", "未来へ"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("未来へ（向着未来）", "未来へ"), 0.001)
        assertEquals(1.0, LyricScorer.calculateTitleSimilarity("未来へ [向着未来]", "未来へ"), 0.001)
        assertTrue(LyricScorer.calculateTitleSimilarity("未来へ (Oliver)", "未来へ") > 0.5)
    }

    @Test
    fun `duration tiers remain strict`() {
        assertEquals(100.0, LyricScorer.calculateDurationScore(333_000L, 333_000L), 0.001)
        assertEquals(100.0, LyricScorer.calculateDurationScore(333_000L, 332_000L), 0.001)
        assertEquals(80.0, LyricScorer.calculateDurationScore(333_000L, 330_000L), 0.001)
        assertEquals(50.0, LyricScorer.calculateDurationScore(333_000L, 328_000L), 0.001)
        assertEquals(0.0, LyricScorer.calculateDurationScore(333_000L, 327_000L), 0.001)
        assertEquals(80.0, LyricScorer.calculateDurationScore(30_000L, 300_000L), 0.001)

        assertEquals(
            100.0,
            LyricScorer.scoreCandidate("未来へ", "kiroro", 333_000L, "未来へ", "kiroro", 332_000L),
            0.01,
        )
        assertEquals(
            96.0,
            LyricScorer.scoreCandidate("未来へ", "kiroro", 333_000L, "未来へ", "kiroro", 330_000L),
            0.01,
        )
        assertEquals(
            90.0,
            LyricScorer.scoreCandidate("未来へ", "kiroro", 333_000L, "未来へ", "kiroro", 328_000L),
            0.01,
        )
        assertEquals(
            80.0,
            LyricScorer.scoreCandidate("未来へ", "kiroro", 333_000L, "未来へ", "kiroro", 327_000L),
            0.01,
        )
        assertEquals(
            96.0,
            LyricScorer.scoreCandidate("未来へ", "kiroro", 30_000L, "未来へ", "kiroro", 300_000L),
            0.01,
        )
    }

    @Test
    fun `existing title decoration cleanup still applies`() {
        assertEquals("song", LyricScorer.cleanTitle("01 - Song.mp3"))
        assertEquals("song", LyricScorer.cleanTitle("Song.flac"))
        assertEquals("song", LyricScorer.cleanTitle("Song VIP"))
        assertEquals("song", LyricScorer.cleanTitle("Song feat. Artist"))
    }

    @Test
    fun `the acceptance line remains 75`() {
        assertEquals(75.0, LyricScorer.MIN_ACCEPT_SCORE, 0.0)
    }

    private companion object {
        const val ACCEPT_SCORE = 75.0
    }
}
