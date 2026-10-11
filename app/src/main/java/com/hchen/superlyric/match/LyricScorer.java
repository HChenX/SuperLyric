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
package com.hchen.superlyric.match;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多源在线歌词匹配打分仲裁器。
 * <p>
 * <b>技术原理：</b>
 * <ul>
 *   <li><b>元数据清洗：</b>去除音轨编号 (01. 02-)、副标题括号、伴奏/Live/Remix 标记、feat 伴唱及文件后缀。</li>
 *   <li><b>归一化编辑距离：</b>计算目标歌名与候选歌名的 Levenshtein 相似度。</li>
 *   <li><b>歌手集合 Jaccard 相似度：</b>切分多位歌手计算集合重合度，防止单曲翻唱混淆。</li>
 *   <li><b>时长偏差分档：</b>1 秒内满分、3 秒内 80、5 秒内 50、更远 0 分。它只影响时长那一栏
 *       （20 分），不会归零整条结果。</li>
 *   <li><b>缺失字段按"哪一侧缺"分别处理：</b>目标侧缺则该维度不可用并从分母中去掉，
 *       候选侧缺则该维度给 0 分但保留在分母里。详见 [scoreCandidate]。</li>
 * </ul>
 *
 * <p>三档权重与采纳线的定法、以及它们各自守住的样本，都写在 [scoreCandidate] 与
 * [MIN_ACCEPT_SCORE] 上，并已固化成单测。
 *
 * @author 焕晨HChen
 */
public final class LyricScorer {
    private static final Pattern CLEAN_TRACK_NO_PATTERN = Pattern.compile(
        "^\\s*(?:track\\s*)?\\d{1,3}\\s*[-._\\s]\\s*",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLEAN_BRACKETS_PATTERN = Pattern.compile(
        "\\s*[\\[\\(（【].*?(?:伴奏|Live|remix|Remix|现场|纯音乐|Instrumental|Cover|翻唱|官方|Remaster|重制|原声|ver\\.|Version|试听|VIP|vip|片段|Clip|clip|Preview|preview|Audition|audition|铃声|DJ版|高潮版).*?[\\]\\)）】]",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLEAN_SUFFIX_PATTERN = Pattern.compile(
        "\\s*[-/\\s]\\s*(?:试听版?|VIP|vip|片段|高潮版?|铃声版?).*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLEAN_FEAT_PATTERN = Pattern.compile(
        "\\s*(?:feat\\.|ft\\.|featuring).*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLEAN_EXT_PATTERN = Pattern.compile(
        "\\.(mp3|flac|wav|m4a|aac|ogg|ape)$",
        Pattern.CASE_INSENSITIVE
    );
    /**
     * 标题**末尾**的括号段（中英文四种括号），捕获组为括号内容。
     *
     * 白名单式的 {@link #CLEAN_BRACKETS_PATTERN} 只认得「这不是你要的那首歌」的标记
     * （伴奏 / Live / remix …），而中文曲库里更常见的是**译名**：
     * 宿主上报 {@code 未来へ (向着未来)}，平台曲库却是 {@code 未来へ}。
     * 这类括号必须另做一次「去掉后再比」的比较，详见 [calculateTitleSimilarity]。
     */
    private static final Pattern TRAILING_BRACKET_PATTERN = Pattern.compile(
        "\\s*[\\(（\\[【]([^\\)）\\]】]*)[\\)）\\]】]\\s*$"
    );
    /**
     * 版本 / 语言 / 演出标记。
     * <p>
     * 末尾括号里出现这些，说明它标的是「**这是同一首歌的另一版**」而不是译名：
     * {@code (Korean Ver.)}、{@code (DJ豪大大版)}、{@code (2025 巡回演唱会合肥站)}、
     * {@code (双语版)}。带这类标记的括号**绝不能**被剥离——一剥，韩语版、演唱会版
     * 就会和原版同分，然后靠候选顺序随机胜出。
     * <p>
     * 含**数字**也一并视为标记：日期、{@code 1.1x版}、{@code 32秒片段} 都落在这一类。
     */
    private static final Pattern VARIANT_MARKER_PATTERN = Pattern.compile(
        "\\d|ver\\.?|version|remaster|original|remix|live|acoustic|demo|"
            + "chin(a|ese)|japan(ese)?|korean?|english|"
            + "版|伴奏|纯音乐|现场|巡回|演唱会|片段|试听|铃声|双语|原版|重制|翻唱",
        Pattern.CASE_INSENSITIVE
    );
    /**
     * 中日韩文字（含假名、谚文）。见 [stripTranslatedSuffix]：只有它们才会带「原名 (译名)」括号。
     */
    private static final Pattern CJK_PATTERN = Pattern.compile(
        "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af\\uf900-\\ufaff]"
    );

    private LyricScorer() {
    }

    /**
     * 清洗歌曲标题。
     */
    @NonNull
    public static String cleanTitle(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = CLEAN_EXT_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_TRACK_NO_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_BRACKETS_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_SUFFIX_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_FEAT_PATTERN.matcher(s).replaceAll("");
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 清洗并切分歌手名称。
     */
    @NonNull
    public static Set<String> splitArtists(@Nullable String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return set;
        }
        String s = CLEAN_FEAT_PATTERN.matcher(raw.trim()).replaceAll("");
        String[] parts = s.split("[/&,，、|;；]");
        for (String p : parts) {
            String item = p.trim().toLowerCase(Locale.ROOT);
            if (!item.isEmpty()) {
                set.add(item);
            }
        }
        return set;
    }

    /**
     * 计算歌名相似度 [0.0, 1.0]。
     *
     * <p>取「原样比较」与「去掉末尾**译名**括号后比较」两者中的**较高值**。
     * 中文曲库里 {@code 原名 (译名)} 是常态，而平台曲库的标题往往只有原名；
     * 若把译名一并计入长度，同一首歌的相似度会被稀释到门槛之下
     * （实测 {@code 未来へ (向着未来)} vs {@code 未来へ} 只得 0.3，综合 82.2 分而落选）。
     *
     * <p>取 max 是刻意的：它**只会抬高**相似度，不会让原本可采纳的候选反被拒。
     *
     * <p><b>但括号不能无条件剥离</b>：实测把 {@code (Korean Ver.)}、{@code (DJ豪大大版)}、
     * {@code (2025 巡回演唱会合肥站)} 这类**版本标记**一并剥掉后，它们会从"勉强过线或被否决"
     * 直接顶到满分，与原版同分后靠候选顺序随机胜出——日语歌会配上韩语歌词。
     * 因此只在括号内容**不像版本标记**时才剥离，见 {@link #stripTranslatedSuffix}。
     */
    public static double calculateTitleSimilarity(@Nullable String target, @Nullable String candidate) {
        String t1 = cleanTitle(target);
        String t2 = cleanTitle(candidate);
        if (t1.isEmpty() || t2.isEmpty()) return 0.0;

        return Math.max(similarityOf(t1, t2),
            similarityOf(stripTranslatedSuffix(t1), stripTranslatedSuffix(t2)));
    }

    /**
     * 两份**已清洗**标题之间的相似度 [0.0, 1.0]。
     */
    private static double similarityOf(@NonNull String t1, @NonNull String t2) {
        if (t1.isEmpty() || t2.isEmpty()) return 0.0;
        if (t1.equals(t2)) return 1.0;
        if (t1.contains(t2) || t2.contains(t1)) {
            return (double) Math.min(t1.length(), t2.length()) / Math.max(t1.length(), t2.length());
        }

        int dist = levenshteinDistance(t1, t2);
        int maxLen = Math.max(t1.length(), t2.length());
        if (maxLen == 0) return 1.0;
        return Math.max(0.0, 1.0 - ((double) dist / maxLen));
    }

    /**
     * 剥离末尾**译名**括号，可连续剥多段。
     *
     * <p>只有**中日韩文字的标题**才会带「原名 (译名)」这种括号——这是中文曲库的编排惯例。
     * 纯拉丁标题里的括号（{@code (From THE FIRST TAKE)}、{@code (BIG Naughty Remix)}、
     * {@code (English Ver.)}）标的是"另一版"，一律不剥。实测若不设这道判据，
     * {@code NIGHT DANCER (From THE FIRST TAKE)} 会从"被否决"直接顶到 100 分。
     *
     * <p>此外，括号内容带 {@link #VARIANT_MARKER_PATTERN} 标记时也立即停止——
     * 那之后的括号同样标的是"另一版"。
     *
     * @return 剥离后的标题；没有可剥离的括号时原样返回
     */
    @NonNull
    private static String stripTranslatedSuffix(@NonNull String cleaned) {
        String current = cleaned;
        while (true) {
            if (!CJK_PATTERN.matcher(current).find()) return current;

            Matcher matcher = TRAILING_BRACKET_PATTERN.matcher(current);
            if (!matcher.find()) return current;
            if (VARIANT_MARKER_PATTERN.matcher(matcher.group(1)).find()) return current;

            String stripped = matcher.replaceAll("");
            if (stripped.isEmpty() || stripped.equals(current)) return current;
            current = stripped;
        }
    }

    /**
     * 计算歌手相似度 [0.0, 1.0]。
     *
     * <p>任一侧歌手缺失时返回 0.0——"缺失"与"不匹配"在这里**必须同值**。
     * 原实现在缺失时给 0.5 的"中性分"，于是"两个歌手里只对一个"（1/3 ≈ 0.33）
     * 反而比如实缺失得分更低：缺字段成了加分项。
     *
     * <p>缺失该不该扣分、按哪一侧算，由 {@link #scoreCandidate} 决定——它才同时看得见两侧。
     */
    public static double calculateArtistSimilarity(@Nullable String target, @Nullable String candidate) {
        Set<String> set1 = splitArtists(target);
        Set<String> set2 = splitArtists(candidate);
        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }

        int intersection = 0;
        for (String a1 : set1) {
            for (String a2 : set2) {
                if (a1.equals(a2) || a1.contains(a2) || a2.contains(a1)) {
                    intersection++;
                    break;
                }
            }
        }

        int union = set1.size() + set2.size() - intersection;
        if (union <= 0) return 0.0;
        return (double) intersection / union;
    }

    /**
     * 计算音频时长偏差匹配分 [0, 100]。
     *
     * <p>只接受**两侧都是真实时长**的输入；任一侧缺失返回 0。缺失该怎么算由
     * {@link #scoreCandidate} 定，本函数不该有自己的"缺失分"——那正是历史上
     * "不知道时长"（70）反而比"差了 4 秒"（50）得分更高的来源。
     *
     * <p>误差 &lt;= 1000ms: 100分
     * 误差 &lt;= 3000ms: 80分
     * 误差 &lt;= 5000ms: 50分
     * 误差 &gt; 5000ms: 0分
     *
     * <p>另一条是 VIP 试听 / 伴奏片段：目标短于 65 秒而候选是完整曲目时给 80 分
     * （片段时长与完整曲目必然对不上，但歌名歌手一致时它就是同一首歌）。
     */
    public static double calculateDurationScore(long targetDurationMs, long candidateDurationMs) {
        if (targetDurationMs <= 0 || candidateDurationMs <= 0) {
            return 0.0;
        }

        // 关键增强：支持 VIP 试听 / 伴奏片段剪辑 (通常 target 时长在 15s ~ 65s 之间，而完整候选曲目时长 > 65s)
        if (targetDurationMs <= 65000L && candidateDurationMs > 65000L) {
            return 80.0;
        }

        long delta = Math.abs(targetDurationMs - candidateDurationMs);
        if (delta <= 1000L) {
            return 100.0;
        } else if (delta <= 3000L) {
            return 80.0;
        } else if (delta <= 5000L) {
            return 50.0;
        } else {
            return 0.0; // 超出 5 秒直接判错
        }
    }

    /**
     * 歌名维度权重
     *
     * 最高的一档：它是最强的判据，也是**版本判别的唯一依据**（见 {@link #stripTranslatedSuffix}）。
     */
    private static final double TITLE_WEIGHT = 50.0;

    /**
     * 歌手维度权重
     *
     * 低于歌名：宿主上报的歌手字段既可能为空、也可能被写进歌词行之类的内容，
     * 真正可用的机会本就更少。
     */
    private static final double ARTIST_WEIGHT = 30.0;

    /**
     * 时长维度权重
     *
     * 最低的一档：它最客观，但也最容易被"试听片段 / 流的长度"带偏，
     * 因此只该作为旁证，不该能否决另一侧的证据。
     */
    private static final double DURATION_WEIGHT = 20.0;

    /** 歌名相似度下限；低于它直接判 0，不再看其它维度 */
    private static final double MIN_TITLE_SIMILARITY = 0.3;

    /**
     * 目标侧可用维度不足两个时的得分上限
     *
     * 刻意压在采纳线之下：一个维度定不了身份（`Intro`、`Interlude` 这类同名曲目遍地都是），
     * 这正是 §3.5「不得只凭歌名推断曲目身份」在打分器里的落点。
     */
    private static final double THIN_TARGET_CAP = 60.0;

    /**
     * 采纳线：检索阶段的得分必须**严格大于**本值才会被采纳
     *
     * <p>定在 75 的依据是两侧样本的实测得分（均已固化成单测）：
     * <ul>
     *   <li>必须被**采纳**的最低档：歌名相似度 0.8 + 歌手完全一致 + 时长差 4 秒 = <b>80</b></li>
     *   <li>必须被**拒绝**的最高档：歌名完全一致但歌手完全不同 = <b>70</b></li>
     * </ul>
     * 采纳线落在 70 与 80 之间，两侧各余 5 分。改动权重或时长分档时必须重跑那组单测——
     * 它们就是这条线的定义。
     *
     * <p>同一首歌的不同版本可能登记成完全相同的歌名/歌手/时长，打分器无从区分，
     * 平局只能由检索顺序决定（见调用处注释）。
     */
    public static final double MIN_ACCEPT_SCORE = 75.0;

    /**
     * 综合评定候选歌曲得分 [0, 100]。
     *
     * <p>三个维度按权重加权平均，分母只算**可用**的维度。
     *
     * <h3>缺失字段按"哪一侧缺"分别处理</h3>
     * 目标侧（宿主上报）与候选侧（平台检索结果）的可信度**不对称**：
     * <ul>
     *   <li><b>目标侧缺失 ⇒ 该维度不可用</b>，直接从分母里去掉（重新归一）。绝不补一个常数分——
     *       常数分对所有候选一视同仁，等于把采纳线悄悄往下挪一截，而"缺失"看起来就像"匹配上了"</li>
     *   <li><b>候选侧缺失 ⇒ 该维度给 0 分，但保留在分母里</b>。平台检索回来的记录不该缺字段，
     *       缺了就无法核验；宽容它就是重新把"缺字段"变成奖励</li>
     * </ul>
     *
     * <h3>时长不再一票否决</h3>
     * 原实现"双方时长都 &gt; 0 且差 &gt; 5 秒"直接判 0，于是弱信号能抹掉强信号：
     * 歌名歌手完全一致、只差 6 秒的候选被判 0，而"只有歌名对、其余全缺"的候选反而过线。
     * 现在时长超差只把时长那一栏打成 0（丢 20 分），不归零整条结果——宿主上报的时长常常是
     * **流的长度**而非录音长度，"差 6 秒"未必是另一首歌。版本判别由歌名一栏负责。
     *
     * <h3>目标侧只剩歌名时不给结论</h3>
     * 只用歌名无法确定身份，得分被压在 {@link #THIN_TARGET_CAP}——采纳线之下。
     *
     * @param targetTitle         目标歌名
     * @param targetArtist        目标歌手
     * @param targetDurationMs    目标时长 (毫秒)
     * @param candidateTitle      候选歌名
     * @param candidateArtist     候选歌手
     * @param candidateDurationMs 候选时长 (毫秒)
     * @return 最终综合得分 [0, 100]；调用方应与 {@link #MIN_ACCEPT_SCORE} 比较
     */
    public static double scoreCandidate(@Nullable String targetTitle,
                                        @Nullable String targetArtist,
                                        long targetDurationMs,
                                        @Nullable String candidateTitle,
                                        @Nullable String candidateArtist,
                                        long candidateDurationMs) {
        double titleSim = calculateTitleSimilarity(targetTitle, candidateTitle);
        if (titleSim < MIN_TITLE_SIMILARITY) {
            // 歌名严重不符，直接一票否决
            return 0.0;
        }

        boolean targetHasArtist = !splitArtists(targetArtist).isEmpty();
        boolean targetHasDuration = targetDurationMs > 0;

        double artistSim = targetHasArtist && !splitArtists(candidateArtist).isEmpty()
            ? calculateArtistSimilarity(targetArtist, candidateArtist)
            : 0.0;
        double durationFraction = targetHasDuration && candidateDurationMs > 0
            ? calculateDurationScore(targetDurationMs, candidateDurationMs) / 100.0
            : 0.0;

        double earned = titleSim * TITLE_WEIGHT
            + artistSim * ARTIST_WEIGHT
            + durationFraction * DURATION_WEIGHT;
        double total = TITLE_WEIGHT
            + (targetHasArtist ? ARTIST_WEIGHT : 0.0)
            + (targetHasDuration ? DURATION_WEIGHT : 0.0);

        double score = earned / total * 100.0;
        if (!targetHasArtist && !targetHasDuration) {
            return Math.min(score, THIN_TARGET_CAP);
        }
        return score;
    }

    private static int levenshteinDistance(@NonNull String s1, @NonNull String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        int[] prev = new int[len2 + 1];
        int[] curr = new int[len2 + 1];

        for (int j = 0; j <= len2; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= len1; i++) {
            curr[0] = i;
            char c1 = s1.charAt(i - 1);
            for (int j = 1; j <= len2; j++) {
                char c2 = s2.charAt(j - 1);
                int cost = (c1 == c2) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, len2 + 1);
        }

        return prev[len2];
    }
}
