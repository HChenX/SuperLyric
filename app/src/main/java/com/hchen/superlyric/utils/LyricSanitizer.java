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
 * 歌词数据的安全清洗与合法性校验
 *
 * <p>拦下损坏的逐字切片、倒错的字符索引、零宽不可见字符与累积字长越界，避免下游
 * 接收端（如 SystemUI 中的 SuperIslandLyric）调用 {@code Canvas.drawTextRun} 时抛出
 * {@link IndexOutOfBoundsException} 导致系统界面崩溃。
 *
 * @author 焕晨HChen
 */
public final class LyricSanitizer {
    private static final String TAG = "LyricSanitizer";

    private LyricSanitizer() {
    }

    /**
     * 清理破坏字符长度对齐或导致文本测量异常的特殊控制字符
     *
     * <p>剥离零宽空格 (U+200B)、零宽不连字 (U+200C)、零宽连字 (U+200D)、BOM (U+FEFF)、
     * 未折行的换行符 (U+000D / U+000A) 与 NUL (U+0000，可由 {@code &#0;} 实体解码得到)。
     *
     * <p>字符表只有一份，在内核的 {@code LyricText}；发布路径上把关的是本方法。
     */
    @NonNull
    public static String cleanInvisibleChars(@Nullable String text) {
        return LyricText.INSTANCE.cleanInvisibleChars(text);
    }

    /**
     * 对单行歌词模型做深度校验与数据清洗
     *
     * <p>规则：
     * <ol>
     *   <li>标准化文本，剥离不可见零宽字符；</li>
     *   <li>丢弃条件：null 元素、字词累积长度超出行长、或字词文本拼接与行文本严重失配
     *       （如重叠重复切片），此时认定逐字数据损坏并降级为 {@code null}；</li>
     *   <li>修正条件：时间倒错、0ms、重叠由 {@code healWordTimings} 纠偏/顺推，不丢弃；</li>
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
     * 校验并清洗逐字数据；存在越界或破坏性风险时整体降级为 null
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

        // 首尾空白导致累积词长超出行长时，先裁剪首尾词元的空白再校验
        if (combinedWordsText.length() > cleanLineText.length()
            && combinedWordsText.trim().equals(cleanLineText.trim())) {
            trimOuterWhitespace(validated, cleanLineText);
            wordsTextBuilder.setLength(0);
            for (SuperLyricWord w : validated) {
                wordsTextBuilder.append(w.getWord());
            }
            combinedWordsText = wordsTextBuilder.toString();
        }

        // 防线 1：字词累积长度不得超过整行文本长度（否则 Canvas.drawTextRun 索引越界崩溃）
        if (combinedWordsText.length() > cleanLineText.length()) {
            AndroidLog.logW(TAG, "Sanitizer: DROPPING CORRUPTED WORDS! Total words length (" + combinedWordsText.length()
                + ") exceeds line text length (" + cleanLineText.length() + ") | line=\"" + cleanLineText
                + "\" | combinedWords=\"" + combinedWordsText + "\"");
            return null;
        }

        // 防线 2：字词文本与整行文本必须保持语义一致；允许去除空格后一致，
        // 以兼容不切空格的逐字约定
        boolean exactMatch = combinedWordsText.equals(cleanLineText);
        if (!exactMatch) {
            // 全角空格 U+3000 不属于 \s，必须显式列出：否则含全角空格与不含的两种写法
            // 会被判为不等，合法的逐字数据因此被丢弃
            String combinedNoSpace = combinedWordsText.replaceAll("[\\s\\u3000]+", "");
            String lineNoSpace = cleanLineText.replaceAll("[\\s\\u3000]+", "");
            if (!combinedNoSpace.equals(lineNoSpace)) {
                AndroidLog.logW(TAG, "Sanitizer: DROPPING MISMATCHED WORDS! Combined words mismatch line text"
                    + " | line=\"" + cleanLineText + "\" | combined=\"" + combinedWordsText + "\"");
                return null;
            }
        }

        // 防线 3：全局逐字时序纠偏，消除 0ms 持续时间
        healWordTimings(validated, lineStart, lineEnd);

        return validated.toArray(new SuperLyricWord[0]);
    }

    /**
     * 裁剪词元序列两端多余的外层空白
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
     * 全局逐字时序纠偏与 0ms / 异常时长兜底
     *
     * <p>针对各 Provider（如波点、酷狗、QQ音乐等）因上游解析截断或重叠产生的 0ms 词元，
     * 借助前后词的时序间隙顺推，使每个词元的持续时间大于 0。
     */
    public static void healWordTimings(@NonNull List<SuperLyricWord> words, long lineStart, long lineEnd) {
        int size = words.size();
        if (size == 0) return;

        // 阶段 1：单调性纠偏，保证 start 非降并消除 0ms 时长
        long runningStart = lineStart;
        for (int i = 0; i < size; i++) {
            SuperLyricWord cur = words.get(i);
            long start = cur.getStartTime();
            long end = cur.getEndTime();
            String text = cur.getWord();
            // 兜底时长只在 LyricText 中实现一份；两处各写会算出不同的值
            long fallbackDur = LyricText.INSTANCE.fallbackWordDuration(text != null ? text : "");

            // 若词起偏早于当前运行水位（发生时间倒流），纠正至当前运行水位
            if (start < runningStart) {
                start = runningStart;
            }

            // 持续时间兜底：end <= start 或超过 15s 时重算
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
                    // 词间停顿不得计入单词时长：最多取单个词元的自然时长
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

            // 当前词结束时间越过后继词起始
            if (cur.getEndTime() > next.getStartTime()) {
                long adjustedEnd = next.getStartTime();
                if (adjustedEnd > cur.getStartTime()) {
                    // 截断到后继词起始
                    words.set(i, new SuperLyricWord(cur.getWord(), cur.getStartTime(), adjustedEnd));
                } else {
                    // 重叠导致当前词被压平时顺推后继词起始
                    long minCurEnd = cur.getStartTime() + Math.max(80L, (long) Math.max(1, cur.getWord().length()) * 100L);
                    words.set(i, new SuperLyricWord(cur.getWord(), cur.getStartTime(), minCurEnd));
                    if (next.getStartTime() < minCurEnd) {
                        long nextDur = Math.max(100L, next.getEndTime() - next.getStartTime());
                        words.set(i + 1, new SuperLyricWord(next.getWord(), minCurEnd, minCurEnd + nextDur));
                    }
                }
            }
        }

        // 阶段 2 的顺推分支可能把后继词尾推到 lineEnd 之后；这里统一收口，
        // 保证任何词的 end 都不越过行尾（start 已在线内时才截断）。
        if (lineEnd > lineStart) {
            for (int i = 0; i < size; i++) {
                SuperLyricWord word = words.get(i);
                if (word.getStartTime() < lineEnd && word.getEndTime() > lineEnd) {
                    words.set(i, new SuperLyricWord(word.getWord(), word.getStartTime(), lineEnd));
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
     * 对完整 {@link SuperLyricData} 数据包做全量深度清洗
     *
     * @param data 原始歌词数据包
     * @return 经过安全校验与清洗的数据包；原始为 null 或清洗过程失败时返回 null
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
                List<SuperLyricLine> cleanList = new ArrayList<>(originalLines.length);
                for (SuperLyricLine originalLine : originalLines) {
                    SuperLyricLine clean = sanitizeLine(originalLine);
                    // sanitizeLine 对 null 输入返回 null；null 元素留在数组里会让下游
                    // （LyricDocAdapter 的 mapNotNull 参数、UI 逐行取 text）抛 NPE，
                    // 打在发布或时钟线程上。直接剔除
                    if (clean != null) {
                        cleanList.add(clean);
                    }
                }
                SuperLyricLine[] cleanLines = cleanList.toArray(new SuperLyricLine[0]);
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
            // 逐字段清洗是原地写回：失败时 data 会留下部分已清洗、部分未清洗的混合体，
            // 未清洗的逐字数据可能直接进入 SystemUI 的 drawTextRun。调用方
            // （sendLyric / publishFullLyric）本就处理 null，丢包优于半清洗发布。
            AndroidLog.logE(TAG, "Dropping lyric packet: sanitization failed", t);
            return null;
        }

        return data;
    }
}
