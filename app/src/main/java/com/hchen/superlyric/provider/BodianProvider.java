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

    // 自适应行反射字段缓存
    private volatile Field mLineTimestampField;
    private volatile Field mLineTextField;
    private volatile Field mLineIsTransField;
    private volatile Field mLineSubListField;
    private volatile boolean mLineFieldsResolved = false;

    // 自适应逐字子元素反射字段缓存 (5个int字段的语义映射)
    private volatile Field mSubCharStartField;
    private volatile Field mSubCharEndField;
    private volatile Field mSubStartTimeField;
    private volatile Field mSubEndTimeField;
    private volatile boolean mSubFieldsResolved = false;

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

        // 首次动态发现行对象的混淆字段
        if (!mLineFieldsResolved) {
            resolveLineFields(rawLines.get(0));
        }

        if (mLineTimestampField == null || mLineTextField == null) {
            AndroidLog.logE(TAG, "Failed to resolve required lyric line fields");
            return null;
        }

        // 尝试从当前歌曲全局样本行动态解析逐字字段映射（优先采用词数 < 字符长的行以严格区分 charStart 与 wordIndex）
        if (!mSubFieldsResolved && mLineSubListField != null) {
            resolveSubFieldsFromSong(rawLines);
        }

        List<ParsedLine> parsedList = new ArrayList<>();
        ParsedLine pendingOriginal = null;

        for (Object raw : rawLines) {
            try {
                Integer timestamp = (Integer) mLineTimestampField.get(raw);
                String text = (String) mLineTextField.get(raw);
                boolean isTranslation = false;
                if (mLineIsTransField != null) {
                    Object transObj = mLineIsTransField.get(raw);
                    if (transObj instanceof Boolean) {
                        isTranslation = (Boolean) transObj;
                    }
                }

                if (text == null || timestamp == null) continue;

                // 提取逐字子元素 (若为逐字歌词)
                SuperLyricWord[] words = null;
                if (mLineSubListField != null) {
                    Object subObj = mLineSubListField.get(raw);
                    if (subObj instanceof List<?> subList && !subList.isEmpty()) {
                        words = extractWords(subList, text, timestamp);
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
     * 遍历全曲歌词行提取样本，优先寻找词素数量小于字符总长度的行（如包含标点、空格、多字词），
     * 避免因全单字行中 wordIndex (0,1,2...) 与 charStart (0,1,2...) 数值完全一致而产生歧义误判。
     */
    private void resolveSubFieldsFromSong(@NonNull List<?> rawLines) {
        SampleLine preferredSample = null;
        SampleLine fallbackSample = null;

        for (Object raw : rawLines) {
            try {
                String text = (String) mLineTextField.get(raw);
                if (text == null || text.trim().isEmpty()) continue;

                Object subObj = mLineSubListField.get(raw);
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

        if (preferredSample != null) {
            resolveSubFields(preferredSample.subList, preferredSample.text);
        } else if (fallbackSample != null) {
            resolveSubFields(fallbackSample.subList, fallbackSample.text);
        }
    }

    /**
     * 提取逐字数据并映射为 {@link SuperLyricWord} 数组。
     * <p>
     * <b>严格防御与连续性闭包校验：</b>
     * <ul>
     *   <li>每个词元必须严格从前一词元的结束点无缝连续切分 ({@code cStart == prevEnd})；</li>
     *   <li>索引范围必须严格满足 {@code 0 <= cStart < cEnd <= lineText.length()}；</li>
     *   <li>整行拼接的字符必须 100% 严丝合缝还原 {@code lineText}。</li>
     * </ul>
     * <p>
     * 一旦检测到任何越界、反向重叠、不连续或失配，立即将逐字降级为 {@code null}，
     * 并清空字段映射缓存，绝不向系统界面与下游模块分发非法切片。
     *
     * @param subList 宿主逐字词元列表
     * @param lineText 当前行的完整文本
     * @param lineStartMs 当前行的起始绝对时间戳 (毫秒)
     * @return 校验通过的 {@link SuperLyricWord} 数组；存在异常或非逐字行时返回 {@code null}
     */
    @Nullable
    private SuperLyricWord[] extractWords(@NonNull List<?> subList, @NonNull String lineText, long lineStartMs) {
        if (subList.isEmpty()) return null;

        if (!mSubFieldsResolved) {
            resolveSubFields(subList, lineText);
        }

        if (mSubCharEndField == null || mSubStartTimeField == null || mSubEndTimeField == null) {
            return null;
        }

        List<SuperLyricWord> words = new ArrayList<>(subList.size());
        int prevEnd = 0;
        StringBuilder reconstructed = new StringBuilder();

        for (Object eh : subList) {
            try {
                int startOffsetMs = mSubStartTimeField.getInt(eh);
                int endOffsetMs = mSubEndTimeField.getInt(eh);

                int cEnd = mSubCharEndField.getInt(eh);
                int cStart = mSubCharStartField != null ? mSubCharStartField.getInt(eh) : prevEnd;

                // 严密校验区间合法性与连续性：不可越界、不可倒错、相邻词必须严密无缝承接
                if (cStart != prevEnd || cStart >= cEnd || cEnd > lineText.length()) {
                    AndroidLog.logW(TAG, "extractWords: invalid or non-contiguous char range [" + cStart + ".." + cEnd
                        + "], expectedStart=" + prevEnd + ", lineLen=" + lineText.length() + ", text=\"" + lineText + "\"");
                    mSubFieldsResolved = false;
                    return null;
                }

                String wordText = lineText.substring(cStart, cEnd);
                prevEnd = cEnd;
                reconstructed.append(wordText);

                long absStart;
                long absEnd;
                if (startOffsetMs >= lineStartMs) {
                    absStart = startOffsetMs;
                    absEnd = endOffsetMs;
                } else {
                    absStart = lineStartMs + startOffsetMs;
                    absEnd = lineStartMs + endOffsetMs;
                }
                if (absEnd < absStart) {
                    absEnd = absStart;
                }

                words.add(new SuperLyricWord(wordText, absStart, absEnd));
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Error extracting sub word: " + t.getMessage());
                mSubFieldsResolved = false;
                return null;
            }
        }

        // 字符切片必须严丝合缝 100% 还原整行文本
        if (prevEnd != lineText.length() || !reconstructed.toString().equals(lineText)) {
            AndroidLog.logW(TAG, "extractWords: reconstructed words mismatch with line text: '"
                + reconstructed + "' vs '" + lineText + "', dropping words");
            mSubFieldsResolved = false;
            return null;
        }

        return words.isEmpty() ? null : words.toArray(new SuperLyricWord[0]);
    }

    /**
     * 基于强类型和反射自适应发现行对象的混淆字段（无排位索引硬编码）。
     * <p>
     * 通过字段的 Java 类型特征（{@link Integer}, {@link String}, {@code boolean}, {@link List}）
     * 实现 100% 确定性类型收敛，彻底免疫类成员重命名混淆。
     *
     * @param lineObj 宿主行数据实例
     */
    private void resolveLineFields(@NonNull Object lineObj) {
        Class<?> current = lineObj.getClass();
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                f.setAccessible(true);
                Class<?> type = f.getType();
                if (type == Integer.class && mLineTimestampField == null) {
                    mLineTimestampField = f;
                } else if (type == String.class && mLineTextField == null) {
                    mLineTextField = f;
                } else if (type == boolean.class && mLineIsTransField == null) {
                    mLineIsTransField = f;
                } else if (List.class.isAssignableFrom(type) && mLineSubListField == null) {
                    mLineSubListField = f;
                }
            }
            current = current.getSuperclass();
        }
        if (mLineTimestampField != null && mLineTextField != null) {
            mLineFieldsResolved = true;
        }
    }

    /**
     * 自适应解析逐字元素 ({@code com.tme.push.y2.e$h}) 的反射字段映射。
     * <p>
     * <b>严格数学拓扑与边界判定（零混淆名称硬编码）：</b>
     * <ul>
     *   <li><b>charEnd:</b> 在多词样本行中，唯一满足首词大于 0、严格单调递增且末词恰好等于行文本长度的字段；</li>
     *   <li><b>charStart:</b> 在多字词样本行中，唯一满足首词为 0、且后续词严格等于前一词 charEnd 的字段
     *       （与恒以 1 步进的 wordIndex 产生代数分流，彻底杜绝歧义）；</li>
     *   <li><b>startMs / endMs:</b> 剩余字段中相对行起始时间的毫秒偏移，满足内聚单调性。</li>
     * </ul>
     *
     * @param subList 逐字词元样本列表
     * @param sampleText 对应的样本行完整文本
     */
    private void resolveSubFields(@NonNull List<?> subList, @NonNull String sampleText) {
        if (subList.isEmpty() || sampleText.isEmpty()) return;
        Object subObj = subList.get(0);
        Class<?> clazz = subObj.getClass();

        List<Field> intFields = new ArrayList<>();
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == int.class) {
                f.setAccessible(true);
                intFields.add(f);
            }
        }

        if (intFields.size() != 5) return;

        Field candidateCharEnd = null;
        Field candidateCharStart = null;
        Field candidateStartMs = null;
        Field candidateEndMs = null;

        int textLen = sampleText.length();
        int subCount = subList.size();

        // 1. 利用行边界与单调不变量唯一锁定 charEnd
        // 边界约束：w[0].end >= 1，w[k].end > w[k-1].end，且末词 w[M-1].end 严格等于文本总长度
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
            return;
        }

        // 2. 在锁定 charEnd 的基础上甄别 charStart
        // 相邻连续性约束：w[0].start == 0，w[k].start == w[k-1].end
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

        // 3. 甄别剩余 3 个字段：排除已确定的 charStart/charEnd 后，找出 wordIndex (k=0..M-1)，剩余 2 个为毫秒偏移
        List<Field> remaining = new ArrayList<>();
        for (Field f : intFields) {
            if (f != candidateCharEnd && f != candidateCharStart) {
                remaining.add(f);
            }
        }

        Field candidateWordIndex = null;
        for (Field f : remaining) {
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

        List<Field> timeCandidates = new ArrayList<>();
        for (Field f : remaining) {
            if (f != candidateWordIndex) {
                timeCandidates.add(f);
            }
        }

        if (timeCandidates.size() >= 2) {
            try {
                Field t0 = timeCandidates.get(0);
                Field t1 = timeCandidates.get(1);
                int v0 = t0.getInt(subObj);
                int v1 = t1.getInt(subObj);
                int lastV0 = t0.getInt(subList.get(subCount - 1));
                int lastV1 = t1.getInt(subList.get(subCount - 1));
                // 起始毫秒恒小于等于结束毫秒，且末词时间通常较大约束
                if (v0 <= v1 && lastV0 <= lastV1) {
                    candidateStartMs = t0;
                    candidateEndMs = t1;
                } else {
                    candidateStartMs = t1;
                    candidateEndMs = t0;
                }
            } catch (Throwable ignored) {
            }
        }

        // 4. 严苛的闭包校验：切片必须 100% 严丝合缝还原样本行文本
        if (candidateCharEnd != null && candidateStartMs != null && candidateEndMs != null) {
            if (verifyCharPartition(candidateCharStart, candidateCharEnd, subList, sampleText)) {
                mSubCharStartField = candidateCharStart;
                mSubCharEndField = candidateCharEnd;
                mSubStartTimeField = candidateStartMs;
                mSubEndTimeField = candidateEndMs;
                mSubFieldsResolved = true;
                AndroidLog.logI(TAG, "Resolved sub-word fields successfully via invariant topology: charEnd="
                    + candidateCharEnd.getName()
                    + ", charStart=" + (candidateCharStart != null ? candidateCharStart.getName() : "contiguous")
                    + ", startMs=" + candidateStartMs.getName()
                    + ", endMs=" + candidateEndMs.getName());
            }
        }
    }

    /**
     * 验证候选字段切片是否对样本行文本构成严谨连续且无重叠的完美切分。
     *
     * @param fStart 候选起始字段（可为 null，为 null 时以 prevEnd 推进）
     * @param fEnd 候选结束字段
     * @param subList 样本逐字元素列表
     * @param sampleText 样本行文本
     * @return 满足连续划分且完全还原文本时返回 true，否则返回 false
     */
    private static boolean verifyCharPartition(@Nullable Field fStart, @NonNull Field fEnd,
                                               @NonNull List<?> subList, @NonNull String sampleText) {
        if (subList.isEmpty() || sampleText.isEmpty()) return false;
        try {
            int prevEnd = 0;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < subList.size(); k++) {
                Object eh = subList.get(k);
                int cEnd = fEnd.getInt(eh);
                int cStart = fStart != null ? fStart.getInt(eh) : prevEnd;

                // 起始与结束索引有效性
                if (cStart != prevEnd || cStart >= cEnd || cEnd > sampleText.length()) {
                    return false;
                }
                sb.append(sampleText.substring(cStart, cEnd));
                prevEnd = cEnd;
            }

            return prevEnd == sampleText.length() && sb.toString().equals(sampleText);
        } catch (Throwable ignored) {
            return false;
        }
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
