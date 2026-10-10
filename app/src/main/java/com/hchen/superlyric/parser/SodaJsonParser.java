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
package com.hchen.superlyric.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 汽水音乐 (Soda / Luna) 与波点 JSON 原生逐字歌词解析器。
 * <p>
 * <b>技术原理：</b>
 * <ul>
 *   <li><b>多层级容器自适应探测：</b>支持探测 {@code audioWithLyricsOption.lyrics.sentences}、
 *       {@code loaderData.track_page.audioWithLyricsOption.lyrics.sentences} 或根级 {@code sentences}。</li>
 *   <li><b>逐字时间线规整：</b>提取 {@code sentence.startMs}, {@code sentence.endMs} 与词元列表 {@code words}。</li>
 *   <li><b>时序自愈集成：</b>遍历时自动纠偏 {@code endMs <= startMs}，彻底规避宿主内部 0ms 坍缩及越界缺陷。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
public final class SodaJsonParser {
    private static final String TAG = "SodaJsonParser";

    private SodaJsonParser() {
    }

    /**
     * 解析汽水音乐原始 JSON 歌词字符串为标准歌词行数组。
     *
     * @param jsonString 包含 sentences / words 的原始 JSON 字符串
     * @return 解析后的逐字歌词行数组，失败或无歌词时返回 null
     */
    @Nullable
    public static SuperLyricLine[] parseJson(@Nullable String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return null;
        }

        try {
            JSONObject root = new JSONObject(jsonString);
            JSONArray sentences = findSentencesArray(root);
            if (sentences == null || sentences.length() == 0) {
                return null;
            }

            List<SuperLyricLine> lineList = new ArrayList<>(sentences.length());
            for (int i = 0; i < sentences.length(); i++) {
                JSONObject sentenceObj = sentences.optJSONObject(i);
                if (sentenceObj == null) continue;

                long lineStart = sentenceObj.optLong("startMs", sentenceObj.optLong("start_ms", 0L));
                long lineEnd = sentenceObj.optLong("endMs", sentenceObj.optLong("end_ms", 0L));
                if (lineEnd <= lineStart) {
                    lineEnd = lineStart + 2000L;
                }

                JSONArray wordsArr = sentenceObj.optJSONArray("words");
                List<SuperLyricWord> wordsList = new ArrayList<>();
                StringBuilder textBuilder = new StringBuilder();

                if (wordsArr != null && wordsArr.length() > 0) {
                    for (int w = 0; w < wordsArr.length(); w++) {
                        JSONObject wordObj = wordsArr.optJSONObject(w);
                        if (wordObj == null) continue;

                        String wText = LyricSanitizer.cleanInvisibleChars(wordObj.optString("text", ""));
                        if (wText == null || wText.isEmpty()) continue;

                        long wStart = wordObj.optLong("startMs", wordObj.optLong("start_ms", lineStart));
                        long wEnd = wordObj.optLong("endMs", wordObj.optLong("end_ms", wStart));

                        if (wEnd <= wStart) {
                            // 优先以前驱或字长进行保底
                            wEnd = wStart + Math.max(120L, (long) wText.length() * 150L);
                        }

                        wordsList.add(new SuperLyricWord(wText, wStart, wEnd));
                        textBuilder.append(wText);
                    }
                }

                String mainText = textBuilder.toString().trim();
                if (mainText.isEmpty()) {
                    mainText = LyricSanitizer.cleanInvisibleChars(sentenceObj.optString("text", ""));
                }

                if (mainText == null || mainText.isEmpty()) {
                    continue;
                }

                if (!wordsList.isEmpty()) {
                    LyricSanitizer.healWordTimings(wordsList, lineStart, lineEnd);
                    lineList.add(new SuperLyricLine(mainText, wordsList.toArray(new SuperLyricWord[0]), null, lineStart, lineEnd));
                } else {
                    lineList.add(new SuperLyricLine(mainText, lineStart, lineEnd));
                }
            }

            if (lineList.isEmpty()) {
                return null;
            }

            return lineList.toArray(new SuperLyricLine[0]);
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to parse Soda JSON lyrics: " + t.getMessage());
            return null;
        }
    }

    @Nullable
    private static JSONArray findSentencesArray(@NonNull JSONObject root) {
        if (root.has("sentences")) {
            return root.optJSONArray("sentences");
        }
        if (root.has("lyrics")) {
            JSONObject lyricsObj = root.optJSONObject("lyrics");
            if (lyricsObj != null && lyricsObj.has("sentences")) {
                return lyricsObj.optJSONArray("sentences");
            }
        }
        if (root.has("audioWithLyricsOption")) {
            JSONObject audioObj = root.optJSONObject("audioWithLyricsOption");
            if (audioObj != null) {
                return findSentencesArray(audioObj);
            }
        }
        if (root.has("track_page")) {
            JSONObject trackPage = root.optJSONObject("track_page");
            if (trackPage != null) {
                return findSentencesArray(trackPage);
            }
        }
        if (root.has("loaderData")) {
            JSONObject loaderData = root.optJSONObject("loaderData");
            if (loaderData != null) {
                return findSentencesArray(loaderData);
            }
        }
        return null;
    }
}
