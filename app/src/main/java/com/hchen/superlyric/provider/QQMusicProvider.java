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
package com.hchen.superlyric.provider;

import android.app.Application;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐统一歌词提供者。
 * <p>
 * <b>逆向适配与架构设计说明 (QQ 音乐 20.9.0+)：</b>
 * <ul>
 *   <li><b>零混淆硬编码铁律 (Zero-Obfuscation Doctrine)：</b>
 *       通过 DexKit 检索唯一特征常量字符串 {@code "LyricLoadBean(songInfo="} 动态定位目标类，绝不硬编码混淆类名与字段名。</li>
 *   <li><b>源头根解析拦截：</b>
 *       坚决杜绝在 UI 渲染层（{@code LyricView}）或消费端控制器（{@code RemoteLyricController}）拦截。
 *       有且仅有 Hook 最核心的、最稳定的第一手全量歌词承载中枢 —— {@code LyricLoadBean} 构造函数。
 *       在其构造瞬间，直接截获包含逐字时间戳、原文、翻译、罗马音与曲目信息（{@code SongInfo}）的全部完整原始对象。</li>
 *   <li><b>严格类型隔离与拓扑推导：</b>
 *       针对播放引擎行模型（{@code com.lyricengine.base.t} 等），采用基于 Class 的类型隔离缓存与自适应字段探针，安全提取逐字数据。</li>
 *   <li><b>统一数据安全清洗：</b>
 *       提取的主歌词与逐字数据经 {@link LyricSanitizer} 深度清洗，防止字长越界与零宽字符导致系统界面崩溃。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.tencent.qqmusic")
public class QQMusicProvider extends UnifiedLyricProvider {
    private static final String TAG = "QQMusicProvider";

    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();
    private volatile String mLastProcessedTrackId = null;
    private volatile boolean mLastHadWords = false;
    private volatile int mLastLineCount = 0;

    // 自适应反射字段缓存（按行与词元 Class 隔离，零混淆硬编码，数学拓扑推导）
    private final Map<Class<?>, LineFieldResolver> mLineResolvers = new ConcurrentHashMap<>();
    private final Map<Class<?>, WordFieldResolver> mWordResolvers = new ConcurrentHashMap<>();

    @NonNull
    @Override
    public ProviderCapability capability() {
        return ProviderCapability.FULL_HOOK_ONLY;
    }

    @Nullable
    @Override
    protected IHookLyricEngine createHookEngine() {
        return new IHookLyricEngine() {
            @Override
            public void initHooks() {
                hookLyricLoadBeanInit();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                // 异步反序列化回调驱动，歌词就绪时通过 Hook 拦截即时上报
                return null;
            }
        };
    }

    @Nullable
    private static String getProcessNameCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        return null;
    }

    /**
     * 严密校验是否属于人类可读的常规元数据文本。
     * <p>
     * 坚决拦截 QQ 音乐中混杂的超长 QRC 密文 (Hex raw)、散列签名及未解析脏串。
     */
    private static boolean isPlausibleHumanText(@Nullable String s) {
        if (s == null) return false;
        String str = s.trim();
        if (str.isEmpty() || str.length() > 80) return false;
        if (str.startsWith("http://") || str.startsWith("https://")) return false;
        if (str.contains("\n") || str.contains("\r") || str.contains("\t")) return false;
        if ("未知歌手".equals(str) || "未知专辑".equals(str) || "null".equalsIgnoreCase(str))
            return false;
        // 过滤纯 Hex 密文 / 散列串 / 加密串 (通常长于 16 位的 16 进制字符串)
        if (str.length() >= 16 && str.matches("^[0-9A-Fa-f]+$")) return false;
        // 过滤结构化数据（JSON/XML 等）
        if (str.startsWith("{") || str.startsWith("[") || str.startsWith("<")) return false;
        return true;
    }

    /**
     * 通道 1：Hook {@code LyricLoadBean} 构造函数。
     * <p>
     * {@code LyricLoadBean} 构造时直接将 {@code SongInfo}、主歌词 {@code k}、翻译歌词 {@code k}、
     * 罗马音歌词 {@code k} 作为入参传入，是最纯净的全量歌词承载中枢。
     */
    private void hookLyricLoadBeanInit() {
        String proc = getProcessNameCompat();
        if (proc != null && proc.contains(":") && !proc.endsWith(":QQPlayerService")) {
            AndroidLog.logD(TAG, "Skipping LyricLoadBean hook in non-playback sub-process: " + proc);
            return;
        }

        try {
            Class<?> beanClass = DexkitCache.findMember("qqmusic_lyric_load_bean_class", new IDexkit<ClassData>() {
                @Nullable
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    ClassDataList beanClasses = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("LyricLoadBean(songInfo=")
                        )
                    );
                    if (beanClasses == null || beanClasses.isEmpty()) {
                        AndroidLog.logW(TAG, "DexKit failed to locate LyricLoadBean class");
                        return null;
                    }
                    return beanClasses.get(0);
                }
            });

            if (beanClass != null) {
                for (Constructor<?> c : beanClass.getDeclaredConstructors()) {
                    if (c.getParameterCount() == 8) {
                        c.setAccessible(true);
                        hook(c, new AbsHook() {
                            @Override
                            public void after() {
                                Object songInfo = getArg(0);
                                Object mainLyric = getArg(1);
                                Object transLyric = getArg(2);
                                Object romaLyric = getArg(3);
                                handleCapturedLyrics(songInfo, mainLyric, transLyric, romaLyric, "LyricLoadBean.<init>");
                            }
                        });
                        AndroidLog.logI(TAG, "Hooked LyricLoadBean constructor successfully");
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to hook LyricLoadBean constructor: " + t.getMessage());
        }
    }

    /**
     * 核心歌词组装、清洗与分发。
     */
    private void handleCapturedLyrics(@Nullable Object songInfo,
                                      @Nullable Object mainLyric,
                                      @Nullable Object transLyric,
                                      @Nullable Object romaLyric,
                                      @NonNull String source) {
        if (mainLyric == null) return;

        // 多进程协同防护：
        // QQ 音乐的主进程 (com.tencent.qqmusic) 为纯 UI 层，真正的音频播放中枢与 MediaSession 均在 :QQPlayerService 中。
        // 若当前处于主 UI 进程且未托管 MediaSession (mActiveTrack.get() == null)，严禁分发未锚定的歌词，防止污染播放进程的数据
        String processName = getProcessNameCompat();
        if (processName != null && !processName.endsWith(":QQPlayerService") && mActiveTrack.get() == null) {
            AndroidLog.logD(TAG, "Ignoring captured lyrics from non-player process (" + processName + ")");
            return;
        }

        try {
            // 1. 提取行列表
            List<ParsedLine> parsedLines = extractLines(mainLyric);
            if (parsedLines == null || parsedLines.isEmpty()) {
                AndroidLog.logW(TAG, "[" + source + "] No lines extracted from main lyric");
                return;
            }

            // 2. 提取并确定曲目元数据
            long estimatedDuration = parsedLines.get(parsedLines.size() - 1).endMs;
            TrackMetadata meta = extractMetadata(songInfo, mainLyric, estimatedDuration);

            // 若关键元数据严重缺失或异常，绝不分发残缺包
            if (meta.title.isEmpty()) {
                AndroidLog.logW(TAG, "[" + source + "] Dropping lyrics with empty title");
                return;
            }

            // 3. 关联翻译行
            if (transLyric != null) {
                applyTranslations(parsedLines, transLyric);
            }

            boolean hasWords = false;
            boolean hasTrans = false;
            for (ParsedLine pl : parsedLines) {
                if (pl.words != null && pl.words.length > 0) hasWords = true;
                if (pl.translation != null && !pl.translation.isEmpty()) hasTrans = true;
            }

            // 去重防护：若为同一曲目，仅在当前数据更完整（补充了逐字或行数更多）且非劣质数据时才允许更新
            if (Objects.equals(mLastProcessedTrackId, meta.trackId)) {
                if (mLastHadWords && !hasWords) {
                    return; // 劣质包（丢失逐字）丢弃
                }
                if (hasWords == mLastHadWords && parsedLines.size() <= mLastLineCount) {
                    return; // 重复或更少行数包丢弃
                }
            }
            mLastProcessedTrackId = meta.trackId;
            mLastHadWords = hasWords;
            mLastLineCount = parsedLines.size();

            // 4. 构建并清洗每一行
            SuperLyricLine[] lyricLines = new SuperLyricLine[parsedLines.size()];
            for (int i = 0; i < parsedLines.size(); i++) {
                ParsedLine pl = parsedLines.get(i);
                SuperLyricLine rawLine = new SuperLyricLine(
                    pl.text,
                    pl.words,
                    pl.translation,
                    pl.startMs,
                    pl.endMs
                );
                lyricLines[i] = LyricSanitizer.sanitizeLine(rawLine);
            }

            // 5. 构建全量包模型并做全局清洗
            SuperLyricData data = new SuperLyricData();
            data.setTitle(meta.title);
            data.setArtist(meta.artist);
            data.setAlbum(meta.album);
            data.setDuration(meta.duration);
            data.setAllLyrics(lyricLines);
            data.setLyricId(meta.trackId);

            SuperLyricData cleanData = LyricSanitizer.sanitizeData(data);
            if (cleanData == null) {
                AndroidLog.logW(TAG, "[" + source + "] Sanitized lyric data is null for " + meta.trackId);
                return;
            }

            // 6. 生成新音轨上下文并通知协调器（若为同一音轨继承 generation，杜绝 MediaSession 与 Hook 冲突导致的停止中断）
            TrackContext active = mActiveTrack.get();
            long gen;
            if (active != null && Objects.equals(active.getTrackId(), meta.trackId)) {
                gen = active.getGeneration();
            } else {
                gen = mTrackGeneration.incrementAndGet();
            }
            TrackContext context = new TrackContext(gen, meta.trackId, meta.title, meta.artist, meta.album, meta.duration);
            mActiveTrack.set(context);

            if (mOrchestrator != null) {
                mOrchestrator.onTrackChanged(context);
                mOrchestrator.onHookFullLyricCaptured(context, cleanData);
            }

            AndroidLog.logI(TAG, "[" + source + "] Successfully captured QQMusic lyrics: trackId=" + meta.trackId
                + ", title=" + meta.title
                + ", artist=" + meta.artist
                + ", lines=" + lyricLines.length
                + ", hasWords=" + hasWords
                + ", hasTrans=" + hasTrans);
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "[" + source + "] Error handling captured lyrics", t);
        }
    }

    /**
     * 自适应从歌词宿主对象中解析所有歌词行。
     */
    @Nullable
    private List<ParsedLine> extractLines(@NonNull Object lyricObj) {
        List<?> rawLines = extractRawLines(lyricObj);
        if (rawLines == null || rawLines.isEmpty()) {
            return null;
        }

        Object sample = rawLines.get(0);
        LineFieldResolver resolver = mLineResolvers.get(sample.getClass());
        if (resolver == null) {
            resolver = resolveLineFields(rawLines);
            if (resolver != null && resolver.isValid()) {
                mLineResolvers.put(sample.getClass(), resolver);
            }
        }

        if (resolver == null || !resolver.isValid()) {
            AndroidLog.logW(TAG, "Failed to resolve required lyric line fields for: " + sample.getClass().getName());
            return null;
        }

        List<ParsedLine> list = new ArrayList<>();
        for (Object rawLine : rawLines) {
            if (rawLine == null) continue;
            try {
                String text = (String) resolver.textField.get(rawLine);
                if (text == null || text.trim().isEmpty()) continue;

                long startMs = ((Number) resolver.startField.get(rawLine)).longValue();
                long durationMs = resolver.durationField != null ? ((Number) resolver.durationField.get(rawLine)).longValue() : 0;
                long endMs = durationMs > 0 ? (startMs + durationMs) : 0;

                // 提取逐字子元素
                SuperLyricWord[] words = null;
                if (resolver.wordsField != null) {
                    Object wordsVal = resolver.wordsField.get(rawLine);
                    if (wordsVal instanceof List<?> rawWords && !rawWords.isEmpty()) {
                        words = extractWords(rawWords, text, startMs, endMs);
                    }
                }

                list.add(new ParsedLine(text, startMs, endMs, words));
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Error extracting single line: " + t.getMessage());
            }
        }

        if (list.isEmpty()) return null;

        // 校准每行结束时间（若为 0 则平滑采用下一行起始时间）
        for (int i = 0; i < list.size(); i++) {
            ParsedLine cur = list.get(i);
            if (cur.endMs <= cur.startMs) {
                if (i + 1 < list.size()) {
                    cur.endMs = list.get(i + 1).startMs;
                } else {
                    cur.endMs = cur.startMs + 5000;
                }
            }
        }

        return list;
    }

    /**
     * 基于类型特征与时间单调不变量自适应发现行对象混淆字段（零混淆名称硬编码）。
     */
    @Nullable
    private LineFieldResolver resolveLineFields(@NonNull List<?> rawLines) {
        if (rawLines.isEmpty()) return null;
        Object sample = rawLines.get(0);
        Class<?> clazz = sample.getClass();

        Field textField = null;
        Field startField = null;
        Field durationField = null;
        Field wordsField = null;

        // 1. 唯一 String 字段即为行文本
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                textField = f;
                break;
            }
        }

        // 2. 收集所有数值型字段 (long / int)，通过全曲样本行单调递增性判定起始时间与持续时间
        List<Field> numFields = new ArrayList<>();
        for (Field f : clazz.getDeclaredFields()) {
            Class<?> type = f.getType();
            if (type == long.class || type == int.class) {
                f.setAccessible(true);
                numFields.add(f);
            }
        }

        if (numFields.size() == 1) {
            startField = numFields.get(0);
        } else if (numFields.size() >= 2) {
            Field bestStartField = null;
            int sampleCount = Math.min(rawLines.size(), 15);
            for (Field f : numFields) {
                boolean monotonic = true;
                long prev = -1;
                for (int i = 0; i < sampleCount; i++) {
                    try {
                        long val = ((Number) f.get(rawLines.get(i))).longValue();
                        if (val < 0) {
                            monotonic = false;
                            break;
                        }
                        if (prev != -1 && val < prev) {
                            monotonic = false;
                            break;
                        }
                        prev = val;
                    } catch (Throwable t) {
                        monotonic = false;
                        break;
                    }
                }
                if (monotonic && prev > 0) {
                    bestStartField = f;
                    break;
                }
            }

            if (bestStartField != null) {
                startField = bestStartField;
                for (Field f : numFields) {
                    if (f != bestStartField) {
                        durationField = f;
                        break;
                    }
                }
            } else {
                startField = numFields.get(0);
                durationField = numFields.get(1);
            }
        }

        // 3. 甄别逐字列表字段：类型为 List 且其元素为词元对象（含有数值字段）
        for (Field f : clazz.getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                for (Object line : rawLines) {
                    try {
                        Object val = f.get(line);
                        if (val instanceof List<?> list && !list.isEmpty()) {
                            Object elem = list.get(0);
                            if (elem != null && isWordElement(elem)) {
                                wordsField = f;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (wordsField != null) break;
            }
        }

        return new LineFieldResolver(clazz, textField, startField, durationField, wordsField);
    }

    private static boolean isPlausibleLineElement(@Nullable Object obj) {
        if (obj == null) return false;
        Class<?> clazz = obj.getClass();
        if (clazz.isEnum() || Enum.class.isAssignableFrom(clazz) || clazz.isPrimitive())
            return false;
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) return false;

        boolean hasString = false;
        boolean hasNumber = false;
        for (Field f : clazz.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == String.class) {
                hasString = true;
            } else if (t == long.class || t == int.class) {
                hasNumber = true;
            }
            if (hasString && hasNumber) return true;
        }
        return false;
    }

    private boolean isWordElement(@NonNull Object obj) {
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
            return false;
        }
        if (obj.getClass().isEnum() || Enum.class.isAssignableFrom(obj.getClass())) {
            return false;
        }
        int numCount = 0;
        for (Field f : obj.getClass().getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == long.class || t == int.class) {
                numCount++;
            }
        }
        return numCount >= 2;
    }

    /**
     * 关联并填充翻译歌词。
     */
    private void applyTranslations(@NonNull List<ParsedLine> mainLines, @NonNull Object transLyric) {
        List<?> rawTransLines = extractRawLines(transLyric);
        if (rawTransLines == null || rawTransLines.isEmpty()) return;

        Object sample = rawTransLines.get(0);
        LineFieldResolver resolver = mLineResolvers.get(sample.getClass());
        if (resolver == null) {
            resolver = resolveLineFields(rawTransLines);
            if (resolver != null && resolver.isValid()) {
                mLineResolvers.put(sample.getClass(), resolver);
            }
        }

        List<ParsedLine> transLines = new ArrayList<>();
        for (Object raw : rawTransLines) {
            if (raw == null) continue;
            try {
                String text = null;
                if (resolver != null && resolver.textField != null) {
                    text = (String) resolver.textField.get(raw);
                }
                if (text == null || text.trim().isEmpty()) {
                    for (Field f : raw.getClass().getDeclaredFields()) {
                        if (f.getType() == String.class) {
                            f.setAccessible(true);
                            Object val = f.get(raw);
                            if (val instanceof String s && !s.trim().isEmpty()) {
                                text = s;
                                break;
                            }
                        }
                    }
                }
                if (text == null || text.trim().isEmpty()) continue;

                long startMs = 0;
                if (resolver != null && resolver.startField != null) {
                    startMs = ((Number) resolver.startField.get(raw)).longValue();
                }
                transLines.add(new ParsedLine(text, startMs, 0, null));
            } catch (Throwable ignored) {
            }
        }

        if (transLines.isEmpty()) return;

        // 若翻译行数与主歌词严格一致，按行索引一对一匹配
        if (transLines.size() == mainLines.size()) {
            for (int i = 0; i < mainLines.size(); i++) {
                mainLines.get(i).translation = transLines.get(i).text;
            }
            return;
        }

        // 行数不一致时采用时间戳最近邻匹配 (容差 800ms)
        for (ParsedLine trans : transLines) {
            ParsedLine bestMatch = null;
            long minDiff = 800;
            for (ParsedLine main : mainLines) {
                long diff = Math.abs(main.startMs - trans.startMs);
                if (diff < minDiff) {
                    minDiff = diff;
                    bestMatch = main;
                }
            }
            if (bestMatch != null && bestMatch.translation == null) {
                bestMatch.translation = trans.text;
            }
        }
    }

    /**
     * 基于数学拓扑与边界单调不变量自适应解析逐字词元字段（零混淆名称硬编码）。
     */
    @Nullable
    private WordFieldResolver resolveWordFields(@NonNull List<?> wordList, @NonNull String lineText) {
        if (wordList.isEmpty() || lineText.isEmpty()) return null;
        Object first = wordList.get(0);
        Class<?> clazz = first.getClass();

        int textLen = lineText.length();

        Field textField = null;
        // 1. 甄别 String 字段
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                textField = f;
                break;
            }
        }

        // 2. 甄别 int 字段: charEnd 与 charStart
        List<Field> intFields = new ArrayList<>();
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == int.class) {
                f.setAccessible(true);
                intFields.add(f);
            }
        }

        Field candidateCharEnd = null;
        Field candidateCharStart = null;

        if (intFields.size() >= 2) {
            int bestScore = -1;
            for (Field fEnd : intFields) {
                for (Field fStart : intFields) {
                    if (fEnd == fStart) continue;
                    int score = 0;
                    boolean valid = true;
                    int prevEnd = 0;
                    for (Object w : wordList) {
                        try {
                            int s = fStart.getInt(w);
                            int e = fEnd.getInt(w);
                            if (s < 0 || e < s || e > textLen + 5) {
                                valid = false;
                                break;
                            }
                            if (e > s) {
                                score++;
                            }
                            if (s < prevEnd) {
                                valid = false;
                                break;
                            }
                            prevEnd = e;
                        } catch (Throwable t) {
                            valid = false;
                            break;
                        }
                    }
                    if (valid && score > bestScore) {
                        bestScore = score;
                        candidateCharStart = fStart;
                        candidateCharEnd = fEnd;
                    }
                }
            }
        }

        // 3. 甄别 long 字段: startMs 与 durationMs
        List<Field> longFields = new ArrayList<>();
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == long.class) {
                f.setAccessible(true);
                longFields.add(f);
            }
        }

        Field candidateStartMs = null;
        Field candidateDurationMs = null;

        if (longFields.size() >= 2) {
            Field f0 = longFields.get(0);
            Field f1 = longFields.get(1);
            boolean f0Monotonic = true;
            boolean f1Monotonic = true;
            long prev0 = -1;
            long prev1 = -1;
            for (Object w : wordList) {
                try {
                    long v0 = f0.getLong(w);
                    long v1 = f1.getLong(w);
                    if (prev0 != -1 && v0 < prev0) f0Monotonic = false;
                    if (prev1 != -1 && v1 < prev1) f1Monotonic = false;
                    prev0 = v0;
                    prev1 = v1;
                } catch (Throwable ignored) {
                }
            }
            if (f0Monotonic && !f1Monotonic) {
                candidateStartMs = f0;
                candidateDurationMs = f1;
            } else if (f1Monotonic && !f0Monotonic) {
                candidateStartMs = f1;
                candidateDurationMs = f0;
            } else {
                candidateStartMs = f0;
                candidateDurationMs = f1;
            }
        } else if (longFields.size() == 1) {
            candidateStartMs = longFields.get(0);
        }

        if (candidateCharEnd == null) return null;
        return new WordFieldResolver(clazz, candidateCharStart, candidateCharEnd, candidateStartMs, candidateDurationMs, textField);
    }

    /**
     * 提取逐字数据并校验字符切片连续性与 100% 重建还原。
     */
    @Nullable
    private SuperLyricWord[] extractWords(@NonNull List<?> rawWords,
                                          @NonNull String lineText,
                                          long lineStartMs,
                                          long lineEndMs) {
        if (rawWords.isEmpty() || lineText.isEmpty()) return null;

        Object first = rawWords.get(0);
        WordFieldResolver resolver = mWordResolvers.get(first.getClass());
        if (resolver == null) {
            resolver = resolveWordFields(rawWords, lineText);
            if (resolver != null && resolver.isValid()) {
                mWordResolvers.put(first.getClass(), resolver);
            }
        }

        if (resolver == null || !resolver.isValid()) {
            return null;
        }

        List<SuperLyricWord> wordList = new ArrayList<>(rawWords.size());
        int prevEnd = 0;
        int textLen = lineText.length();
        int rawCount = rawWords.size();

        for (int i = 0; i < rawCount; i++) {
            Object wordObj = rawWords.get(i);
            if (wordObj == null) continue;
            try {
                int cEnd = resolver.charEndField.getInt(wordObj);
                int cStart = resolver.charStartField != null ? resolver.charStartField.getInt(wordObj) : prevEnd;

                // 容错 1：若 cEnd <= cStart，说明为 0 长度停顿/呼吸标记，平滑跳过
                if (cEnd <= cStart) {
                    continue;
                }

                // 容错 2：若超出整行文本长度，截断收敛
                if (cStart >= textLen) {
                    continue;
                }
                cEnd = Math.min(cEnd, textLen);

                // 容错 3：若 cStart < prevEnd，修正为 prevEnd 避免倒错/重叠
                if (cStart < prevEnd) {
                    cStart = prevEnd;
                }
                if (cEnd <= cStart) {
                    continue;
                }

                // 容错 4：若 cStart > prevEnd，说明存在空格或未被词元覆盖的间隙标点
                // 将间隙平滑并入本词（从 prevEnd 到 cEnd），实现整行 100% 严密接缝
                int sliceStart = prevEnd;
                int sliceEnd = cEnd;

                String text = lineText.substring(sliceStart, sliceEnd);
                prevEnd = sliceEnd;

                long rawStart = resolver.startField != null ? resolver.startField.getLong(wordObj) : -1;
                long rawDur = resolver.durationField != null ? resolver.durationField.getLong(wordObj) : -1;

                long absStart;
                if (rawStart >= lineStartMs) {
                    absStart = rawStart;
                } else if (rawStart >= 0) {
                    absStart = lineStartMs + rawStart;
                } else {
                    absStart = lineStartMs;
                }

                long absEnd;
                if (rawDur > 0) {
                    absEnd = absStart + rawDur;
                } else {
                    // 启发式自愈：rawDur <= 0 时优先采纳下一词起始，否则按字符权重保底
                    long nextAbsStart = -1L;
                    if (i + 1 < rawCount) {
                        Object nextObj = rawWords.get(i + 1);
                        if (nextObj != null && resolver.startField != null) {
                            long nrStart = resolver.startField.getLong(nextObj);
                            nextAbsStart = (nrStart >= lineStartMs) ? nrStart : (nrStart >= 0 ? lineStartMs + nrStart : -1L);
                        }
                    }
                    if (nextAbsStart > absStart) {
                        absEnd = nextAbsStart;
                    } else {
                        absEnd = absStart + Math.max(120L, (long) text.length() * 150L);
                        if (lineEndMs > absStart && absEnd > lineEndMs) {
                            absEnd = lineEndMs;
                        }
                    }
                }
                if (absEnd <= absStart) {
                    absEnd = absStart + Math.max(120L, (long) text.length() * 150L);
                }

                wordList.add(new SuperLyricWord(text, absStart, absEnd));
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "extractWords: error extracting word: " + t.getMessage());
            }
        }

        if (wordList.isEmpty()) {
            return null;
        }

        // 容错 5：若末尾还有剩余字符（如末尾标点、空格），平滑补入最后一个词
        if (prevEnd < textLen) {
            SuperLyricWord last = wordList.get(wordList.size() - 1);
            String paddedText = last.getWord() + lineText.substring(prevEnd);
            wordList.set(wordList.size() - 1, new SuperLyricWord(paddedText, last.getStartTime(), last.getEndTime()));
        }

        LyricSanitizer.healWordTimings(wordList, lineStartMs, lineEndMs);
        Collections.sort(wordList, Comparator.comparingLong(SuperLyricWord::getStartTime));
        return wordList.toArray(new SuperLyricWord[0]);
    }

    /**
     * 从任意歌词对象中提取原始行列表 (List)。
     */
    @Nullable
    private List<?> extractRawLines(@Nullable Object lyricObj) {
        if (lyricObj == null) return null;
        Class<?> clazz = lyricObj.getClass();
        if (clazz.isEnum() || Enum.class.isAssignableFrom(clazz) || clazz.isPrimitive())
            return null;

        String className = clazz.getName().toLowerCase();
        // 歌词对象必须来源于歌词相关包名或类名
        if (!className.contains("lyric")) {
            return null;
        }

        // 1. 优先扫描字段中的 List
        for (Field f : clazz.getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(lyricObj);
                    if (val instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (isPlausibleLineElement(first)) {
                            return list;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 2. 扫描无参实例方法返回的 List
        for (Method m : clazz.getMethods()) {
            if (m.getParameterCount() == 0
                && !Modifier.isStatic(m.getModifiers())
                && List.class.isAssignableFrom(m.getReturnType())) {
                try {
                    m.setAccessible(true);
                    Object val = m.invoke(lyricObj);
                    if (val instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (isPlausibleLineElement(first)) {
                            return list;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 自适应提取曲目元数据（优先 MediaSession 与标准公开字段，完全摒弃混淆反射）。
     */
    @NonNull
    private TrackMetadata extractMetadata(@Nullable Object songInfo,
                                          @Nullable Object mainLyric,
                                          long fallbackDuration) {
        String title = "";
        String artist = "";
        String album = "";
        String trackId = "";
        long duration = fallbackDuration;

        // 1. 优先采用系统 MediaSession 追踪到的权威元数据
        TrackContext active = mActiveTrack.get();
        if (active != null) {
            if (isPlausibleHumanText(active.getTitle())) title = active.getTitle();
            if (isPlausibleHumanText(active.getArtist())) artist = active.getArtist();
            if (isPlausibleHumanText(active.getAlbum())) album = active.getAlbum();
            if (active.getDuration() > 0) duration = active.getDuration();
            if (active.getTrackId() != null && !active.getTrackId().isEmpty())
                trackId = active.getTrackId();
        }

        // 2. 从 songInfo 提取标准化字段
        long songNumericId = 0;
        String songMid = "";
        if (songInfo != null) {
            // 2.1 优先通过 shortMessage() 解析 name, singer 与 id (100% 避开任何混淆，标准化文本)
            try {
                Method smMethod = songInfo.getClass().getMethod("shortMessage");
                Object res = smMethod.invoke(songInfo);
                if (res instanceof String sm && !sm.isEmpty()) {
                    Matcher m = Pattern.compile("id\\s*=\\s*(\\d+).*?name\\s*=\\s*(.*?)\\s+singer\\s*=\\s*(.*?)\\s+tmpPlayKey\\s*=").matcher(sm);
                    if (m.find()) {
                        String idStr = m.group(1).trim();
                        String nameStr = m.group(2).trim();
                        String singerStr = m.group(3).trim();

                        try {
                            songNumericId = Long.parseLong(idStr);
                        } catch (Throwable ignored) {
                        }

                        if (title.isEmpty() && isPlausibleHumanText(nameStr)) {
                            title = nameStr;
                        }

                        if (artist.isEmpty() && isPlausibleHumanText(singerStr)) {
                            int dotIdx = singerStr.indexOf('·');
                            if (dotIdx > 0) {
                                String sName = singerStr.substring(0, dotIdx).trim();
                                String aName = singerStr.substring(dotIdx + 1).trim();
                                if (isPlausibleHumanText(sName)) artist = sName;
                                if (album.isEmpty() && isPlausibleHumanText(aName)) album = aName;
                            } else {
                                artist = singerStr;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2.2 通过 songInfo.toString() 解析 mid 和 name 备用
            try {
                String str = songInfo.toString();
                Matcher midMatcher = Pattern.compile("mid=([^,]+)").matcher(str);
                if (midMatcher.find()) {
                    String m = midMatcher.group(1).trim();
                    if (!m.isEmpty()) songMid = m;
                }
                Matcher idMatcher = Pattern.compile("id=(\\d+)").matcher(str);
                if (songNumericId == 0 && idMatcher.find()) {
                    try {
                        songNumericId = Long.parseLong(idMatcher.group(1).trim());
                    } catch (Throwable ignored) {
                    }
                }
                Matcher nameMatcher = Pattern.compile("name=([^,]+)").matcher(str);
                if (nameMatcher.find()) {
                    String n = nameMatcher.group(1).trim();
                    if (title.isEmpty() && isPlausibleHumanText(n)) title = n;
                }
            } catch (Throwable ignored) {
            }

            // 2.3 动态扫描 ID3 实体类中的 String 字段
            try {
                for (Field f : songInfo.getClass().getDeclaredFields()) {
                    if (f.getType().getName().contains("ID3")) {
                        f.setAccessible(true);
                        Object id3 = f.get(songInfo);
                        if (id3 != null) {
                            List<String> validStrings = new ArrayList<>();
                            for (Field sf : id3.getClass().getDeclaredFields()) {
                                if (sf.getType() == String.class) {
                                    sf.setAccessible(true);
                                    Object val = sf.get(id3);
                                    if (val instanceof String s && isPlausibleHumanText(s)) {
                                        validStrings.add(s);
                                    }
                                }
                            }
                            for (String s : validStrings) {
                                if (title.isEmpty()) {
                                    title = s;
                                } else if (artist.isEmpty() && !s.equals(title)) {
                                    artist = s;
                                } else if (album.isEmpty() && !s.equals(title) && !s.equals(artist)) {
                                    album = s;
                                }
                            }
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. 从 Lyric 对象中提取备用 String 字段元数据 (严格过滤纯 Hex 密文与非人类文本)
        if (mainLyric != null && (title.isEmpty() || artist.isEmpty() || album.isEmpty())) {
            try {
                for (Field f : mainLyric.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        Object val = f.get(mainLyric);
                        if (val instanceof String s && isPlausibleHumanText(s)) {
                            if (title.isEmpty()) {
                                title = s;
                            } else if (artist.isEmpty() && !s.equals(title)) {
                                artist = s;
                            } else if (album.isEmpty() && !s.equals(title) && !s.equals(artist)) {
                                album = s;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 4. 强制轨道标识与 MediaSession 保持谐振
        // 若 active 匹配当前曲目（标题一致或 ID 一致），强制使用 active 的 trackId，确保与 MediaSession 保持 100% 连贯无缝
        if (active != null && !active.getTrackId().isEmpty()) {
            boolean matchesActive = false;
            if (songNumericId > 0 && active.getTrackId().equals(String.valueOf(songNumericId))) {
                matchesActive = true;
            } else if (!songMid.isEmpty() && active.getTrackId().equals(songMid)) {
                matchesActive = true;
            } else if (!title.isEmpty() && title.equals(active.getTitle())) {
                matchesActive = true;
            }
            if (matchesActive) {
                trackId = active.getTrackId();
                if (title.isEmpty() && active.getTitle() != null) title = active.getTitle();
                if (artist.isEmpty() && active.getArtist() != null) artist = active.getArtist();
                if (album.isEmpty() && active.getAlbum() != null) album = active.getAlbum();
                if (duration <= 0 && active.getDuration() > 0) duration = active.getDuration();
            }
        }

        // 若 trackId 尚未确定，优先采用与 MediaSession 对齐的 numeric ID，其次 mid
        if (trackId.isEmpty()) {
            if (songNumericId > 0) {
                trackId = String.valueOf(songNumericId);
            } else if (!songMid.isEmpty()) {
                trackId = songMid;
            } else if (!title.isEmpty()) {
                trackId = title + (!artist.isEmpty() ? ("_" + artist) : "");
            } else {
                trackId = String.valueOf(System.currentTimeMillis());
            }
        }

        return new TrackMetadata(title, artist, album, trackId, duration);
    }

    private static class LineFieldResolver {
        final Class<?> lineClass;
        final Field textField;
        final Field startField;
        final Field durationField;
        final Field wordsField;

        LineFieldResolver(Class<?> lineClass, Field textField, Field startField, Field durationField, Field wordsField) {
            this.lineClass = lineClass;
            this.textField = textField;
            this.startField = startField;
            this.durationField = durationField;
            this.wordsField = wordsField;
        }

        boolean isValid() {
            return textField != null && startField != null;
        }
    }

    private static class WordFieldResolver {
        final Class<?> wordClass;
        final Field charStartField;
        final Field charEndField;
        final Field startField;
        final Field durationField;
        final Field textField;

        WordFieldResolver(Class<?> wordClass, Field charStartField, Field charEndField, Field startField, Field durationField, Field textField) {
            this.wordClass = wordClass;
            this.charStartField = charStartField;
            this.charEndField = charEndField;
            this.startField = startField;
            this.durationField = durationField;
            this.textField = textField;
        }

        boolean isValid() {
            return charEndField != null;
        }
    }

    private static class ParsedLine {
        final String text;
        final long startMs;
        long endMs;
        final SuperLyricWord[] words;
        String translation;

        ParsedLine(String text, long startMs, long endMs, SuperLyricWord[] words) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.words = words;
        }
    }

    private static class TrackMetadata {
        final String title;
        final String artist;
        final String album;
        final String trackId;
        final long duration;

        TrackMetadata(String title, String artist, String album, String trackId, long duration) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.trackId = trackId;
            this.duration = duration;
        }
    }
}
