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

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对多个在线歌词候选的标题、歌手、时长打分
 *
 * <p>标题先做归一化，再按完全相等、包含比例、Levenshtein 与 Jaro-Winkler 分层比较；
 * 歌手以集合重合为主，token overlap 与 Jaro-Winkler 只作补充。时长只作低权重维度。
 * 缺失字段的计分方式由 {@link #scoreCandidate} 按目标侧与候选侧分别处理。
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
     * 任意位置的括号段（中英文四种括号），捕获组为括号内容
     */
    private static final Pattern BRACKET_PATTERN = Pattern.compile(
        "[\\(（\\[【]([^\\)）\\]】]*)[\\)）\\]】]"
    );
    /**
     * 标题末尾的括号段，捕获组为括号内容
     *
     * <p>只用于剥离中日韩标题末尾的译名括号；版本标记不在这里剥离，见
     * {@link #variantMismatch} 与 {@link #stripTrailingTranslation}。
     */
    private static final Pattern TRAILING_BRACKET_PATTERN = Pattern.compile(
        "\\s*[\\(（\\[【]([^\\)）\\]】]*)[\\)）\\]】]\\s*$"
    );
    /**
     * 标题开头的括号段，捕获组为括号内容
     */
    private static final Pattern LEADING_BRACKET_PATTERN = Pattern.compile(
        "^\\s*[\\(（\\[【]([^\\)）\\]】]*)[\\)）\\]】]"
    );
    /**
     * 录音版本词；版本标记与出处注解的版本守卫共用，避免新增版本词时两处分叉
     */
    private static final String VERSION_MARKER_TOKENS =
        "ver\\.?|version|remaster(?:ed)?|remix|live|acoustic|demo|cover|tribute|"
            + "nightcore|sped\\s+up|slowed|orchestral|instrumental|karaoke|"
            + "伴奏|纯音乐|现场|巡回|演唱会|片段|试听|铃声|双语|原版|重制|翻唱|版";
    /**
     * 作品出处注解，例如 {@code From "Zootopia 2"}、{@code Music from Zootopia 2}
     *
     * <p>它说明歌曲出自哪部作品，不区分录音版本；平台曲库常省略这段，按版本标记
     * 处理会让完全相同的曲目落选。只接受带引号的作品名或明确的影视/原声措辞，
     * 避免把 {@code From The Vault}、{@code Nobody - from Kaiju No. 8} 这类
     * 未加引号且可能指向另一版录音的标注一并放宽。
     */
    private static final Pattern SOURCE_ATTRIBUTION_PATTERN = Pattern.compile(
        "^(?:from\\s+[\"“”'‘’].+[\"“”'‘’]"
            + "|from\\s+the\\s+(?:(?:original|motion\\s+picture)\\s+)*"
            + "(?:movie|film|series|show|game|soundtrack|album|anime|musical|play)\\b.*"
            + "|(?:music|theme)\\s+from\\b.*"
            + "|(?:original\\s+)?(?:motion\\s+picture\\s+)?soundtrack\\b.*)\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    /**
     * 录音版本词；出处注解里出现它时不得按注解剥离
     *
     * <p>{@code Soundtrack Version}、{@code Music from "Movie" Live} 仍然指向另一版录音。
     * 这里刻意不含 {@code original}，否则 {@code Original Soundtrack} 会被误判。
     */
    private static final Pattern RECORDING_VERSION_PATTERN = Pattern.compile(
        "(?<![a-z])(?:" + VERSION_MARKER_TOKENS + ")(?![a-z])",
        Pattern.CASE_INSENSITIVE
    );
    /**
     * 标题末尾的分隔后缀，例如 {@code Song - Live}、{@code Song – Remix}
     */
    private static final Pattern TRAILING_DELIMITER_PATTERN = Pattern.compile(
        "\\s+[-–—]\\s*(.+)$"
    );
    /**
     * 主标题末尾直接跟版本词，例如 {@code NIGHT DANCER Live}、{@code NIGHT DANCER 2025}、
     * {@code NIGHT DANCER Korean}、{@code NIGHT DANCER From Movie}
     */
    private static final Pattern PLAIN_TRAILING_VARIANT_PATTERN = Pattern.compile(
        "(?:(?<![a-z0-9])(?:"
            + "sped\\s+up"
            + "|\\d+(?:\\s+\\d+)?x?"
            + "|live|remaster(?:ed)?|cover|tribute|nightcore|slowed|orchestral|remix|acoustic|"
            + "demo|instrumental|karaoke|ver\\.?|version|original|"
            + "english|korean|japanese|chinese|china|japan|korea"
            + "|from\\b.*"
            + ")|(?:纯音乐|演唱会|伴奏|现场|巡回|片段|试听|铃声|双语|原版|重制|翻唱|版))\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    /**
     * 版本 / 语言 / 演出标记
     *
     * <p>这些词出现在任意括号、末尾分隔后缀或直接跟在主标题后，说明它标的是
     * 「同一首歌的另一版」：{@code (Korean Ver.)}、{@code (DJ豪大大版)}、{@code - Live}、
     * {@code NIGHT DANCER Live}。标记必须带词边界，避免 Oliver 被当成 Live、
     * fromage 被当成 from。
     */
    private static final Pattern VARIANT_MARKER_PATTERN = Pattern.compile(
        "(?<![a-z])(?:\\d|" + VERSION_MARKER_TOKENS + "|original|"
            + "chin(a|ese)|japan(ese)?|korean?|english|from)(?![a-z])",
        Pattern.CASE_INSENSITIVE
    );
    /**
     * 中日韩文字（含假名、谚文）
     */
    private static final Pattern CJK_PATTERN = Pattern.compile(
        "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af\\uf900-\\ufaff]"
    );
    private static final Pattern COMBINING_MARK_PATTERN = Pattern.compile("\\p{M}+");
    private static final Pattern MULTIPLE_SPACES_PATTERN = Pattern.compile("\\s+");
    /**
     * 只标注平台噪声、不影响歌词版本的括号内容；整段匹配才移除
     */
    private static final Set<String> NOISE_BRACKET_LABELS = Set.of(
        "explicit", "explicit version", "deluxe", "deluxe edition", "digital",
        "digital edition", "album edit", "radio edit", "clean", "clean version",
        "bonus track", "official video", "official audio", "lyric video"
    );
    private static final Set<String> ARTIST_TOKEN_STOPWORDS = Set.of(
        "the", "a", "an", "and", "of", "with", "vs"
    );
    /**
     * 候选歌手多出的词命中这些版本词时，不再用 token/JW 补充
     */
    private static final Set<String> ARTIST_VERSION_TOKENS = Set.of(
        "tribute", "cover", "karaoke", "instrumental", "remix", "live",
        "acoustic", "demo", "remaster", "remastered", "slowed", "nightcore", "orchestral",
        "version", "ver", "original", "from",
        "english", "korean", "japanese", "chinese", "china", "japan", "korea"
    );

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
     * 跨文字系统歌手别名的总分上限；高于采纳线，低于同文字已验证的 84.67。
     */
    private static final double CROSS_SCRIPT_ARTIST_CAP = 82.0;
    /**
     * 一侧有有意义前导括号时标题相似度的上限；使 50 + 30 + 20 的满分组合低于采纳线至少 2 分。
     */
    private static final double LEADING_BRACKET_CAP = 0.45;
    /**
     * 普通包含关系比例低于本值时，不允许仅靠歌手/时长把总分抬过采纳线。
     */
    private static final double PLAIN_CONTAINMENT_THRESHOLD = 0.7;
    private static final double PLAIN_CONTAINMENT_CAP = 0.45;
    /**
     * 纯拉丁短标题没有完全相同 token 时直接拒绝，避免换位/少字母越过采纳线。
     */
    private static final int SHORT_LATIN_TITLE_MAX_LENGTH = 4;
    /**
     * 歌手 Jaro-Winkler 作为补充证据的下限。
     */
    private static final double ARTIST_JW_SUPPLEMENT_MIN = 0.90;
    /**
     * 歌手 token overlap 作为补充证据的下限。
     */
    private static final double ARTIST_TOKEN_SUPPLEMENT_MIN = 0.80;
    /**
     * token overlap 生效时要求的最低 Jaro-Winkler。
     */
    private static final double ARTIST_TOKEN_JW_MIN = 0.80;
    /**
     * 采纳线：得分必须严格大于本值。
     * <p>
     * 实测最低可采纳档为 80，最高应拒绝档为 70，采纳线取 75。改动权重或时长分档须重跑
     * 对应单测；同分时按检索顺序决定。
     */
    public static final double MIN_ACCEPT_SCORE = 75.0;

    private enum TextScript {
        LATIN,
        CJK
    }

    private LyricScorer() {
    }

    /**
     * 清洗歌名并归一化：去掉扩展名、曲目序号、试听与铃声后缀、feat 段，
     * 再去变音符号、标点转空格、压缩空白并折叠连续单字符拉丁 token。
     */
    @NonNull
    public static String cleanTitle(@Nullable String raw) {
        return normalizeForComparison(stripTitleDecorations(raw));
    }

    @NonNull
    private static String stripTitleDecorations(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = CLEAN_EXT_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_TRACK_NO_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_SUFFIX_PATTERN.matcher(s).replaceAll("");
        s = CLEAN_FEAT_PATTERN.matcher(s).replaceAll("");
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 清洗并切分歌手：按 {@code / & , ， 、 | ; ；} 分隔，去 feat 段，每个分段归一化
     */
    @NonNull
    public static Set<String> splitArtists(@Nullable String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return set;
        }
        String s = CLEAN_FEAT_PATTERN.matcher(raw.trim()).replaceAll("");
        for (String part : s.split("[/&,，、|;；]")) {
            String item = normalizeForComparison(part.trim());
            if (!item.isEmpty()) {
                set.add(item);
            }
        }
        return set;
    }

    /**
     * 两侧版本签名不同、或只有一侧带版本标记时，判为不同的版本
     *
     * <p>签名是任意括号、末尾分隔后缀和直接跟在主标题后的版本词归一化后的 token 集合。
     * 必须在清洗前判断：版本标记一旦被括号清洗剥掉，后面的相似度就没有可比较的对象。
     */
    private static boolean variantMismatch(@Nullable String target, @Nullable String candidate) {
        if (target == null || candidate == null) return false;
        Set<String> left = variantSignatures(target);
        Set<String> right = variantSignatures(candidate);
        if (left.isEmpty() && right.isEmpty()) return false;
        if (left.isEmpty() || right.isEmpty()) return true;
        return !left.equals(right);
    }

    @NonNull
    private static Set<String> variantSignatures(@NonNull String raw) {
        Set<String> signatures = new HashSet<>();
        Matcher matcher = BRACKET_PATTERN.matcher(raw);
        while (matcher.find()) {
            String content = matcher.group(1);
            if (isIgnorableBracket(content)) continue;
            if (!VARIANT_MARKER_PATTERN.matcher(content).find()) continue;
            addSignatureTokens(signatures, normalizeForComparison(content));
        }
        String suffix = trailingDelimiterSuffix(raw);
        if (suffix != null && !isSourceAttribution(suffix)
            && VARIANT_MARKER_PATTERN.matcher(suffix).find()) {
            addSignatureTokens(signatures, normalizeForComparison(suffix));
        }
        signatures.addAll(plainTrailingVariants(raw));
        String decorated = stripTitleDecorations(raw);
        if (!decorated.isEmpty()) {
            signatures.addAll(plainTrailingVariants(decorated));
        }
        return signatures;
    }

    private static void addSignatureTokens(@NonNull Set<String> target, @NonNull String normalized) {
        for (String token : normalized.split(" ")) {
            if (token.isEmpty()) continue;
            if (!CJK_PATTERN.matcher(token).find()) {
                target.add(token);
                continue;
            }
            boolean marked = false;
            Matcher matcher = VARIANT_MARKER_PATTERN.matcher(token);
            while (matcher.find()) {
                String marker = matcher.group();
                if (!marker.isEmpty()) {
                    target.add(marker);
                    marked = true;
                }
            }
            if (!marked) {
                target.add(token);
            }
        }
    }

    @Nullable
    private static String trailingDelimiterSuffix(@NonNull String raw) {
        Matcher matcher = TRAILING_DELIMITER_PATTERN.matcher(raw.trim());
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    @NonNull
    private static Set<String> plainTrailingVariants(@NonNull String raw) {
        Set<String> markers = new HashSet<>();
        String body = normalizeForComparison(plainTitleBody(raw));
        while (!body.isEmpty()) {
            Matcher matcher = PLAIN_TRAILING_VARIANT_PATTERN.matcher(body);
            if (!matcher.find()) break;
            addSignatureTokens(markers, normalizeForComparison(matcher.group()));
            String stripped = body.substring(0, matcher.start()).trim();
            if (stripped.equals(body)) break;
            body = stripped;
        }
        return markers;
    }

    @NonNull
    private static String plainTitleBody(@NonNull String raw) {
        String trimmed = BRACKET_PATTERN.matcher(raw).replaceAll(" ").trim();
        Matcher matcher = TRAILING_DELIMITER_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            trimmed = trimmed.substring(0, matcher.start()).trim();
        }
        return trimmed;
    }

    /**
     * 出处注解不改变歌曲身份：{@code From "作品名"} 可被平台曲库省略。
     *
     * <p>同一个判断同时用于括号内容与分隔后缀，避免
     * {@code Song (From "Movie")} 与 {@code Song - From "Movie"} 结论不同。
     */
    private static boolean isSourceAttribution(@NonNull String content) {
        String trimmed = content.trim();
        if (RECORDING_VERSION_PATTERN.matcher(trimmed).find()) return false;
        return SOURCE_ATTRIBUTION_PATTERN.matcher(trimmed).matches();
    }

    private static boolean isIgnorableBracket(@NonNull String content) {
        return NOISE_BRACKET_LABELS.contains(normalizeForComparison(content))
            || isSourceAttribution(content);
    }

    /**
     * 歌名相似度 [0.0, 1.0]。
     *
     * <p>先看版本签名，再取原样比较与去噪声 / 去 CJK 尾译名后比较的较高值。
     * 普通包含比例低于 {@link #PLAIN_CONTAINMENT_THRESHOLD} 时压到
     * {@link #PLAIN_CONTAINMENT_CAP}；前导有意义括号不剥离，单侧或不同时压到
     * {@link #LEADING_BRACKET_CAP}。完全相等时不压。
     */
    public static double calculateTitleSimilarity(@Nullable String target, @Nullable String candidate) {
        if (variantMismatch(target, candidate)) return 0.0;
        String decoratedTarget = stripTitleDecorations(target);
        String decoratedCandidate = stripTitleDecorations(candidate);
        if (decoratedTarget.isEmpty() || decoratedCandidate.isEmpty()) return 0.0;

        String normalizedTarget = normalizeForComparison(decoratedTarget);
        String normalizedCandidate = normalizeForComparison(decoratedCandidate);
        if (normalizedTarget.isEmpty() || normalizedCandidate.isEmpty()) return 0.0;

        double best = similarityOf(normalizedTarget, normalizedCandidate);
        String strippedTarget = normalizeForComparison(
            stripTrailingTranslation(stripIgnorableDecorations(decoratedTarget)));
        String strippedCandidate = normalizeForComparison(
            stripTrailingTranslation(stripIgnorableDecorations(decoratedCandidate)));
        best = Math.max(best, similarityOf(strippedTarget, strippedCandidate));

        double containment = Math.max(
            containmentRatio(normalizedTarget, normalizedCandidate),
            containmentRatio(strippedTarget, strippedCandidate));
        if (containment >= 0.0
            && containment < PLAIN_CONTAINMENT_THRESHOLD
            && best <= containment + 1e-9) {
            best = Math.min(best, PLAIN_CONTAINMENT_CAP);
        }

        String leadingTarget = leadingMeaningfulBracket(target);
        String leadingCandidate = leadingMeaningfulBracket(candidate);
        boolean sameLeading = (leadingTarget == null && leadingCandidate == null)
            || (leadingTarget != null && leadingTarget.equals(leadingCandidate));
        if (best < 1.0 && !sameLeading) {
            best = Math.min(best, LEADING_BRACKET_CAP);
        }
        return best;
    }

    /**
     * 两份已归一化标题之间的相似度 [0.0, 1.0]。
     *
     * <p>包含关系只按长度比例给分，避免缺词标题被 Jaro-Winkler 抬高；
     * 其余情况取 Levenshtein 与 Jaro-Winkler 的较大值。
     */
    private static double similarityOf(@NonNull String t1, @NonNull String t2) {
        if (t1.isEmpty() || t2.isEmpty()) return 0.0;
        if (t1.equals(t2)) return 1.0;
        if (isShortLatinWithoutSharedToken(t1, t2)) return 0.0;
        if (t1.contains(t2) || t2.contains(t1)) {
            return containmentRatio(t1, t2);
        }

        int distance = levenshteinDistance(t1, t2);
        int maxLen = Math.max(t1.length(), t2.length());
        double levenshtein = maxLen == 0 ? 1.0 : Math.max(0.0, 1.0 - (double) distance / maxLen);
        return Math.max(levenshtein, jaroWinkler(t1, t2));
    }

    private static double containmentRatio(@NonNull String t1, @NonNull String t2) {
        if (t1.isEmpty() || t2.isEmpty() || t1.equals(t2)) return -1.0;
        if (!t1.contains(t2) && !t2.contains(t1)) return -1.0;
        return (double) Math.min(t1.length(), t2.length()) / Math.max(t1.length(), t2.length());
    }

    private static boolean isShortLatinWithoutSharedToken(@NonNull String t1, @NonNull String t2) {
        if (Math.max(t1.length(), t2.length()) > SHORT_LATIN_TITLE_MAX_LENGTH) return false;
        if (pureScript(t1) != TextScript.LATIN || pureScript(t2) != TextScript.LATIN) return false;
        Set<String> tokens1 = meaningfulTokens(t1);
        Set<String> tokens2 = meaningfulTokens(t2);
        for (String token : tokens1) {
            if (tokens2.contains(token)) return false;
        }
        return true;
    }

    @NonNull
    private static String stripIgnorableDecorations(@NonNull String decorated) {
        Matcher matcher = BRACKET_PATTERN.matcher(decorated);
        StringBuilder result = new StringBuilder(decorated.length());
        int last = 0;
        while (matcher.find()) {
            result.append(decorated, last, matcher.start());
            if (isIgnorableBracket(matcher.group(1))) {
                result.append(' ');
            } else {
                result.append(matcher.group());
            }
            last = matcher.end();
        }
        result.append(decorated, last, decorated.length());

        String trimmed = result.toString().trim();
        Matcher suffix = TRAILING_DELIMITER_PATTERN.matcher(trimmed);
        if (suffix.find() && isSourceAttribution(suffix.group(1))) {
            return trimmed.substring(0, suffix.start());
        }
        return result.toString();
    }

    @NonNull
    private static String stripTrailingTranslation(@NonNull String decorated) {
        String current = decorated;
        while (true) {
            if (!CJK_PATTERN.matcher(current).find()) return current;
            Matcher matcher = TRAILING_BRACKET_PATTERN.matcher(current);
            if (!matcher.find()) return current;
            if (VARIANT_MARKER_PATTERN.matcher(matcher.group(1)).find()) return current;
            String stripped = current.substring(0, matcher.start()) + current.substring(matcher.end());
            if (stripped.isEmpty() || stripped.equals(current)) return current;
            current = stripped;
        }
    }

    @Nullable
    private static String leadingMeaningfulBracket(@Nullable String raw) {
        if (raw == null) return null;
        Matcher matcher = LEADING_BRACKET_PATTERN.matcher(raw);
        if (!matcher.find()) return null;
        String content = matcher.group(1);
        if (content.trim().isEmpty() || isIgnorableBracket(content)) return null;
        return normalizeForComparison(content);
    }

    /**
     * 计算歌手相似度 [0.0, 1.0]
     *
     * <p>集合 Jaccard 只做完全相等匹配；完整歌手串的 token overlap 与
     * Jaro-Winkler 只在接近同一名称时补充。任一侧出现版本词时，只有集合完全相等才保留，
     * 否则直接判 0，避免 {@code Taylor Swift Tribute}、{@code Adele Cover} 被当成同一歌手。
     * 任一侧歌手缺失时返回 0.0，缺失扣分由 {@link #scoreCandidate} 决定。
     */
    public static double calculateArtistSimilarity(@Nullable String target, @Nullable String candidate) {
        Set<String> targetArtists = splitArtists(target);
        Set<String> candidateArtists = splitArtists(candidate);
        if (targetArtists.isEmpty() || candidateArtists.isEmpty()) {
            return 0.0;
        }

        String targetText = normalizeArtistText(target);
        String candidateText = normalizeArtistText(candidate);
        double base = jaccard(targetArtists, candidateArtists);
        if (hasVersionToken(meaningfulTokens(targetText))
            || hasVersionToken(meaningfulTokens(candidateText))) {
            return base == 1.0 ? 1.0 : 0.0;
        }

        double jw = jaroWinkler(targetText, candidateText);
        double token = tokenOverlap(targetText, candidateText);
        double supplement = 0.0;
        if (jw >= ARTIST_JW_SUPPLEMENT_MIN) {
            supplement = jw;
        }
        if (token >= ARTIST_TOKEN_SUPPLEMENT_MIN && jw >= ARTIST_TOKEN_JW_MIN) {
            supplement = Math.max(supplement, token);
        }
        return Math.max(base, supplement);
    }

    private static double jaccard(@NonNull Set<String> left, @NonNull Set<String> right) {
        int intersection = 0;
        for (String leftItem : left) {
            if (right.contains(leftItem)) intersection++;
        }
        int union = left.size() + right.size() - intersection;
        return union <= 0 ? 0.0 : (double) intersection / union;
    }

    private static boolean hasVersionToken(@NonNull Set<String> tokens) {
        for (String token : tokens) {
            if (ARTIST_VERSION_TOKENS.contains(token)) return true;
        }
        return tokens.contains("sped") && tokens.contains("up");
    }

    private static boolean artistHasVersionWord(@Nullable String raw) {
        return hasVersionToken(meaningfulTokens(normalizeArtistText(raw)));
    }

    private static double tokenOverlap(@NonNull String left, @NonNull String right) {
        Set<String> leftTokens = meaningfulTokens(left);
        Set<String> rightTokens = meaningfulTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0;
        int overlap = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) overlap++;
        }
        if (overlap == 0) return 0.0;
        return ((double) overlap / leftTokens.size() + (double) overlap / rightTokens.size()) / 2.0;
    }

    @NonNull
    private static Set<String> meaningfulTokens(@NonNull String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : text.split(" ")) {
            if (token.length() >= 2 && !ARTIST_TOKEN_STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    @NonNull
    private static String normalizeArtistText(@Nullable String raw) {
        if (raw == null) return "";
        return normalizeForComparison(CLEAN_FEAT_PATTERN.matcher(raw.trim()).replaceAll(""));
    }

    private static boolean isCrossScriptArtist(@Nullable String target, @Nullable String candidate) {
        TextScript left = pureScript(normalizeArtistText(target));
        TextScript right = pureScript(normalizeArtistText(candidate));
        return left != null && right != null && left != right;
    }

    @Nullable
    private static TextScript pureScript(@NonNull String text) {
        TextScript found = null;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) continue;
            TextScript script = scriptOf(codePoint);
            if (script == null) return null;
            if (found == null) {
                found = script;
            } else if (found != script) {
                return null;
            }
        }
        return found;
    }

    @Nullable
    private static TextScript scriptOf(int codePoint) {
        if ((codePoint >= 'A' && codePoint <= 'Z')
            || (codePoint >= 'a' && codePoint <= 'z')
            || (codePoint >= 0x00C0 && codePoint <= 0x024F)
            || (codePoint >= 0x1E00 && codePoint <= 0x1EFF)) {
            return TextScript.LATIN;
        }
        if ((codePoint >= 0x3040 && codePoint <= 0x30FF)
            || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
            || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
            || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)
            || (codePoint >= 0xF900 && codePoint <= 0xFAFF)) {
            return TextScript.CJK;
        }
        return null;
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
     * 综合评定候选歌曲得分 [0, 100]。
     * <p>
     * 目标侧缺失的维度从分母去掉；候选侧缺失给 0 分但保留分母。跨文字系统歌手视为
     * 不可比较的别名，歌手栏从分母去掉，总分封顶在 {@link #CROSS_SCRIPT_ARTIST_CAP}。
     * 歌手含 Tribute、Version、Original 等版本词时不走别名路径，按普通歌手扣分。
     * 别名不额外放宽时长：6 秒及以上仍按时长 0 分拒绝，保守优先。
     * 目标侧只剩歌名时得分被压在 THIN_TARGET_CAP 以下。
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

        Set<String> targetArtists = splitArtists(targetArtist);
        Set<String> candidateArtists = splitArtists(candidateArtist);
        boolean targetHasArtist = !targetArtists.isEmpty();
        boolean candidateHasArtist = !candidateArtists.isEmpty();
        boolean targetHasDuration = targetDurationMs > 0;
        boolean artistVersionWord = targetHasArtist && candidateHasArtist
            && (artistHasVersionWord(targetArtist) || artistHasVersionWord(candidateArtist));

        boolean crossScript = targetHasArtist && candidateHasArtist
            && !artistVersionWord
            && isCrossScriptArtist(targetArtist, candidateArtist);
        boolean artistComparable = targetHasArtist && !crossScript;

        double artistSim = artistComparable && candidateHasArtist
            ? calculateArtistSimilarity(targetArtist, candidateArtist)
            : 0.0;
        double durationFraction = targetHasDuration && candidateDurationMs > 0
            ? calculateDurationScore(targetDurationMs, candidateDurationMs) / 100.0
            : 0.0;

        double earned = titleSim * TITLE_WEIGHT
            + (artistComparable ? artistSim * ARTIST_WEIGHT : 0.0)
            + durationFraction * DURATION_WEIGHT;
        double total = TITLE_WEIGHT
            + (artistComparable ? ARTIST_WEIGHT : 0.0)
            + (targetHasDuration ? DURATION_WEIGHT : 0.0);

        double score = earned / total * 100.0;
        if (crossScript) {
            score = Math.min(score, CROSS_SCRIPT_ARTIST_CAP);
        }
        if (!targetHasArtist && !targetHasDuration) {
            return Math.min(score, THIN_TARGET_CAP);
        }
        return score;
    }

    /**
     * 纯 Java Jaro-Winkler，适合短文本；按码点匹配，避免代理对被拆开。
     */
    private static double jaroWinkler(@NonNull String s1, @NonNull String s2) {
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        int[] left = s1.codePoints().toArray();
        int[] right = s2.codePoints().toArray();
        int matchDistance = Math.max(0, Math.max(left.length, right.length) / 2 - 1);
        boolean[] leftMatches = new boolean[left.length];
        boolean[] rightMatches = new boolean[right.length];
        int matches = 0;
        for (int i = 0; i < left.length; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance, right.length - 1);
            for (int j = start; j <= end; j++) {
                if (!rightMatches[j] && left[i] == right[j]) {
                    leftMatches[i] = true;
                    rightMatches[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) return 0.0;

        int transpositions = 0;
        int rightIndex = 0;
        for (int i = 0; i < left.length; i++) {
            if (!leftMatches[i]) continue;
            while (!rightMatches[rightIndex]) rightIndex++;
            if (left[i] != right[rightIndex]) transpositions++;
            rightIndex++;
        }

        double jaro = ((double) matches / left.length
            + (double) matches / right.length
            + (matches - transpositions / 2.0) / matches) / 3.0;
        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(left.length, right.length));
        while (prefix < maxPrefix && left[prefix] == right[prefix]) {
            prefix++;
        }
        return Math.min(1.0, jaro + prefix * 0.1 * (1.0 - jaro));
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

    /**
     * 归一化文本：NFKC、去变音符号、标点转空格、压缩空白、折叠连续单字符拉丁 token。
     */
    @NonNull
    private static String normalizeForComparison(@Nullable String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        value = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        value = Normalizer.normalize(value, Normalizer.Form.NFD);
        value = COMBINING_MARK_PATTERN.matcher(value).replaceAll("");

        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(codePoint);
            } else {
                builder.append(' ');
            }
        }
        String collapsed = MULTIPLE_SPACES_PATTERN.matcher(builder).replaceAll(" ").trim();
        return collapseSpacedLatinRuns(collapsed);
    }

    @NonNull
    private static String collapseSpacedLatinRuns(@NonNull String value) {
        if (value.isEmpty()) return value;
        String[] tokens = value.split(" ", -1);
        StringBuilder result = new StringBuilder(value.length());
        StringBuilder run = new StringBuilder();
        for (String token : tokens) {
            if (token.length() == 1 && isLatinLetter(token.codePointAt(0))) {
                run.append(token);
                continue;
            }
            appendRun(result, run);
            if (token.isEmpty()) continue;
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(token);
        }
        appendRun(result, run);
        return result.toString();
    }

    private static void appendRun(@NonNull StringBuilder result, @NonNull StringBuilder run) {
        if (run.length() == 0) return;
        if (result.length() > 0) {
            result.append(' ');
        }
        result.append(run);
        run.setLength(0);
    }

    private static boolean isLatinLetter(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
            || (codePoint >= 'a' && codePoint <= 'z')
            || (codePoint >= 0x00C0 && codePoint <= 0x024F)
            || (codePoint >= 0x1E00 && codePoint <= 0x1EFF);
    }
}
