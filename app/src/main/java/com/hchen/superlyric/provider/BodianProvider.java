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
import com.hchen.superlyric.engine.multisource.MultiSourceLyricEngine;
import com.hchen.superlyric.parser.KuwoLrcxParser;
import com.hchen.superlyric.parser.SodaJsonParser;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 波点音乐统一歌词提供者。
 * <p>
 * <b>架构革新与原生报文自解析 (波点音乐 5.9.8+)：</b>
 * <ul>
 *   <li><b>彻底斩断末端混淆反射：</b>
 *       全面废除在数据消费末端（{@code LyricsRunner4Flutter.g}）拦截并反射解析混淆行/词模型（{@code aVarA}）的旧方案，
 *       杜绝因宿主内部代码混淆、字段重构带来的脆弱性；</li>
 *   <li><b>源头截获原始报文流：</b>
 *       在 {@code LyricsRunner4Flutter} 构造器（传入 {@code Music} 实体与本地下载歌词文件路径）
 *       以及 {@code LyricsStream.f(byte[])} 原始字节流接收点实施轻量拦截，直接捕获未经宿主篡改的原生歌词报文；</li>
 *   <li><b>模块原生自解析与零 0ms 坍缩：</b>
 *       直接由 {@link KuwoLrcxParser}（或 JSON 格式下的 {@link SodaJsonParser}）进行高精数学自解析，
 *       从根本上彻底避开波点宿主内部 {@code VerbatimLyricsParserImpl.e()} 粗暴重叠裁切造成的 0ms 坍缩缺陷；</li>
 *   <li><b>双轨网络引擎兜底：</b>
 *       未命中或纯本地音轨时无缝交由 {@link MultiSourceLyricEngine} 进行多源聚合打分拉取。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "cn.wenyu.bodian")
public class BodianProvider extends UnifiedLyricProvider {
    private static final String TAG = "BodianProvider";

    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();
    private final AtomicReference<Object> mCurrentMusic = new AtomicReference<>();
    private volatile String mLastProcessedFilePath = null;

    @NonNull
    @Override
    public ProviderCapability capability() {
        return ProviderCapability.FULL_HOOK_WITH_NETWORK;
    }

    @Nullable
    @Override
    protected INetworkLyricEngine createNetworkEngine() {
        return new MultiSourceLyricEngine();
    }

    @Nullable
    @Override
    protected IHookLyricEngine createHookEngine() {
        return new IHookLyricEngine() {
            @Override
            public void initHooks() {
                hookLyricsRunnerInit();
                hookLyricsStreamByte();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                // 异步报文事件驱动，拦截到原始数据后即时上报
                return null;
            }
        };
    }

    /**
     * 通道 1：Hook {@code LyricsRunner4Flutter} 构造函数。
     * <p>
     * 构造函数入参为：{@code (Music music, int lyricStatus, String lyricPath)}。
     * 在其构造完成瞬间，保存曲目元数据并直接读取原始文件内容自解析。
     */
    private void hookLyricsRunnerInit() {
        try {
            Class<?> runnerClass = DexkitCache.findMember("bodian_lyrics_runner_class_v1", new IDexkit<ClassData>() {
                @Nullable
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    ClassDataList list = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("LyricsRunner4Flutter")
                        )
                    );
                    if (list != null && !list.isEmpty()) {
                        for (ClassData cd : list) {
                            if (!cd.getName().contains("$")) {
                                return cd;
                            }
                        }
                        return list.get(0);
                    }
                    return null;
                }
            });

            if (runnerClass == null) {
                AndroidLog.logW(TAG, "DexKit failed to locate LyricsRunner4Flutter class");
                return;
            }

            for (Constructor<?> c : runnerClass.getDeclaredConstructors()) {
                if (c.getParameterCount() == 3) {
                    c.setAccessible(true);
                    hook(c, new AbsHook() {
                        @Override
                        public void after() {
                            try {
                                Object music = getArg(0);
                                String filePath = safeString(getArg(2));
                                if (music != null) {
                                    mCurrentMusic.set(music);
                                }
                                if (!filePath.isEmpty()) {
                                    processRawLyricFile(music, filePath, "RunnerInit");
                                }
                            } catch (Throwable t) {
                                AndroidLog.logW(TAG, "Error in LyricsRunner4Flutter constructor hook: " + t.getMessage());
                            }
                        }
                    });
                    AndroidLog.logI(TAG, "Hooked LyricsRunner4Flutter constructor successfully");
                    break;
                }
            }
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to hook LyricsRunner4Flutter constructor", t);
        }
    }

    /**
     * 通道 2：Hook {@code LyricsStream.f(byte[])} 原生报文字节流入口。
     * <p>
     * 宿主读取完歌词本地文件或网络流后，统一通过 {@code f(byte[] bArr)} 进行文本转换。
     * 直接在此截取原始字节数组，规避一切文件未写毕与延迟读取竞争。
     */
    private void hookLyricsStreamByte() {
        try {
            Method streamMethod = DexkitCache.findMember("bodian_lyrics_stream_f_v1", new IDexkit<MethodData>() {
                @Nullable
                @Override
                public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    ClassDataList list = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("LyricsStream")
                        )
                    );
                    if (list == null || list.isEmpty()) return null;

                    for (ClassData cd : list) {
                        MethodDataList methods = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                .declaredClass(cd.getName())
                                .paramTypes(byte[].class)
                            )
                        );
                        if (methods != null && !methods.isEmpty()) {
                            return methods.get(0);
                        }
                    }
                    return null;
                }
            });

            if (streamMethod == null) {
                AndroidLog.logW(TAG, "DexKit failed to locate LyricsStream.f(byte[]) method");
                return;
            }

            hook(streamMethod, new AbsHook() {
                @Override
                public void before() {
                    try {
                        byte[] bArr = (byte[]) getArg(0);
                        if (bArr != null && bArr.length > 0) {
                            String rawText = new String(bArr, StandardCharsets.UTF_8);
                            processRawLyricText(mCurrentMusic.get(), rawText, "LyricsStream.f");
                        }
                    } catch (Throwable t) {
                        AndroidLog.logW(TAG, "Error in LyricsStream.f hook: " + t.getMessage());
                    }
                }
            });

            AndroidLog.logI(TAG, "Hooked LyricsStream.f method successfully: " + streamMethod.getName());
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to hook LyricsStream.f method", t);
        }
    }

    /**
     * 读取并自解析本地歌词原始文件。
     */
    private void processRawLyricFile(@Nullable Object music, @NonNull String filePath, @NonNull String source) {
        if (filePath.isEmpty() || Objects.equals(mLastProcessedFilePath, filePath)) {
            return;
        }

        try {
            File f = new File(filePath);
            if (!f.exists() || !f.isFile() || f.length() == 0) {
                return;
            }
            mLastProcessedFilePath = filePath;
            byte[] bytes = Files.readAllBytes(f.toPath());
            String rawText = new String(bytes, StandardCharsets.UTF_8);
            processRawLyricText(music, rawText, source + "->File");
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to process raw lyric file (" + filePath + "): " + t.getMessage());
        }
    }

    /**
     * 核心：接收原生歌词报文，由 SuperLyric 原生解析器自解析与调度上报。
     */
    private void processRawLyricText(@Nullable Object music, @NonNull String rawText, @NonNull String source) {
        if (rawText.trim().isEmpty()) {
            return;
        }

        try {
            // 1. 提取当前曲目元数据
            String title = "";
            String artist = "";
            String album = "";
            String rid = "";
            long duration = 0L;

            if (music != null) {
                title = safeString(callMethod(music, "getName"));
                artist = safeString(callMethod(music, "getArtist"));
                album = safeString(callMethod(music, "getAlbum"));
                rid = safeString(getField(music, "rid"));

                Object durObj = callMethod(music, "getDuration");
                if (durObj instanceof Number) {
                    duration = ((Number) durObj).longValue();
                }
            }

            TrackContext active = mActiveTrack.get();
            if (active != null) {
                if (title.isEmpty() && active.getTitle() != null) title = active.getTitle();
                if (artist.isEmpty() && active.getArtist() != null) artist = active.getArtist();
                if (album.isEmpty() && active.getAlbum() != null) album = active.getAlbum();
                if (duration <= 0 && active.getDuration() > 0) duration = active.getDuration();
            }

            // 2. 根据报文特征自适应原生解析
            SuperLyricData parsedData;
            String trimmed = rawText.trim();
            if (trimmed.startsWith("{") || trimmed.contains("\"sentences\"")) {
                // 汽水/Luna 原生 JSON 报文
                var lines = SodaJsonParser.parseJson(trimmed);
                if (lines != null && lines.length > 0) {
                    parsedData = new SuperLyricData();
                    parsedData.setTitle(title);
                    parsedData.setArtist(artist);
                    parsedData.setAlbum(album);
                    parsedData.setDuration(duration);
                    parsedData.setAllLyrics(lines);
                } else {
                    parsedData = null;
                }
            } else {
                // 酷我/波点 原生 LRCX / LRC 报文
                parsedData = KuwoLrcxParser.parseLrcx(trimmed, title, artist, album, duration);
            }

            if (parsedData == null || !parsedData.hasAllLyrics()) {
                AndroidLog.logW(TAG, "[" + source + "] Failed to parse raw lyric text, lines=0");
                if (active != null && mOrchestrator != null) {
                    mOrchestrator.onHookDeterminedInvalid(active);
                }
                return;
            }

            SuperLyricData cleanData = LyricSanitizer.sanitizeData(parsedData);
            if (cleanData == null) {
                return;
            }

            String trackId = !rid.isEmpty() ? rid : (active != null ? active.getTrackId() : (title + "_" + artist));
            cleanData.setLyricId(trackId);

            long gen;
            if (active != null && Objects.equals(active.getTrackId(), trackId)) {
                gen = active.getGeneration();
            } else {
                gen = mTrackGeneration.incrementAndGet();
            }

            TrackContext context = new TrackContext(gen, trackId, title, artist, album, duration);
            mActiveTrack.set(context);

            if (mOrchestrator != null) {
                mOrchestrator.onTrackChanged(context);
                mOrchestrator.onHookFullLyricCaptured(context, cleanData);
            }

            AndroidLog.logI(TAG, "[" + source + "] Successfully parsed raw lyric stream for: " + title + " - " + artist
                + " (trackId=" + trackId + ", lines=" + cleanData.getAllLyrics().length + ")");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "[" + source + "] Error processing raw lyric text", t);
        }
    }

    @NonNull
    private static String safeString(@Nullable Object obj) {
        return obj instanceof String ? ((String) obj).trim() : "";
    }
}
