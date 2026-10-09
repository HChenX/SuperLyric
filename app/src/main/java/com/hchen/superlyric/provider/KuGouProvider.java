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

import android.content.Context;
import android.media.MediaMetadata;
import android.text.TextUtils;

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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 酷狗音乐统一歌词提供者。
 * <p>
 * <b>逆向适配与架构设计说明 (酷狗音乐 20.8.2+)：</b>
 * <ul>
 *   <li><b>全局双进程 IPC 中枢数据拦截</b>：
 *       酷狗内部播放时均会在其核心双进程 IPC 总线
 *       （特征常量 {@code @twin:GlobalVariate}）中维护当前歌曲完整的反序列化模型 {@code LyricData}（Key=41）
 *       与歌曲 Hash（Key=207）。通过 Hook 其统一 Setter 方法并配合被动查询，实现首帧零延迟截获全量逐字歌词。</li>
 *   <li><b>系统级推演时基驱动</b>：
 *       完全由框架层 {@code SystemPlayStateListener} 监听全局 {@code MediaSession} 驱动播放/暂停/Seek 与推演，
 *       解耦应用内脆弱的私有时钟与状态栏 View 派发，彻底消除行切换时的数据闪烁与多余 IPC 开销。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.kugou.android")
public class KuGouProvider extends UnifiedLyricProvider {
    private static final String TAG = "KuGouProvider";

    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();
    private volatile String mLastProcessedHash = null;

    // 全局值访问方法缓存 (Getter / Setter)
    private volatile Method mGetLyricDataMethod;
    private volatile Method mGetHashMethod;
    private volatile Method mSetLyricDataMethod;

    // LyricData 反射解析方法缓存
    private volatile Method mGetRowBeginTimeMethod;
    private volatile Method mGetRowDelayTimeMethod;
    private volatile Method mGetWordsMethod;
    private volatile Method mGetWordBeginTimeMethod;
    private volatile Method mGetWordDelayTimeMethod;
    private volatile Method mGetTranslateWordsMethod;
    private volatile Method mGetHeadersMethod;
    private volatile boolean mLyricDataMethodsResolved = false;

    public KuGouProvider() {
        super();
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
                findGlobalValueAccessor();
                hookLyricDataSetter();
                fixProbabilityCollapse();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                mActiveTrack.set(context);
                return extractFullLyricDataInternal();
            }
        };
    }

    /**
     * 定位全局 IPC 跨进程访问中枢，获取 LyricData (Key=41) 读取/写入方法与 SongHash (Key=207) 读取方法。
     * <p>
     * 使用两段式 DexKit 检索：先通过不变常量 {@code @twin:GlobalVariate} 锁定目标类，
     * 再依据静态方法签名精准绑定 Getter 与 Setter，全程零混淆字段硬编码。
     */
    private void findGlobalValueAccessor() {
        try {
            Class<?> gvClass = DexkitCache.findMember("kugou_global_value_class_v1", new IDexkit<ClassData>() {
                @Nullable
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    ClassDataList classes = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("@twin:GlobalVariate")
                        )
                    );
                    return (classes != null && !classes.isEmpty()) ? classes.get(0) : null;
                }
            });

            if (gvClass == null) {
                AndroidLog.logW(TAG, "DexKit failed to locate @twin:GlobalVariate class");
                return;
            }

            for (Method m : gvClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == int.class
                        && android.os.Parcelable.class.isAssignableFrom(m.getReturnType())) {
                        mGetLyricDataMethod = m;
                        mGetLyricDataMethod.setAccessible(true);
                    } else if (params.length == 2 && params[0] == int.class
                        && params[1] == String.class && m.getReturnType() == String.class) {
                        mGetHashMethod = m;
                        mGetHashMethod.setAccessible(true);
                    } else if (params.length == 2 && params[0] == int.class
                        && android.os.Parcelable.class.isAssignableFrom(params[1])
                        && m.getReturnType() == void.class) {
                        mSetLyricDataMethod = m;
                        mSetLyricDataMethod.setAccessible(true);
                    }
                }
            }

            AndroidLog.logI(TAG, "Found Kugou GlobalValue accessors: getLyricData=" + mGetLyricDataMethod
                + ", getHash=" + mGetHashMethod
                + ", setLyricData=" + mSetLyricDataMethod);
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to find Kugou GlobalValue accessor", t);
        }
    }

    /**
     * 拦截宿主向全局 IPC 总线写入 {@code LyricData} (Key=41) 的统一 Setter 方法。
     * <p>
     * 当酷狗加载或异步更新歌词完毕时，会主动调用该 Setter 存入全局缓存。
     * 我们在此被动感知更新事件，提取解析好的全量歌词模型并交付调度器。
     */
    private void hookLyricDataSetter() {
        if (mSetLyricDataMethod == null) return;

        try {
            hook(mSetLyricDataMethod, new AbsHook() {
                @Override
                public void after() {
                    try {
                        int key = (int) getArg(0);
                        if (key == 41) {
                            Object lyricObj = getArg(1);
                            if (lyricObj != null) {
                                onLyricDataUpdated(lyricObj);
                            }
                        }
                    } catch (Throwable t) {
                        AndroidLog.logW(TAG, "Error in hookLyricDataSetter: " + t.getMessage());
                    }
                }
            });
            AndroidLog.logI(TAG, "Hooked Kugou LyricData setter method successfully");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to hook Kugou LyricData setter", t);
        }
    }

    /**
     * 宿主歌词数据写入更新时的处理逻辑。
     *
     * @param lyricDataObj 酷狗反序列化的 {@code com.kugou.framework.lyric.LyricData} 对象
     */
    private void onLyricDataUpdated(@NonNull Object lyricDataObj) {
        String songHash = getSongHash();
        if (!TextUtils.isEmpty(songHash) && TextUtils.equals(mLastProcessedHash, songHash)) {
            return;
        }

        SuperLyricData fullData = convertLyricData(lyricDataObj, songHash);
        if (fullData != null && fullData.hasAllLyrics()) {
            mLastProcessedHash = songHash;
            TrackContext context = mActiveTrack.get();
            if (context == null || !TextUtils.equals(context.getTrackId(), fullData.getLyricId())) {
                long gen = mTrackGeneration.incrementAndGet();
                context = new TrackContext(gen, fullData.getLyricId(), fullData.getTitle(), fullData.getArtist(), "", fullData.getDuration());
                mActiveTrack.set(context);
            }
            if (mOrchestrator != null) {
                mOrchestrator.onTrackChanged(context);
                mOrchestrator.onHookFullLyricCaptured(context, fullData);
            }
        } else {
            TrackContext active = mActiveTrack.get();
            if (active != null && mOrchestrator != null) {
                mOrchestrator.onHookDeterminedInvalid(active);
            }
        }
    }

    /**
     * 提取音轨唯一标识，优先直接提取宿主全局双进程 IPC 中枢的不可变音频 Hash（Key=207）。
     * <p>
     * 酷狗音乐在开启蓝牙/车载歌词时，会高频将单句歌词覆写到 {@code MediaMetadata.METADATA_KEY_TITLE} 中。
     * 若依据标题生成 Hash，会导致单曲播放过程中音轨 ID 随每句歌词频繁突变；
     * 直接使用底层不可变的音频 Hash 作为音轨唯一标识，可彻底免疫蓝牙歌词污染。
     *
     * @param metadata 系统媒体会话派发的元数据对象
     * @return 酷狗全局唯一音频 Hash，不可用时回退父类策略
     */
    @Nullable
    @Override
    protected String extractTrackId(@NonNull MediaMetadata metadata) {
        String songHash = getSongHash();
        if (!TextUtils.isEmpty(songHash)) {
            return songHash;
        }
        return super.extractTrackId(metadata);
    }

    /**
     * 内部主动被动抽取整首 {@code LyricData} 模型并转换为标准 {@link SuperLyricData}。
     *
     * @return 转换后的全量逐字歌词数据，若获取失败则返回 null
     */
    @Nullable
    private SuperLyricData extractFullLyricDataInternal() {
        if (mGetLyricDataMethod == null) return null;

        try {
            Object lyricDataObj = mGetLyricDataMethod.invoke(null, 41);
            if (lyricDataObj == null) return null;

            String songHash = getSongHash();
            SuperLyricData converted = convertLyricData(lyricDataObj, songHash);
            if (converted != null) {
                mLastProcessedHash = songHash;
                return converted;
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to extract LyricData: " + t.getMessage());
        }
        return null;
    }

    /**
     * 读取当前播放曲目的全局 Hash。
     *
     * @return 当前歌曲 Hash 字符串，若不可用返回空串
     */
    @NonNull
    private String getSongHash() {
        if (mGetHashMethod != null) {
            try {
                Object hashObj = mGetHashMethod.invoke(null, 207, "");
                if (hashObj instanceof String) {
                    return (String) hashObj;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /**
     * 动态反射解析 {@code LyricData} 对象，完全免除对混淆字段的依赖。
     * <p>
     * 解析流程包括：
     * <ul>
     *   <li>获取行起始与延迟时间数组；</li>
     *   <li>提取逐字文本矩阵与各字毫秒跨度；</li>
     *   <li>提取翻译文本矩阵；</li>
     *   <li>组装为标准 {@link SuperLyricLine} 数组与全量 {@link SuperLyricData}。</li>
     * </ul>
     *
     * @param lyricDataObj 宿主原生 {@code LyricData} 对象
     * @param songHash     歌曲唯一 Hash 标识
     * @return 转换后的标准全量歌词包
     */
    @Nullable
    private SuperLyricData convertLyricData(@NonNull Object lyricDataObj, @NonNull String songHash) {
        try {
            if (!mLyricDataMethodsResolved) {
                resolveLyricDataMethods(lyricDataObj.getClass());
            }

            if (mGetRowBeginTimeMethod == null || mGetWordsMethod == null) {
                return null;
            }

            long[] rowBeginTime = (long[]) mGetRowBeginTimeMethod.invoke(lyricDataObj);
            if (rowBeginTime == null || rowBeginTime.length == 0) {
                return null;
            }

            long[] rowDelayTime = mGetRowDelayTimeMethod != null ? (long[]) mGetRowDelayTimeMethod.invoke(lyricDataObj) : null;
            String[][] words = (String[][]) mGetWordsMethod.invoke(lyricDataObj);
            long[][] wordBeginTime = mGetWordBeginTimeMethod != null ? (long[][]) mGetWordBeginTimeMethod.invoke(lyricDataObj) : null;
            long[][] wordDelayTime = mGetWordDelayTimeMethod != null ? (long[][]) mGetWordDelayTimeMethod.invoke(lyricDataObj) : null;
            String[][] translateWords = mGetTranslateWordsMethod != null ? (String[][]) mGetTranslateWordsMethod.invoke(lyricDataObj) : null;
            Map<?, ?> headers = mGetHeadersMethod != null ? (Map<?, ?>) mGetHeadersMethod.invoke(lyricDataObj) : null;

            SuperLyricLine[] lines = new SuperLyricLine[rowBeginTime.length];
            for (int r = 0; r < rowBeginTime.length; r++) {
                long lineStart = rowBeginTime[r];
                long lineDelay = (rowDelayTime != null && r < rowDelayTime.length) ? rowDelayTime[r] : 0;
                long lineEnd = (r + 1 < rowBeginTime.length) ? rowBeginTime[r + 1] : (lineStart + Math.max(lineDelay, 3000));

                // 组装整行文本与逐字模型
                StringBuilder sb = new StringBuilder();
                List<SuperLyricWord> wordList = new ArrayList<>();

                if (words != null && r < words.length && words[r] != null) {
                    String[] rowWords = words[r];
                    long[] bTimes = (wordBeginTime != null && r < wordBeginTime.length) ? wordBeginTime[r] : null;
                    long[] dTimes = (wordDelayTime != null && r < wordDelayTime.length) ? wordDelayTime[r] : null;

                    for (int w = 0; w < rowWords.length; w++) {
                        String wText = rowWords[w];
                        if (wText == null) continue;
                        sb.append(wText);

                        if (bTimes != null && w < bTimes.length) {
                            long b = bTimes[w];
                            long d = (dTimes != null && w < dTimes.length) ? dTimes[w] : 0;
                            // 兼容绝对毫秒与行内相对偏移两种时序约定
                            long wStart = (b >= lineStart) ? b : (lineStart + b);
                            long wEnd;
                            if (d > 0) {
                                wEnd = wStart + d;
                            } else {
                                // 启发式自愈：d <= 0 时优先采纳下一词起始，否则按字符权重保底
                                long nextStart = -1L;
                                if (w + 1 < bTimes.length) {
                                    long nb = bTimes[w + 1];
                                    nextStart = (nb >= lineStart) ? nb : (lineStart + nb);
                                }
                                if (nextStart > wStart) {
                                    wEnd = nextStart;
                                } else {
                                    wEnd = wStart + Math.max(120L, (long) wText.length() * 150L);
                                    if (lineEnd > wStart && wEnd > lineEnd) {
                                        wEnd = lineEnd;
                                    }
                                }
                            }
                            if (wEnd <= wStart) {
                                wEnd = wStart + Math.max(120L, (long) wText.length() * 150L);
                            }
                            wordList.add(new SuperLyricWord(wText, wStart, wEnd));
                        }
                    }

                    if (!wordList.isEmpty()) {
                        LyricSanitizer.healWordTimings(wordList, lineStart, lineEnd);
                    }
                }

                String rowText = sb.toString();
                SuperLyricWord[] wordsArr = wordList.isEmpty() ? null : wordList.toArray(new SuperLyricWord[0]);

                // 提取翻译文本
                String transText = null;
                if (translateWords != null && r < translateWords.length && translateWords[r] != null) {
                    StringBuilder tsb = new StringBuilder();
                    for (String tw : translateWords[r]) {
                        if (tw != null) tsb.append(tw);
                    }
                    transText = tsb.length() > 0 ? tsb.toString() : null;
                }

                lines[r] = new SuperLyricLine(rowText, wordsArr, transText, lineStart, lineEnd);
            }

            SuperLyricData fullData = new SuperLyricData();
            fullData.setAllLyrics(lines);
            fullData.setLyricId(songHash);

            // 解析元数据：优先采纳原生歌词 Header 中的真实曲目与歌手，不受 MediaSession 蓝牙歌词覆写污染
            String lyricTitle = null;
            String lyricArtist = null;
            if (headers != null) {
                Object ti = headers.get("ti");
                if (ti instanceof String && !TextUtils.isEmpty((String) ti)) {
                    lyricTitle = (String) ti;
                }
                Object ar = headers.get("ar");
                if (ar instanceof String && !TextUtils.isEmpty((String) ar)) {
                    lyricArtist = (String) ar;
                }
            }

            TrackContext active = mActiveTrack.get();
            if (!TextUtils.isEmpty(lyricTitle)) {
                fullData.setTitle(lyricTitle);
            } else if (active != null && !TextUtils.isEmpty(active.getTitle())) {
                fullData.setTitle(active.getTitle());
            }

            if (!TextUtils.isEmpty(lyricArtist)) {
                fullData.setArtist(lyricArtist);
            } else if (active != null && !TextUtils.isEmpty(active.getArtist())) {
                fullData.setArtist(active.getArtist());
            }

            if (active != null && active.getDuration() > 0) {
                fullData.setDuration(active.getDuration());
            }

            return fullData;
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "convertLyricData failed", t);
        }
        return null;
    }

    /**
     * 反射解析 {@code LyricData} 公开 Getter 方法，按公开签名绑定，杜绝混淆影响。
     *
     * @param clazz 宿主内部 {@code LyricData} 的 Class 对象
     */
    private void resolveLyricDataMethods(@NonNull Class<?> clazz) {
        try {
            mGetRowBeginTimeMethod = clazz.getMethod("getRowBeginTime");
            mGetRowDelayTimeMethod = clazz.getMethod("getRowDelayTime");
            mGetWordsMethod = clazz.getMethod("getWords");
            mGetWordBeginTimeMethod = clazz.getMethod("getWordBeginTime");
            mGetWordDelayTimeMethod = clazz.getMethod("getWordDelayTime");
            try {
                mGetTranslateWordsMethod = clazz.getMethod("getTranslateWords");
            } catch (Throwable ignored) {
            }
            try {
                mGetHeadersMethod = clazz.getMethod("getHeaders");
            } catch (Throwable ignored) {
            }
            mLyricDataMethodsResolved = true;
            AndroidLog.logI(TAG, "Resolved all LyricData getters successfully");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to resolve LyricData getter methods", t);
        }
    }

    /**
     * 修复酷狗在部分定制系统上因 WiFi ServiceFetcher 空指针引发的偶发崩溃。
     */
    private void fixProbabilityCollapse() {
        try {
            hookMethod("com.kugou.framework.hack.ServiceFetcherHacker$FetcherImpl",
                "createServiceObject",
                Context.class, Context.class,
                new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            String serviceName = (String) getField(getThisObject(), "serviceName");
                            if (Context.WIFI_SERVICE.equals(serviceName) && getThrowable() != null) {
                                setThrowable(null);
                                setResult(null);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );
        } catch (Throwable ignored) {
        }
    }
}
