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
package com.hchen.superlyric.engine.multisource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 多源在线歌词匹配打分仲裁器。
 * <p>
 * <b>技术原理：</b>
 * <ul>
 *   <li><b>元数据清洗：</b>去除音轨编号 (01. 02-)、副标题括号、伴奏/Live/Remix 标记、feat 伴唱及文件后缀。</li>
 *   <li><b>归一化编辑距离：</b>计算目标歌名与候选歌名的 Levenshtein 相似度。</li>
 *   <li><b>歌手集合 Jaccard 相似度：</b>切分多位歌手计算集合重合度，防止单曲翻唱混淆。</li>
 *   <li><b>高斯时长容差门限：</b>时长偏差在 1 秒内满分，超 5 秒直接一票否决 (Score=0)。</li>
 *   <li><b>逐字特征增益：</b>含逐字（KRC/QRC/字词 JSON）乘以 1.2 系数，普通 LRC 仅打 0.8 系数。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public final class LyricScorer {
    private static final Pattern CLEAN_TRACK_NO_PATTERN = Pattern.compile(
        "^\\s*(?:track\\s*)?\\d{1,3}\\s*[-._\\s]\\s*",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLEAN_BRACKETS_PATTERN = Pattern.compile(
        "\\s*[\\[\\(（【].*?(?:伴奏|Live|remix|Remix|现场|纯音乐|Instrumental|Cover|翻唱|官方|Remaster|重制|原声|ver\\.|Version).*?[\\]\\)）】]",
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
     */
    public static double calculateTitleSimilarity(@Nullable String target, @Nullable String candidate) {
        String t1 = cleanTitle(target);
        String t2 = cleanTitle(candidate);
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
     * 计算歌手相似度 [0.0, 1.0]。
     */
    public static double calculateArtistSimilarity(@Nullable String target, @Nullable String candidate) {
        Set<String> set1 = splitArtists(target);
        Set<String> set2 = splitArtists(candidate);
        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.5; // 歌手信息缺失时给予中性分
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
     * <p>
     * 误差 <= 1000ms: 100分
     * 误差 <= 3000ms: 80分
     * 误差 <= 5000ms: 50分
     * 误差 > 5000ms: 0分（硬性排除）
     */
    public static double calculateDurationScore(long targetDurationMs, long candidateDurationMs) {
        if (targetDurationMs <= 0 || candidateDurationMs <= 0) {
            // 目标或候选无时长信息，给予中等保守分
            return 70.0;
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
     * 综合评定候选歌曲得分 [0, 100]。
     *
     * @param targetTitle         目标歌名
     * @param targetArtist        目标歌手
     * @param targetDurationMs    目标时长 (毫秒)
     * @param candidateTitle      候选歌名
     * @param candidateArtist     候选歌手
     * @param candidateDurationMs 候选时长 (毫秒)
     * @param hasVerbatim         候选是否具备逐字时序
     * @return 最终综合得分 (>= 80 判定为可采纳)
     */
    public static double scoreCandidate(@Nullable String targetTitle,
                                        @Nullable String targetArtist,
                                        long targetDurationMs,
                                        @Nullable String candidateTitle,
                                        @Nullable String candidateArtist,
                                        long candidateDurationMs,
                                        boolean hasVerbatim) {
        double titleSim = calculateTitleSimilarity(targetTitle, candidateTitle);
        if (titleSim < 0.3) {
            // 歌名严重不符，直接一票否决
            return 0.0;
        }

        double durationScore = calculateDurationScore(targetDurationMs, candidateDurationMs);
        if (targetDurationMs > 0 && candidateDurationMs > 0 && durationScore <= 0.0) {
            // 时长差异过大，一票否决
            return 0.0;
        }

        double artistSim = calculateArtistSimilarity(targetArtist, candidateArtist);

        // 基础得分权重：歌名 45%，歌手 35%，时长 20%
        double baseScore = (titleSim * 100.0 * 0.45)
            + (artistSim * 100.0 * 0.35)
            + (durationScore * 0.20);

        // 逐字特征乘数加成
        double multiplier = hasVerbatim ? 1.20 : 0.80;
        double finalScore = baseScore * multiplier;

        return Math.min(100.0, Math.max(0.0, finalScore));
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
