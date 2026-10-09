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
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 波点音乐统一歌词提供者。
 * <p>
 * <b>逆向适配说明 (波点音乐 5.9.8+)：</b>
 * <ul>
 *   <li><b>歌词解析流水线：</b>
 *       宿主在接收到歌词下载完成后，由 {@code LyricsRunner4Flutter} (包含唯一特征字符串 "LyricsRunner4Flutter")
 *       根据格式 (LRC / 逐字 LRCX) 实例化对应解析器生成未折行的全量歌词对象 {@code ILyrics}。
 *       解析完成通过该类的分发方法分发给观察者和全局状态。
 *       我们在分发方法拦截，直接捕获反序列化完成的完整歌词行和当前歌曲实体 ({@code Music})。</li>
 *   <li><b>播放状态与时钟调度：</b>
 *       已完全由系统框架级 {@code MediaSessionManager} 全局监听与驱动，无需应用内私有播放器委托 Hook。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "cn.wenyu.bodian")
public class BodianProvider extends UnifiedLyricProvider {
    private static final String TAG = "BodianProvider";

    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();

    // 线程安全的全局类级反射字段缓存（一次解析，永久复用，杜绝重复搜索）
    private static final java.util.Map<Class<?>, LineFieldResolver> sLineResolvers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, SubWordResolver> sSubWordResolvers = new java.util.concurrent.ConcurrentHashMap<>();

    private static class LineFieldResolver {
        final Field timestampField;
        final Field textField;
        final Field isTransField;
        final Field subListField;

        LineFieldResolver(Field timestampField, Field textField, Field isTransField, Field subListField) {
            this.timestampField = timestampField;
            this.textField = textField;
            this.isTransField = isTransField;
            this.subListField = subListField;
        }

        boolean isValid() {
            return timestampField != null && textField != null;
        }
    }

    private static class SubWordResolver {
        final Field charStartField;
        final Field charEndField;
        final Field startTimeField;
        final Field endTimeField;

        SubWordResolver(Field charStartField, Field charEndField, Field startTimeField, Field endTimeField) {
            this.charStartField = charStartField;
            this.charEndField = charEndField;
            this.startTimeField = startTimeField;
            this.endTimeField = endTimeField;
        }

        boolean isValid() {
            return charEndField != null && startTimeField != null && endTimeField != null;
        }
    }

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
                hookLyricDispatch();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                // 波点音乐为异步解析回调驱动模式，歌词解析就绪时由 hook 直接捕获并上报
                return null;
            }
        };
    }

    /**
     * Hook 歌词分发中枢。
     * <p>
     * 遵循从大到小两段式收敛原则：
     * <ol>
     *   <li>查找包含常量字符串 {@code "LyricsRunner4Flutter"} 的候选大类；</li>
     *   <li>在候选类中筛选包含 3 个参数且返回值为 {@code void} 的分发方法 ({@code g(status, rawLyrics, foldedLyrics)})，
     *       排除同样引用该常量但仅有无参 {@code call()} 回调的内部类 ({@code $a})。</li>
     * </ol>
     */
    private void hookLyricDispatch() {
        try {
            Method dispatchMethod = DexkitCache.findMember("bodian_lyric_dispatch_v2", new IDexkit<MethodData>() {
                @Nullable
                @Override
                public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    // 大类收敛：包含 "LyricsRunner4Flutter" 常量字符串的所有候选类
                    ClassDataList runnerClasses = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("LyricsRunner4Flutter")
                        )
                    );

                    if (runnerClasses == null || runnerClasses.isEmpty()) {
                        AndroidLog.logE(TAG, "DexKit failed to locate LyricsRunner4Flutter class");
                        return null;
                    }

                    // 方法收敛：遍历候选类，甄别拥有 3 参数且返回 void 的分发方法（排除内部类与构造函数）
                    for (ClassData cd : runnerClasses) {
                        MethodDataList methods = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                .declaredClass(cd.getName())
                                .paramCount(3)
                                .returnType(void.class)
                            )
                        );
                        if (methods != null) {
                            for (MethodData md : methods) {
                                if (md.isMethod()) {
                                    return md;
                                }
                            }
                        }
                    }

                    AndroidLog.logE(TAG, "Cannot find Bodian lyric dispatch method in candidate classes");
                    return null;
                }
            });

            if (dispatchMethod == null) {
                AndroidLog.logE(TAG, "Cannot find Bodian lyric dispatch method");
                return;
            }

            hook(dispatchMethod, new AbsHook() {
                @Override
                public void after() {
                    Object status = getArg(0);
                    Object iLyrics = getArg(1); // 未折行的完整 ILyrics 对象
                    Object runnerThis = getThisObject();

                    // 状态判定：如果是失败状态则告知协调器
                    String statusName = status != null ? status.toString() : "";
                    if ("FAILED".equalsIgnoreCase(statusName) || "NONE".equalsIgnoreCase(statusName)) {
                        TrackContext active = mActiveTrack.get();
                        if (active != null && mOrchestrator != null) {
                            mOrchestrator.onHookDeterminedInvalid(active);
                        }
                        return;
                    }

                    if (!"SUCCESS".equalsIgnoreCase(statusName) || iLyrics == null || runnerThis == null) {
                        return;
                    }

                    handleFullLyricCaptured(runnerThis, iLyrics);
                }
            });

            AndroidLog.logI(TAG, "Hooked Bodian lyric dispatch method: " + dispatchMethod.getName());
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to hook Bodian lyric dispatch", t);
        }
    }

    /**
     * 处理捕获到的整首歌词与当前曲目信息。
     */
    private void handleFullLyricCaptured(@NonNull Object runner, @NonNull Object iLyrics) {
        try {
            // 1. 从 LyricsRunner4Flutter 提取当前 Music 对象
            Object music = extractMusicFromRunner(runner);
            String title = "";
            String artist = "";
            String album = "";
            String rid = "";
            long duration = 0;

            if (music != null) {
                title = safeString(callMethod(music, "getName"));
                artist = safeString(callMethod(music, "getArtist"));
                album = safeString(callMethod(music, "getAlbum"));
                rid = safeString(getField(music, "rid"));

                Object durObj = callMethod(music, "getDuration");
                if (durObj instanceof Number) {
                    duration = ((Number) durObj).longValue();
                }
                if (duration <= 0) {
                    Object durAlt = callMethod(music, "getDur");
                    if (durAlt instanceof Number) {
                        duration = ((Number) durAlt).longValue();
                    }
                }
            }

            // 生成新的音轨上下文
            long gen = mTrackGeneration.incrementAndGet();
            String trackId = !rid.isEmpty() ? rid : (title + "_" + artist);
            TrackContext context = new TrackContext(gen, trackId, title, artist, album, duration);
            mActiveTrack.set(context);

            if (mOrchestrator != null) {
                mOrchestrator.onTrackChanged(context);
            }

            // 2. 从 ILyrics 提取原始行列表
            List<?> rawLines = extractLinesFromILyrics(iLyrics);
            if (rawLines == null || rawLines.isEmpty()) {
                AndroidLog.logW(TAG, "Extracted raw lyric lines list is empty for: " + trackId);
                return;
            }

            // 3. 将宿主数据结构解析转换为标准 SuperLyricLine[]
            SuperLyricLine[] lyricLines = convertRawLines(rawLines, duration);
            if (lyricLines == null || lyricLines.length == 0) {
                AndroidLog.logW(TAG, "Converted SuperLyricLine array is empty for: " + trackId);
                return;
            }

            // 4. 构建全量 SuperLyricData
            SuperLyricData data = new SuperLyricData();
            data.setTitle(title);
            data.setArtist(artist);
            data.setAlbum(album);
            data.setDuration(duration);
            data.setAllLyrics(lyricLines);
            data.setLyricId(trackId);

            boolean hasTrans = false;
            boolean hasWords = false;
            for (SuperLyricLine line : lyricLines) {
                if (line.hasTranslation()) hasTrans = true;
                if (line.getWords() != null && line.getWords().length > 0) hasWords = true;
                if (hasTrans && hasWords) break;
            }
            AndroidLog.logI(TAG, "Successfully extracted Bodian full lyrics: trackId=" + trackId
                + ", title=" + title
                + ", artist=" + artist
                + ", lines=" + lyricLines.length
                + ", hasTrans=" + hasTrans
                + ", hasWords=" + hasWords);

            SuperLyricData cleanData = LyricSanitizer.sanitizeData(data);
            if (cleanData == null) {
                AndroidLog.logW(TAG, "Sanitized Bodian full lyric data is null for: " + trackId);
                return;
            }

            // 5. 移交协调器广播全量包并启动 Tracker
            if (mOrchestrator != null) {
                mOrchestrator.onHookFullLyricCaptured(context, cleanData);
            }
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Error handling captured Bodian lyric", t);
        }
    }

    /**
     * 从 ILyrics 中找到返回 List 的获取所有行方法并调用。
     */
    @Nullable
    private List<?> extractLinesFromILyrics(@NonNull Object iLyrics) {
        // 遍历所有无参返回 List 的方法
        for (Method method : iLyrics.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && List.class.isAssignableFrom(method.getReturnType())) {
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(iLyrics);
                    if (result instanceof List<?> list && !list.isEmpty()) {
                        // 确认元素不是 String (如果是 List<String> 则是纯文本列表，非行模型)
                        Object first = list.get(0);
                        if (!(first instanceof String)) {
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
     * 将宿主的原始行列表转换映射为 SuperLyricLine 数组。
     */
    @Nullable
    private SuperLyricLine[] convertRawLines(@NonNull List<?> rawLines, long totalDuration) {
        if (rawLines.isEmpty()) return null;

        LineFieldResolver lineResolver = getOrResolveLineFields(rawLines.get(0));
        if (lineResolver == null || !lineResolver.isValid()) {
            AndroidLog.logE(TAG, "Failed to resolve required lyric line fields");
            return null;
        }

        SubWordResolver subResolver = null;
        if (lineResolver.subListField != null) {
            subResolver = getOrResolveSubFields(rawLines, lineResolver);
        }

        List<ParsedLine> parsedList = new ArrayList<>();
        ParsedLine pendingOriginal = null;

        for (Object raw : rawLines) {
            try {
                Integer timestamp = (Integer) lineResolver.timestampField.get(raw);
                String text = (String) lineResolver.textField.get(raw);
                boolean isTranslation = false;
                if (lineResolver.isTransField != null) {
                    Object transObj = lineResolver.isTransField.get(raw);
                    if (transObj instanceof Boolean) {
                        isTranslation = (Boolean) transObj;
                    }
                }

                if (text == null || timestamp == null) continue;

                // 提取逐字子元素 (若为逐字歌词)
                SuperLyricWord[] words = null;
                if (subResolver != null && lineResolver.subListField != null) {
                    Object subObj = lineResolver.subListField.get(raw);
                    if (subObj instanceof List<?> subList && !subList.isEmpty()) {
                        words = extractWords(subResolver, subList, text, timestamp);
                    }
                }

                if (isTranslation) {
                    // 波点音乐带翻译行交替排布且挂在对应原文后
                    if (!text.trim().isEmpty() && pendingOriginal != null) {
                        pendingOriginal.translation = text;
                    }
                } else {
                    pendingOriginal = new ParsedLine(text, timestamp, words);
                    parsedList.add(pendingOriginal);
                }
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Error parsing single raw line: " + t.getMessage());
            }
        }

        if (parsedList.isEmpty()) return null;

        // 计算每行起始与结束时间并经过全局安全清洗
        SuperLyricLine[] result = new SuperLyricLine[parsedList.size()];
        for (int i = 0; i < parsedList.size(); i++) {
            ParsedLine cur = parsedList.get(i);
            long endMs;
            if (i + 1 < parsedList.size()) {
                endMs = parsedList.get(i + 1).startMs;
            } else if (totalDuration > 0) {
                endMs = totalDuration;
            } else {
                endMs = cur.startMs + 5000;
            }

            SuperLyricLine rawLine = new SuperLyricLine(
                cur.text,
                cur.words,
                cur.translation,
                cur.startMs,
                endMs
            );
            result[i] = LyricSanitizer.sanitizeLine(rawLine);
        }

        return result;
    }

    /**
     * 提取全曲最佳样本行解析逐字词元字段并全局类级缓存。
     */
    @Nullable
    private SubWordResolver getOrResolveSubFields(@NonNull List<?> rawLines, @NonNull LineFieldResolver lineResolver) {
        for (Object raw : rawLines) {
            try {
                Object subObj = lineResolver.subListField.get(raw);
                if (subObj instanceof List<?> subList && !subList.isEmpty()) {
                    Object firstElem = subList.get(0);
                    if (firstElem != null) {
                        SubWordResolver cached = sSubWordResolvers.get(firstElem.getClass());
                        if (cached != null && cached.isValid()) {
                            return cached;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        SampleLine preferredSample = null;
        SampleLine fallbackSample = null;

        for (Object raw : rawLines) {
            try {
                String text = (String) lineResolver.textField.get(raw);
                if (text == null || text.trim().isEmpty()) continue;

                Object subObj = lineResolver.subListField.get(raw);
                if (subObj instanceof List<?> subList && !subList.isEmpty()) {
                    SampleLine sl = new SampleLine(subList, text);
                    if (subList.size() >= 2 && subList.size() < text.length()) {
                        preferredSample = sl;
                        break;
                    }
                    if (fallbackSample == null && subList.size() >= 2) {
                        fallbackSample = sl;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        SubWordResolver resolved = null;
        if (preferredSample != null) {
            resolved = resolveSubFields(preferredSample.subList, preferredSample.text);
        } else if (fallbackSample != null) {
            resolved = resolveSubFields(fallbackSample.subList, fallbackSample.text);
        }

        if (resolved != null && resolved.isValid()) {
            Object sampleObj = (preferredSample != null ? preferredSample.subList : fallbackSample.subList).get(0);
            sSubWordResolvers.put(sampleObj.getClass(), resolved);
            AndroidLog.logI(TAG, "Cached SubWordResolver for " + sampleObj.getClass().getName());
        }

        return resolved;
    }

    /**
     * 提取逐字数据并映射为 {@link SuperLyricWord} 数组，同时施加时序自愈修复。
     */
    @Nullable
    private SuperLyricWord[] extractWords(@NonNull SubWordResolver resolver,
                                          @NonNull List<?> subList,
                                          @NonNull String lineText,
                                          long lineStartMs) {
        if (subList.isEmpty() || lineText.isEmpty() || !resolver.isValid()) return null;

        List<SuperLyricWord> rawWords = new ArrayList<>(subList.size());
        int prevEnd = 0;
        int textLen = lineText.length();
        StringBuilder reconstructed = new StringBuilder();

        for (Object eh : subList) {
            if (eh == null) continue;
            try {
                int cEnd = resolver.charEndField.getInt(eh);
                int cStart = resolver.charStartField != null ? resolver.charStartField.getInt(eh) : prevEnd;

                // 容错 1：0 长度停顿/标记跳过
                if (cEnd <= cStart) {
                    continue;
                }

                // 容错 2：越界截断
                if (cStart >= textLen) {
                    continue;
                }
                cEnd = Math.min(cEnd, textLen);

                // 容错 3：重叠修正
                if (cStart < prevEnd) {
                    cStart = prevEnd;
                }
                if (cEnd <= cStart) {
                    continue;
                }

                // 容错 4：平滑包含空格等间隙，实现严密连续切分
                int sliceStart = prevEnd;
                int sliceEnd = cEnd;
                String wordText = lineText.substring(sliceStart, sliceEnd);
                prevEnd = sliceEnd;
                reconstructed.append(wordText);

                int startOffsetMs = resolver.startTimeField.getInt(eh);
                int endOffsetMs = resolver.endTimeField.getInt(eh);

                long absStart;
                long absEnd;
                if (startOffsetMs >= lineStartMs) {
                    absStart = startOffsetMs;
                    absEnd = endOffsetMs;
                } else {
                    absStart = lineStartMs + startOffsetMs;
                    absEnd = lineStartMs + endOffsetMs;
                }

                rawWords.add(new SuperLyricWord(wordText, absStart, absEnd));
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Error extracting sub word: " + t.getMessage());
                return null;
            }
        }

        if (rawWords.isEmpty()) return null;

        // 字符切片语义一致性校验
        if (prevEnd != lineText.length() || !reconstructed.toString().equals(lineText)) {
            // 允许去除空格后一致（兼容不切空格的逐字约定）
            String recNoSpace = reconstructed.toString().replaceAll("\\s+", "");
            String lineNoSpace = lineText.replaceAll("\\s+", "");
            if (!recNoSpace.equals(lineNoSpace)) {
                AndroidLog.logW(TAG, "extractWords: reconstructed words mismatch with line text: '"
                    + reconstructed + "' vs '" + lineText + "', dropping words");
                return null;
            }
        }

        // 核心时序自愈算法：修复波点音乐源码中由重叠截断导致的 0ms 持续时间与异常时长
        healWordTimings(rawWords, lineStartMs);

        return rawWords.toArray(new SuperLyricWord[0]);
    }

    /**
     * 波点音乐逐字时序自愈算法。
     * <p>
     * <b>逆向根因与自愈原理：</b>
     * 波点宿主内部 {@code VerbatimLyricsParserImpl.e()} 在处理相邻词元重叠（{@code i3 < hVar2.e}）时，
     * 存在致命代码：{@code hVar2.e = i3; if (hVar2.d < i3) hVar2.d = i3;}。
     * 这导致前一词元的起始时间与结束时间双双被覆写为后一词元的起始 {@code i3}，使持续时间瞬间坍缩为 0ms！
     * <p>
     * 本自愈器通过全句拓扑时隙链重构：
     * 1. 当发现词元持续时间 {@code absEnd <= absStart}（0ms 坍缩）时，提取其前驱词元的实际结束点 {@code prevEnd}
     * 与后继词元的起始点 {@code nextStart}，完整恢复被波点代码抹平的真实演唱时长；
     * 2. 约束单词最大时长，消除因字段读取异常导致的超大异常间隔；
     * 3. 严格保障整句所有词元 {@code absEnd > absStart}，杜绝 0ms 词元流入下游。
     */
    private static void healWordTimings(@NonNull List<SuperLyricWord> words, long lineStartMs) {
        int size = words.size();
        for (int i = 0; i < size; i++) {
            SuperLyricWord cur = words.get(i);
            long start = cur.getStartTime();
            long end = cur.getEndTime();

            if (end <= start) {
                long healedStart = start;
                long healedEnd = end;

                long prevEnd = (i > 0) ? words.get(i - 1).getEndTime() : lineStartMs;
                long nextStart = -1L;
                for (int j = i + 1; j < size; j++) {
                    SuperLyricWord nw = words.get(j);
                    if (nw.getEndTime() > nw.getStartTime()) {
                        nextStart = nw.getStartTime();
                        break;
                    }
                }

                if (prevEnd < end) {
                    // 场景 1（波点经典坍缩）：start 被前向推移至 end (i3)，真实起始正是前一词的结束 prevEnd！
                    healedStart = prevEnd;
                    healedEnd = end;
                } else if (nextStart > start) {
                    // 场景 2：end 缺失或被压平，后继词起始于 nextStart
                    healedStart = start;
                    healedEnd = nextStart;
                } else if (nextStart > prevEnd) {
                    // 场景 3：start 与 end 均被压制，平分 prevEnd 到 nextStart
                    healedStart = prevEnd;
                    healedEnd = nextStart;
                } else {
                    // 场景 4：按字符长度给予自然音节权重保底 (每字 150ms，最小 120ms)
                    int len = Math.max(1, cur.getWord().length());
                    healedStart = start;
                    healedEnd = start + Math.max(120L, 150L * len);
                }

                if (healedEnd <= healedStart) {
                    healedEnd = healedStart + 150L;
                }

                words.set(i, new SuperLyricWord(cur.getWord(), healedStart, healedEnd));
            }
        }
    }

    /**
     * 基于强类型和反射自适应发现行对象的混淆字段（无排位索引硬编码，全局类级缓存）。
     */
    @Nullable
    private LineFieldResolver getOrResolveLineFields(@NonNull Object lineObj) {
        Class<?> clazz = lineObj.getClass();
        LineFieldResolver cached = sLineResolvers.get(clazz);
        if (cached != null && cached.isValid()) {
            return cached;
        }

        Field timestampField = null;
        Field textField = null;
        Field isTransField = null;
        Field subListField = null;

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                f.setAccessible(true);
                Class<?> type = f.getType();
                if (type == Integer.class && timestampField == null) {
                    timestampField = f;
                } else if (type == String.class && textField == null) {
                    textField = f;
                } else if (type == boolean.class && isTransField == null) {
                    isTransField = f;
                } else if (List.class.isAssignableFrom(type) && subListField == null) {
                    subListField = f;
                }
            }
            current = current.getSuperclass();
        }

        if (timestampField != null && textField != null) {
            LineFieldResolver resolver = new LineFieldResolver(timestampField, textField, isTransField, subListField);
            sLineResolvers.put(clazz, resolver);
            AndroidLog.logI(TAG, "Cached LineFieldResolver for " + clazz.getName());
            return resolver;
        }
        return null;
    }

    /**
     * 自适应解析逐字元素 ({@code com.tme.push.y2.e$h}) 的反射字段映射。
     * <p>
     * <b>严格数学拓扑与边界判定（零混淆名称硬编码）：</b>
     * <ul>
     *   <li><b>charEnd:</b> 满足首词大于 0、严格单调递增且末词恰好等于行文本长度的字段；</li>
     *   <li><b>charStart:</b> 首词为 0、且后续词严格等于前一词 charEnd 的字段；</li>
     *   <li><b>wordIndex:</b> 恒为 0, 1, 2... 的索引字段；</li>
     *   <li><b>startMs / endMs:</b> 排除上述字段后严格仅剩的 2 个时间戳毫秒偏移字段，按单调性甄别起止。</li>
     * </ul>
     */
    @Nullable
    private SubWordResolver resolveSubFields(@NonNull List<?> subList, @NonNull String sampleText) {
        if (subList.isEmpty() || sampleText.isEmpty()) return null;
        Object subObj = subList.get(0);
        Class<?> clazz = subObj.getClass();

        List<Field> intFields = new ArrayList<>();
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == int.class) {
                f.setAccessible(true);
                intFields.add(f);
            }
        }

        if (intFields.size() != 5) return null;

        Field candidateCharEnd = null;
        Field candidateCharStart = null;
        Field candidateWordIndex = null;
        Field candidateStartMs = null;
        Field candidateEndMs = null;

        int textLen = sampleText.length();
        int subCount = subList.size();

        // 1. 利用行边界与单调不变量唯一锁定 charEnd
        for (Field f : intFields) {
            try {
                int firstEnd = f.getInt(subList.get(0));
                int lastEnd = f.getInt(subList.get(subCount - 1));
                if (firstEnd <= 0 || lastEnd != textLen) {
                    continue;
                }
                boolean strictlyIncreasing = true;
                int prev = firstEnd;
                for (int k = 1; k < subCount; k++) {
                    int cur = f.getInt(subList.get(k));
                    if (cur <= prev || cur > textLen) {
                        strictlyIncreasing = false;
                        break;
                    }
                    prev = cur;
                }
                if (strictlyIncreasing) {
                    candidateCharEnd = f;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        if (candidateCharEnd == null) {
            return null;
        }

        // 2. 在锁定 charEnd 的基础上甄别 charStart
        for (Field f : intFields) {
            if (f == candidateCharEnd) continue;
            try {
                int firstStart = f.getInt(subList.get(0));
                if (firstStart != 0) continue;

                boolean matchesCharEnd = true;
                for (int k = 1; k < subCount; k++) {
                    int curStart = f.getInt(subList.get(k));
                    int prevEnd = candidateCharEnd.getInt(subList.get(k - 1));
                    if (curStart != prevEnd) {
                        matchesCharEnd = false;
                        break;
                    }
                }
                if (matchesCharEnd) {
                    candidateCharStart = f;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. 甄别 wordIndex：排除 charEnd 与 charStart 后，找出恒为 0, 1, 2... 的索引字段
        for (Field f : intFields) {
            if (f == candidateCharEnd || f == candidateCharStart) continue;
            boolean isWordIdx = true;
            for (int k = 0; k < Math.min(subCount, 10); k++) {
                try {
                    if (f.getInt(subList.get(k)) != k) {
                        isWordIdx = false;
                        break;
                    }
                } catch (Throwable ignored) {
                    isWordIdx = false;
                    break;
                }
            }
            if (isWordIdx) {
                candidateWordIndex = f;
                break;
            }
        }

        // 4. 甄别 startMs 与 endMs：必须严格从排除 charEnd, charStart, wordIndex 后的剩余字段中筛选
        List<Field> timeCandidates = new ArrayList<>();
        for (Field f : intFields) {
            if (f != candidateCharEnd && f != candidateCharStart && f != candidateWordIndex) {
                timeCandidates.add(f);
            }
        }

        if (timeCandidates.size() == 2) {
            try {
                Field t0 = timeCandidates.get(0);
                Field t1 = timeCandidates.get(1);

                int t0LeCount = 0;
                int t1LeCount = 0;
                for (int k = 0; k < subCount; k++) {
                    Object elem = subList.get(k);
                    int v0 = t0.getInt(elem);
                    int v1 = t1.getInt(elem);
                    if (v0 <= v1) t0LeCount++;
                    if (v1 <= v0) t1LeCount++;
                }

                Object lastElem = subList.get(subCount - 1);
                int last0 = t0.getInt(lastElem);
                int last1 = t1.getInt(lastElem);

                if (last0 < last1 || t0LeCount >= t1LeCount) {
                    candidateStartMs = t0;
                    candidateEndMs = t1;
                } else {
                    candidateStartMs = t1;
                    candidateEndMs = t0;
                }
            } catch (Throwable ignored) {
            }
        }

        if (candidateCharEnd != null && candidateStartMs != null && candidateEndMs != null) {
            AndroidLog.logI(TAG, "Resolved sub-word fields successfully: charEnd=" + candidateCharEnd.getName()
                + ", charStart=" + (candidateCharStart != null ? candidateCharStart.getName() : "continuous")
                + ", startMs=" + candidateStartMs.getName()
                + ", endMs=" + candidateEndMs.getName());
            return new SubWordResolver(candidateCharStart, candidateCharEnd, candidateStartMs, candidateEndMs);
        }

        return null;
    }

    /**
     * 从 LyricsRunner4Flutter 查找并获取 Music 实例。
     */
    @Nullable
    private Object extractMusicFromRunner(@NonNull Object runner) {
        Class<?> cur = runner.getClass();
        while (cur != null && cur != Object.class) {
            for (Field f : cur.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object val = f.get(runner);
                    if (val != null && val.getClass().getName().contains("Music")) {
                        return val;
                    }
                } catch (Exception ignored) {
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static String safeString(@Nullable Object obj) {
        return obj == null ? "" : Objects.toString(obj);
    }

    private static class ParsedLine {
        final String text;
        final long startMs;
        final SuperLyricWord[] words;
        String translation;

        ParsedLine(String text, long startMs, SuperLyricWord[] words) {
            this.text = text;
            this.startMs = startMs;
            this.words = words;
        }
    }

    private static class SampleLine {
        final List<?> subList;
        final String text;

        SampleLine(@NonNull List<?> subList, @NonNull String text) {
            this.subList = subList;
            this.text = text;
        }
    }
}
