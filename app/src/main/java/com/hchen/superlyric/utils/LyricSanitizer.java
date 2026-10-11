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
package com.hchen.superlyric.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.kernel.text.LyricText;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌词数据全局安全清洗与合法性校验防护器。
 * <p>
 * 严防任何 Provider 或外部发布者产生损坏的逐字切片、字符索引倒错、
 * 零宽不可见字符污染或累积字长越界等非法数据，避免下游接收端（如 SuperIslandLyric 在 SystemUI 中）
 * 调用 {@code Canvas.drawTextRun} 时发生 {@link IndexOutOfBoundsException} 导致系统界面崩溃。
 *
 * @author 焕晨HChen
 */
public final class LyricSanitizer {
    private static final String TAG = "LyricSanitizer";

    private LyricSanitizer() {
    }

    /**
     * 清理破坏字符长度对齐或导致系统文本测量异常的特殊控制字符。
     * <p>
     * 剥离零宽空格 (U+200B)、零宽不连字 (U+200C)、零宽连字 (U+200D)、BOM (U+FEFF)、
     * 未折行的换行符 (U+000D / U+000A) 与 NUL (U+0000，可由 {@code &#0;} 实体解码得到)。
     * <p>
     * 表只有一份，在内核的 {@code LyricText}：此处曾另抄一份（且不含 NUL），于是往表里
     * 补一个字符会落在没生效的那一份上——发布路径上真正把关的正是本方法。
     */
    @NonNull
    public static String cleanInvisibleChars(@Nullable String text) {
        return LyricText.INSTANCE.cleanInvisibleChars(text);
    }

    /**
     * 对单行歌词模型进行深度校验与数据清洗。
     * <p>
     * 规则：
     * <ol>
     *   <li>标准化文本，剥离不可见零宽字符；</li>
     *   <li>深度自检 {@code words} 数组：若存在 null 元素、时间倒错、字词累积长度超出行长、
     *       或字词文本拼接与行文本严重失配（如重叠重复切片），则认定逐字数据损坏，安全降级置为 {@code null}；</li>
     *   <li>校准行起止时间戳，确保 {@code endTime >= startTime >= 0}；</li>
     *   <li>递归清洗翻译与副歌词行。</li>
     * </ol>
     *
     * @param line 待校验的原始行
     * @return 清洗校准后的安全行；若原始行为 null 则返回 null
     */
    @Nullable
    public static SuperLyricLine sanitizeLine(@Nullable SuperLyricLine line) {
        if (line == null) {
            return null;
        }

        String rawText = line.getText();
        String cleanText = cleanInvisibleChars(rawText);

        long start = Math.max(0L, line.getStartTime());
        long end = Math.max(start, line.getEndTime());

        String cleanTrans = cleanInvisibleChars(line.getTranslation());
        if (cleanTrans.trim().isEmpty()) {
            cleanTrans = null;
        }

        String cleanSec = cleanInvisibleChars(line.getSecondary());
        if (cleanSec.trim().isEmpty()) {
            cleanSec = null;
        }

        SuperLyricWord[] rawWords = line.getWords();
        SuperLyricWord[] cleanWords = null;

        if (rawWords != null && rawWords.length > 0) {
            cleanWords = validateAndCleanWords(rawWords, cleanText, start, end);
        }

        return new SuperLyricLine(
            cleanText,
            cleanWords,
            cleanTrans,
            cleanSec,
            start,
            end
        );
    }

    /**
     * 严密校验并清洗逐字数据。若存在任何越界或破坏性风险，一律拒绝并降级为 null。
     */
    @Nullable
    private static SuperLyricWord[] validateAndCleanWords(@NonNull SuperLyricWord[] rawWords,
                                                          @NonNull String cleanLineText,
                                                          long lineStart,
                                                          long lineEnd) {
        List<SuperLyricWord> validated = new ArrayList<>(rawWords.length);
        StringBuilder wordsTextBuilder = new StringBuilder();

        for (int i = 0; i < rawWords.length; i++) {
            SuperLyricWord w = rawWords[i];
            if (w == null) {
                AndroidLog.logW(TAG, "Sanitizer: dropping words due to null word element at index " + i + " for line: \"" + cleanLineText + "\"");
                return null;
            }

            String wText = cleanInvisibleChars(w.getWord());
            long wStart = Math.max(0L, w.getStartTime());
            long wEnd = Math.max(wStart, w.getEndTime());

            // 检查词内时间反转
            if (wEnd < wStart) {
                wEnd = wStart;
            }

            wordsTextBuilder.append(wText);
            validated.add(new SuperLyricWord(wText, wStart, wEnd));
        }

        String combinedWordsText = wordsTextBuilder.toString();

        // 智能自愈：若仅因词元首尾多余空白导致累积词长超出行长，安全裁剪首尾词元空白
        if (combinedWordsText.length() > cleanLineText.length()
            && combinedWordsText.trim().equals(cleanLineText.trim())) {
            trimOuterWhitespace(validated, cleanLineText);
            wordsTextBuilder.setLength(0);
            for (SuperLyricWord w : validated) {
                wordsTextBuilder.append(w.getWord());
            }
            combinedWordsText = wordsTextBuilder.toString();
        }

        // 致命防线 1：字词累积长度绝对不能超出整行文本长度（会导致 Canvas.drawTextRun 索引越界崩溃）
        if (combinedWordsText.length() > cleanLineText.length()) {
            AndroidLog.logW(TAG, "Sanitizer: DROPPING CORRUPTED WORDS! Total words length (" + combinedWordsText.length()
                + ") exceeds line text length (" + cleanLineText.length() + ") | line=\"" + cleanLineText
                + "\" | combinedWords=\"" + combinedWordsText + "\"");
            return null;
        }

        // 致命防线 2：字词文本与整行文本必须保持语义一致性
        // 允许词文本去除空格后与行文本去除空格后一致（兼容不切空格的逐字约定）
        boolean exactMatch = combinedWordsText.equals(cleanLineText);
        if (!exactMatch) {
            // 全角空格 U+3000 不属于 \s，必须显式列出：否则"含全角空格"与"不含"的两种
            // 写法会被判为不等，合法的逐字数据因此被丢弃
            String combinedNoSpace = combinedWordsText.replaceAll("[\\s\\u3000]+", "");
            String lineNoSpace = cleanLineText.replaceAll("[\\s\\u3000]+", "");
            if (!combinedNoSpace.equals(lineNoSpace)) {
                AndroidLog.logW(TAG, "Sanitizer: DROPPING MISMATCHED WORDS! Combined words mismatch line text"
                    + " | line=\"" + cleanLineText + "\" | combined=\"" + combinedWordsText + "\"");
                return null;
            }
        }

        // 致命防线 3：全局逐字时序自愈与 0ms 持续时间消除
        healWordTimings(validated, lineStart, lineEnd);

        return validated.toArray(new SuperLyricWord[0]);
    }

    /**
     * 智能裁剪词元序列两端多余的外层空白字符。
     */
    private static void trimOuterWhitespace(@NonNull List<SuperLyricWord> words, @NonNull String targetLine) {
        if (words.isEmpty()) return;

        // 1. 消除前导多余空格
        while (!words.isEmpty()) {
            SuperLyricWord first = words.get(0);
            String w = first.getWord();
            if (w.trim().isEmpty() && !targetLine.startsWith(w)) {
                words.remove(0);
            } else {
                int trimLeadingCount = 0;
                while (trimLeadingCount < w.length() && Character.isWhitespace(w.charAt(trimLeadingCount))) {
                    trimLeadingCount++;
                }
                if (trimLeadingCount > 0 && !targetLine.startsWith(w)) {
                    String trimmedWord = w.substring(trimLeadingCount);
                    words.set(0, new SuperLyricWord(trimmedWord, first.getStartTime(), first.getEndTime()));
                }
                break;
            }
        }

        // 2. 消除尾随多余空格
        while (!words.isEmpty()) {
            int lastIdx = words.size() - 1;
            SuperLyricWord last = words.get(lastIdx);
            String w = last.getWord();
            if (w.trim().isEmpty() && !targetLine.endsWith(w)) {
                words.remove(lastIdx);
            } else {
                int trimTrailingCount = 0;
                while (trimTrailingCount < w.length() && Character.isWhitespace(w.charAt(w.length() - 1 - trimTrailingCount))) {
                    trimTrailingCount++;
                }
                if (trimTrailingCount > 0 && !targetLine.endsWith(w)) {
                    String trimmedWord = w.substring(0, w.length() - trimTrailingCount);
                    words.set(lastIdx, new SuperLyricWord(trimmedWord, last.getStartTime(), last.getEndTime()));
                }
                break;
            }
        }
    }

    /**
     * 全局逐字时序自愈与 0ms/异常时长兜底修复。
     * <p>
     * 针对各 Provider（如波点、酷狗、QQ音乐等）可能因上游解析截断或重叠产生的 0ms 词元，
     * 利用前后词的时序拓扑间隙无缝自愈，确保所有词元持续时间严格大于 0。
     */
    public static void healWordTimings(@NonNull List<SuperLyricWord> words, long lineStart, long lineEnd) {
        int size = words.size();
        if (size == 0) return;

        // 阶段 1：首轮拓扑排序与单调性纠偏 (确保 start 严格非降，消灭时光倒流与 0ms 折叠)
        long runningStart = lineStart;
        for (int i = 0; i < size; i++) {
            SuperLyricWord cur = words.get(i);
            long start = cur.getStartTime();
            long end = cur.getEndTime();
            String text = cur.getWord();
            // 兜底时长**只有一份实现**（内核 LyricText）。此处曾另写一遍，且与内核不等价
            // （这里算的是 trim 后的长度），改一处漏一处
            long fallbackDur = LyricText.INSTANCE.fallbackWordDuration(text != null ? text : "");

            // 若词起偏早于当前运行水位（发生时间倒流），纠正至当前运行水位
            if (start < runningStart) {
                start = runningStart;
            }

            // 持续时间自愈：杜绝 end <= start 以及异常超长 (>15s)
            long dur = end - start;
            if (dur <= 0 || dur > 15000L) {
                // 探测后继词起始以平滑填补
                long nextValidStart = -1L;
                for (int j = i + 1; j < size; j++) {
                    long nws = words.get(j).getStartTime();
                    if (nws > start) {
                        nextValidStart = nws;
                        break;
                    }
                }

                if (nextValidStart > start) {
                    // 严防把词间停顿/间隙误当成单词本身的持续时间：最多只取单个字词的合理自然时长
                    dur = Math.min(fallbackDur, nextValidStart - start);
                } else if (lineEnd > start) {
                    dur = Math.min(lineEnd - start, Math.max(fallbackDur, (lineEnd - start) / Math.max(1, size - i)));
                } else {
                    dur = fallbackDur;
                }

                if (dur <= 0) {
                    dur = fallbackDur;
                }
                end = start + dur;
            }

            // 更新当前运行起始水位（保证下一个词不可能早于当前词起始）
            runningStart = start;
            words.set(i, new SuperLyricWord(text, start, end));
        }

        // 阶段 2：重叠消除与紧凑平滑
        for (int i = 0; i < size - 1; i++) {
            SuperLyricWord cur = words.get(i);
            SuperLyricWord next = words.get(i + 1);

            // 如果当前词结束时间大幅越过后继词起始（发生严重重叠）
            if (cur.getEndTime() > next.getStartTime()) {
                long adjustedEnd = next.getStartTime();
                if (adjustedEnd > cur.getStartTime()) {
                    // 平滑截断到后继词起始
                    words.set(i, new SuperLyricWord(cur.getWord(), cur.getStartTime(), adjustedEnd));
                } else {
                    // 若重叠导致当前词被压平，顺推后继词起始
                    long minCurEnd = cur.getStartTime() + Math.max(80L, (long) Math.max(1, cur.getWord().length()) * 100L);
                    words.set(i, new SuperLyricWord(cur.getWord(), cur.getStartTime(), minCurEnd));
                    if (next.getStartTime() < minCurEnd) {
                        long nextDur = Math.max(100L, next.getEndTime() - next.getStartTime());
                        words.set(i + 1, new SuperLyricWord(next.getWord(), minCurEnd, minCurEnd + nextDur));
                    }
                }
            }
        }

        // 阶段 3：尾词与行末对齐
        SuperLyricWord last = words.get(size - 1);
        if (last.getEndTime() <= last.getStartTime()) {
            words.set(size - 1, new SuperLyricWord(last.getWord(), last.getStartTime(), last.getStartTime() + 150L));
        }
    }

    /**
     * 对完整 SuperLyricData 数据包进行全量深度清洗。
     *
     * @param data 原始歌词数据包
     * @return 经过安全校验与清洗的数据包；若原始为 null 则返回 null
     */
    @Nullable
    public static SuperLyricData sanitizeData(@Nullable SuperLyricData data) {
        if (data == null) {
            return null;
        }

        try {
            // 1. 清洗当前单行
            if (data.hasLyric() && data.getLyric() != null) {
                data.setLyric(sanitizeLine(data.getLyric()));
            }

            // 2. 清洗独立挂载的翻译行
            if (data.hasTranslation() && data.getTranslation() != null) {
                data.setTranslation(sanitizeLine(data.getTranslation()));
            }

            // 3. 清洗独立挂载的副歌词行
            if (data.hasSecondary() && data.getSecondary() != null) {
                data.setSecondary(sanitizeLine(data.getSecondary()));
            }

            // 4. 清洗全量歌词行列表
            if (data.hasAllLyrics() && data.getAllLyrics() != null) {
                SuperLyricLine[] originalLines = data.getAllLyrics();
                SuperLyricLine[] cleanLines = new SuperLyricLine[originalLines.length];
                for (int i = 0; i < originalLines.length; i++) {
                    cleanLines[i] = sanitizeLine(originalLines[i]);
                }
                data.setAllLyrics(cleanLines);

                // 若缺少单行但有全量行，向下兼容补全第 0 行
                if (!data.hasLyric() && cleanLines.length > 0) {
                    data.setLyric(cleanLines[0]);
                    data.setCurrentLyricIndex(0);
                }
            }

            // 5. 校准基础时序与索引
            if (data.getPosition() < 0) {
                data.setPosition(0);
            }
            if (data.getDuration() < 0) {
                data.setDuration(0);
            }
            if (data.getCurrentLyricIndex() < -1) {
                data.setCurrentLyricIndex(-1);
            } else if (data.hasAllLyrics() && data.getAllLyricsCount() > 0 && data.getCurrentLyricIndex() >= data.getAllLyricsCount()) {
                data.setCurrentLyricIndex(data.getAllLyricsCount() - 1);
            }
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Error during lyric sanitization", t);
        }

        return data;
    }
}
