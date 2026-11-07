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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对多个在线歌词候选的标题、歌手、时长打分
 *
 * <p>标题用清洗后的 Levenshtein 相似度，歌手用集合重合度，时长只作为低权重维度。
 * 缺失字段的计分方式由 {@link #scoreCandidate} 按目标侧与候选侧分别处理。
 *
 */
public final class LyricScorer {
    private static final Pattern CLEAN_TRACK_NO_PATTERN = Pattern.compile(
        "^\\s*(?:track\\s*)?\\d{1,3}\\s*[-._\\s]\\s*",
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
     * 标题末尾的括号段（中英文四种括号），捕获组为括号内容
     *
     * <p>版本标记（伴奏 / Live / remix …）不在这里剥离，它们决定"这是另一版"，
     * 见 {@link #variantMismatch}。中文曲库里更常见的是译名：宿主上报
     * {@code 未来へ (向着未来)}，平台曲库却是 {@code 未来へ}；这类括号另做一次
     * 「去掉后再比」的比较，见 {@link #calculateTitleSimilarity}。
     */
    private static final Pattern TRAILING_BRACKET_PATTERN = Pattern.compile(
        "\\s*[\\(（\\[【]([^\\)）\\]】]*)[\\)）\\]】]\\s*$"
    );
    /**
     * 版本 / 语言 / 演出标记
     *
     * <p>末尾括号里出现这些，说明它标的是「同一首歌的另一版」而不是译名：
     * {@code (Korean Ver.)}、{@code (DJ豪大大版)}、{@code (2025 巡回演唱会合肥站)}、
     * {@code (双语版)}。带这类标记的括号不得剥离：剥离后韩语版、演唱会版会与原版
     * 同分，胜负只能由候选顺序决定。
     *
     * <p>含数字也一并视为标记：日期、{@code 1.1x版}、{@code 32秒片段} 都落在这一类。
     */
    private static final Pattern VARIANT_MARKER_PATTERN = Pattern.compile(
        "(?<![a-z])(?:\\d|ver\\.?|version|remaster|original|remix|live|acoustic|demo|"
            + "chin(a|ese)|japan(ese)?|korean?|english|"
            + "版|伴奏|纯音乐|现场|巡回|演唱会|片段|试听|铃声|双语|原版|重制|翻唱)(?![a-z])",
        Pattern.CASE_INSENSITIVE
    );
    /**
     * 中日韩文字（含假名、谚文）
     *
     * <p>只有这类标题才会带「原名 (译名)」括号，见 {@link #stripTranslatedSuffix}。
     */
    private static final Pattern CJK_PATTERN = Pattern.compile(
        "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af\\uf900-\\ufaff]"
    );

    private LyricScorer() {
    }

    /**
     * 清洗歌名：去掉扩展名、曲目序号、试听与铃声后缀、feat 段，并统一小写
     */
    @NonNull
    public static String cleanTitle(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = CLEAN_EXT_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_TRACK_NO_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_SUFFIX_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_FEAT_PATTERN.matcher(s).replaceAll("");
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 清洗并切分歌手：按 {@code / & , ， 、 | ; ；} 分隔，去 feat 段，统一小写
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
     * 两侧末尾括号都带版本标记且内容不同，或只有一侧带标记时，判为不同的版本
     *
     * <p>必须在清洗前判断：版本标记一旦被括号清洗剥掉，后面的相似度就没有可比较的
     * 对象，韩语版、Live、伴奏会与原版同分。
     */
    private static boolean variantMismatch(@Nullable String target, @Nullable String candidate) {
        if (target == null || candidate == null) return false;
        String left = trailingBracket(target);
        String right = trailingBracket(candidate);
        if (left == null && right == null) return false;

        boolean leftVariant = left != null && VARIANT_MARKER_PATTERN.matcher(left).find();
        boolean rightVariant = right != null && VARIANT_MARKER_PATTERN.matcher(right).find();
        if (!leftVariant && !rightVariant) return false;
        if (left == null || right == null) return true;
        return !left.trim().equalsIgnoreCase(right.trim());
    }

    @Nullable
    private static String trailingBracket(@NonNull String raw) {
        Matcher matcher = TRAILING_BRACKET_PATTERN.matcher(raw.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 歌名相似度 [0.0, 1.0]，取原样比较与剥离译名括号后比较的较高值。
     * <p>
     * 括号不能无条件剥离，否则韩语版、现场版会与原版同分，见 stripTranslatedSuffix。
     */
    public static double calculateTitleSimilarity(@Nullable String target, @Nullable String candidate) {
        if (variantMismatch(target, candidate)) return 0.0;
        String t1 = cleanTitle(target);
        String t2 = cleanTitle(candidate);
        if (t1.isEmpty() || t2.isEmpty()) return 0.0;

        return Math.max(similarityOf(t1, t2),
            similarityOf(stripTranslatedSuffix(t1), stripTranslatedSuffix(t2)));
    }

    /**
     * 两份已清洗标题之间的相似度 [0.0, 1.0]
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
     * 剥离末尾译名括号，可连续剥多段。
     * <p>
     * 只处理中日韩标题；括号内容像版本标记（Live、Remix、语言、数字等）时立即停止。
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
     * 计算歌手相似度 [0.0, 1.0]
     *
     * <p>任一侧歌手缺失时返回 0.0："缺失"与"不匹配"同值，给缺失"中性分"会让缺字段
     * 反而比部分匹配得分更高。
     *
     * <p>缺失该不该扣分、按哪一侧算，由 {@link #scoreCandidate} 决定，它同时看得见两侧。
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
     * 时长偏差分 [0, 100]；任一侧时长未知返回 0。
     * <p>
     * 误差 1s/3s/5s 内分别给 100/80/50，超过 5s 给 0。目标短于 65s 而候选是完整曲目时
     * 给 80，覆盖试听与伴奏片段。
     */
    public static double calculateDurationScore(long targetDurationMs, long candidateDurationMs) {
        if (targetDurationMs <= 0 || candidateDurationMs <= 0) {
            return 0.0;
        }

        // VIP 试听 / 伴奏片段剪辑：目标时长通常为 15s~65s，完整候选曲目 > 65s
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
            // 误差超过 5 秒判为不匹配
            return 0.0;
        }
    }

    /**
     * 歌名权重；最强的判据，也是版本判别的依据。
     */
    private static final double TITLE_WEIGHT = 50.0;

    /**
     * 歌手权重；低于歌名，宿主上报的歌手字段可用机会更少。
     */
    private static final double ARTIST_WEIGHT = 30.0;

    /**
     * 时长权重；只作旁证，不否决其他维度。
     */
    private static final double DURATION_WEIGHT = 20.0;

    /**
     * 歌名相似度下限；低于它直接判 0。
     */
    private static final double MIN_TITLE_SIMILARITY = 0.3;

    /**
     * 目标侧可用维度不足两个时的得分上限，刻意压在采纳线之下。
     */
    private static final double THIN_TARGET_CAP = 60.0;

    /**
     * 采纳线：得分必须严格大于本值。
     * <p>
     * 实测最低可采纳档为 80，最高应拒绝档为 70，采纳线取 75。改动权重或时长分档须重跑
     * 对应单测；同分时按检索顺序决定。
     */
    public static final double MIN_ACCEPT_SCORE = 75.0;

    /**
     * 综合评定候选歌曲得分 [0, 100]。
     * <p>
     * 目标侧缺失的维度从分母去掉；候选侧缺失给 0 分但保留分母。时长只作旁证，不否决
     * 整条结果；目标侧只剩歌名时得分被压在 THIN_TARGET_CAP 以下。
     */
    public static double scoreCandidate(@Nullable String targetTitle,
                                        @Nullable String targetArtist,
                                        long targetDurationMs,
                                        @Nullable String candidateTitle,
                                        @Nullable String candidateArtist,
                                        long candidateDurationMs) {
        double titleSim = calculateTitleSimilarity(targetTitle, candidateTitle);
        if (titleSim < MIN_TITLE_SIMILARITY) {
            // 歌名低于下限，直接判 0，不再看其它维度
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
