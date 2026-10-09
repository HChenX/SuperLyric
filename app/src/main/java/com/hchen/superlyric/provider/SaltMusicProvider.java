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

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 椒盐音乐 (Salt Player) 统一歌词提供者。
 * <p>
 * <b>逆向适配与架构设计说明 (Salt Player 12.4.1+)：</b>
 * <ul>
 *   <li><b>内核控制器拦截与被动缓存双驱：</b>
 *       废弃旧版易脆的混淆 Lambda/Runnable Hook，直接拦截核心中枢 {@code com.salt.music.service.MusicController}
 *       的歌词更新方法 {@code (LyricsDocument, String)V} 与切歌播放入口 {@code (Song, long, long, Long)V}。
 *       同时支持主动探针式读取 {@code MusicController} 静态 StateFlow 实例，实现冷启动/切歌首帧瞬时提取整首歌词。</li>
 *   <li><b>精准数据结构解构与逐字时间戳映射：</b>
 *       通过 DexKit 检索 {@code LyricsDocument}、{@code LyricsLine} 与 {@code LyricsCell} 核心数据类，
 *       动态解析其行起始/结束时间戳、逐字微元（{@code SuperLyricWord}）与双轨副歌词/翻译（{@code pureSubText}），
 *       经 {@link LyricSanitizer} 安全清洗后装配为完整 {@link SuperLyricData}。</li>
 *   <li><b>车载/蓝牙歌词污染免疫：</b>
 *       由于椒盐音乐内置状态栏/车载蓝牙歌词功能会高频将当前行歌词写入 {@code MediaMetadata.METADATA_KEY_TITLE}，
 *       本实现优先从宿主内部 {@code Song.getId()} 提取不可变音轨标识，并以 {@code Song} 实体中的真实曲名/歌手修正元数据，
 *       彻底杜绝音轨状态抖动与歌词时钟重置。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.salt.music")
public class SaltMusicProvider extends UnifiedLyricProvider {
    private static final String TAG = "SaltMusicProvider";

    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();
    private final AtomicReference<Object> mCurrentSong = new AtomicReference<>();
    private final AtomicReference<Object> mCurrentLyricsDocument = new AtomicReference<>();
    private final AtomicReference<SuperLyricData> mCurrentLyricsData = new AtomicReference<>();
    private final AtomicBoolean mColdStartExtractPending = new AtomicBoolean(true);

    // 核心数据模型类
    private volatile Class<?> mLyricsDocumentClass;
    private volatile Class<?> mLyricsLineClass;
    private volatile Class<?> mLyricsCellClass;
    private volatile Class<?> mSongClass;

    // 宿主业务方法
    private volatile Method mSetLyricsMethod;
    private volatile Method mSongMethod;

    // StateFlow 字段与方法探针缓存
    private volatile Field mSongFlowField;
    private volatile Method mSongFlowGetValueMethod;
    private volatile Field mLyricsFlowField;
    private volatile Method mLyricsFlowGetValueMethod;

    // 反射字段缓存
    private volatile boolean mFieldsResolved = false;
    private volatile Field mFieldDocLines;
    private volatile Field mFieldDocSource;

    private volatile Field mFieldLineTime1;
    private volatile Field mFieldLineTime2;
    private volatile Field mFieldLineCells;
    private volatile Field mFieldLineSubText;
    private volatile Field mFieldLineMainText;

    private volatile Field mFieldCellTime1;
    private volatile Field mFieldCellTime2;
    private volatile Field mFieldCellText;

    public SaltMusicProvider() {
        super();
    }

    @NonNull
    @Override
    public ProviderCapability capability() {
        return ProviderCapability.FULL_HOOK_ONLY;
    }

    @Nullable
    @Override
    protected String extractTrackId(@NonNull MediaMetadata metadata) {
        Object song = getCurrentSongFromController();
        if (song != null) {
            try {
                String id = (String) callMethod(song, "getId");
                if (id != null && !id.trim().isEmpty()) {
                    return id.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return super.extractTrackId(metadata);
    }

    @Override
    protected void onMetadataChanged(@NonNull MediaMetadata metadata) {
        Object song = getCurrentSongFromController();
        if (song != null) {
            try {
                String songTitle = (String) callMethod(song, "getTitle");
                String songArtist = (String) callMethod(song, "getArtist");
                String songAlbum = (String) callMethod(song, "getAlbum");
                long songDuration = (long) callMethod(song, "getDuration");
                String trackId = (String) callMethod(song, "getId");
                if (TextUtils.isEmpty(trackId)) {
                    trackId = extractTrackId(metadata);
                }
                if (trackId == null || trackId.isEmpty()) {
                    mOrchestrator.onPlaybackStopped();
                    return;
                }

                String metaTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                String metaArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
                String metaAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
                long metaDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

                TrackContext active = mActiveTrack.get();
                if (active != null && TextUtils.equals(active.getTrackId(), trackId)) {
                    return;
                }

                long gen = mTrackGeneration.incrementAndGet();
                TrackContext context = new TrackContext(
                    gen,
                    trackId,
                    !TextUtils.isEmpty(songTitle) ? songTitle : metaTitle,
                    !TextUtils.isEmpty(songArtist) ? songArtist : metaArtist,
                    !TextUtils.isEmpty(songAlbum) ? songAlbum : metaAlbum,
                    songDuration > 0 ? songDuration : metaDuration
                );
                mActiveTrack.set(context);
                mOrchestrator.onTrackChanged(context);
                return;
            } catch (Throwable t) {
                logW(tag, "Failed to resolve metadata from Song object: " + t.getMessage());
            }
        }
        super.onMetadataChanged(metadata);
    }

    @Override
    protected void onPlaybackStateChanged(@NonNull PlaybackState state) {
        checkSongAndUpdateTrack();
        super.onPlaybackStateChanged(state);
    }

    private void checkSongAndUpdateTrack() {
        try {
            Object song = getCurrentSongFromController();
            if (song != null) {
                updateTrackIfChanged(song);
            }
        } catch (Throwable t) {
            logW(tag, "Error checking song on playback state changed: " + t.getMessage());
        }
    }

    /**
     * 响应宿主检测到的 Song 实例，仅在音轨发生实质性变更时更新当前音轨与元数据。
     */
    private void updateTrackIfChanged(@NonNull Object song) {
        try {
            String trackId = (String) callMethod(song, "getId");
            if (TextUtils.isEmpty(trackId)) return;

            mCurrentSong.set(song);

            TrackContext active = mActiveTrack.get();
            if (active == null || !TextUtils.equals(active.getTrackId(), trackId)) {
                String songTitle = (String) callMethod(song, "getTitle");
                String songArtist = (String) callMethod(song, "getArtist");
                String songAlbum = (String) callMethod(song, "getAlbum");
                long songDuration = (long) callMethod(song, "getDuration");

                long gen = mTrackGeneration.incrementAndGet();
                TrackContext context = new TrackContext(
                    gen,
                    trackId,
                    !TextUtils.isEmpty(songTitle) ? songTitle : (active != null ? active.getTitle() : ""),
                    !TextUtils.isEmpty(songArtist) ? songArtist : (active != null ? active.getArtist() : ""),
                    !TextUtils.isEmpty(songAlbum) ? songAlbum : (active != null ? active.getAlbum() : ""),
                    songDuration > 0 ? songDuration : (active != null ? active.getDuration() : 0L)
                );
                mActiveTrack.set(context);
                mCurrentLyricsData.set(null);
                mCurrentLyricsDocument.set(null);

                logI(tag, "Track switched via Song detection: trackId=" + trackId
                    + ", title=" + context.getTitle()
                    + ", artist=" + context.getArtist()
                    + ", duration=" + context.getDuration() + "ms");

                if (mOrchestrator != null) {
                    mOrchestrator.onTrackChanged(context);
                }
            }
        } catch (Throwable t) {
            logE(tag, "Error handling detected Song", t);
        }
    }

    @Nullable
    @Override
    protected IHookLyricEngine createHookEngine() {
        return new IHookLyricEngine() {
            @Override
            public void initHooks() {
                findModelClasses();
                resolveModelFields();
                findAndHookMusicController();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
                SuperLyricData cached = mCurrentLyricsData.get();
                if (cached != null && !Objects.equals(cached.getLyricId(), context.getTrackId())) {
                    mCurrentLyricsData.set(null);
                }
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                mActiveTrack.set(context);
                SuperLyricData cached = mCurrentLyricsData.get();
                if (cached != null && cached.hasAllLyrics() && Objects.equals(cached.getLyricId(), context.getTrackId())) {
                    return cached;
                }

                // 仅在首次冷启动且尚未获取过歌词时，尝试从 Controller 的 StateFlow 探针拉取当前文档
                if (mColdStartExtractPending.compareAndSet(true, false)) {
                    Object doc = getCurrentLyricsDocumentFromController(mLyricsDocumentClass);
                    if (doc != null) {
                        SuperLyricData parsed = parseLyricsDocument(doc);
                        if (parsed != null && parsed.hasAllLyrics() && Objects.equals(parsed.getLyricId(), context.getTrackId())) {
                            mCurrentLyricsData.set(parsed);
                            return parsed;
                        }
                    }
                }
                return null;
            }
        };
    }

    /**
     * 定位核心歌词模型类 (LyricsDocument, LyricsLine, LyricsCell)。
     */
    private void findModelClasses() {
        try {
            mLyricsDocumentClass = DexkitCache.findMember("salt_lyrics_document_class_v1", new IDexkit<ClassData>() {
                @Nullable
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("LyricsDocument(sourceText=")
                        )
                    ).single();
                }
            });

            mLyricsLineClass = DexkitCache.findMember("salt_lyrics_line_class_v1", new IDexkit<ClassData>() {
                @Nullable
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("LyricsLine(startTime=")
                        )
                    ).single();
                }
            });

            mLyricsCellClass = DexkitCache.findMember("salt_lyrics_cell_class_v1", new IDexkit<ClassData>() {
                @Nullable
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("LyricsCell(startTime=")
                        )
                    ).single();
                }
            });

            logI(tag, "Resolved Salt Player lyric model classes: document=" + mLyricsDocumentClass
                + ", line=" + mLyricsLineClass
                + ", cell=" + mLyricsCellClass);
        } catch (Throwable t) {
            logE(tag, "Failed to find Salt Player model classes via DexKit", t);
        }
    }

    /**
     * 解析模型类的反射字段，完全不依赖具体的混淆字段名称。
     */
    private synchronized void resolveModelFields() {
        if (mFieldsResolved) return;
        if (mLyricsDocumentClass == null || mLyricsLineClass == null || mLyricsCellClass == null)
            return;

        try {
            // 1. LyricsDocument 字段解析
            for (Field f : mLyricsDocumentClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    mFieldDocLines = f;
                } else if (f.getType() == String.class) {
                    f.setAccessible(true);
                    mFieldDocSource = f;
                }
            }

            // 2. LyricsLine 字段解析
            List<Field> lineLongs = new ArrayList<>();
            List<Field> lineStrings = new ArrayList<>();
            for (Field f : mLyricsLineClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == long.class) {
                    f.setAccessible(true);
                    lineLongs.add(f);
                } else if (f.getType() == String.class) {
                    f.setAccessible(true);
                    lineStrings.add(f);
                } else if (List.class.isAssignableFrom(f.getType()) && !ArrayList.class.equals(f.getType())) {
                    f.setAccessible(true);
                    mFieldLineCells = f;
                }
            }
            if (lineLongs.size() >= 2) {
                mFieldLineTime1 = lineLongs.get(0);
                mFieldLineTime2 = lineLongs.get(1);
            }
            if (lineStrings.size() >= 2) {
                mFieldLineSubText = lineStrings.get(0);
                mFieldLineMainText = lineStrings.get(1);
            } else if (lineStrings.size() == 1) {
                mFieldLineMainText = lineStrings.get(0);
            }
            if (mFieldLineCells == null) {
                for (Field f : mLyricsLineClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        mFieldLineCells = f;
                        break;
                    }
                }
            }

            // 3. LyricsCell 字段解析
            List<Field> cellLongs = new ArrayList<>();
            for (Field f : mLyricsCellClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == long.class) {
                    f.setAccessible(true);
                    cellLongs.add(f);
                } else if (f.getType() == String.class) {
                    f.setAccessible(true);
                    mFieldCellText = f;
                }
            }
            if (cellLongs.size() >= 2) {
                mFieldCellTime1 = cellLongs.get(0);
                mFieldCellTime2 = cellLongs.get(1);
            }

            mFieldsResolved = (mFieldDocLines != null && mFieldLineTime1 != null && mFieldLineCells != null);
            logI(tag, "Model fields resolved successfully: resolved=" + mFieldsResolved);
        } catch (Throwable t) {
            logE(tag, "Failed to resolve model fields", t);
        }
    }

    /**
     * 发现并 Hook 宿主 MusicController 核心中枢。
     */
    private void findAndHookMusicController() {
        try {
            Class<?> mcClass = findClass("com.salt.music.service.MusicController");
            Class<?> songClass = findClass("com.salt.music.data.entry.Song");
            mSongClass = songClass;

            for (Method m : mcClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                // 1. 匹配歌词设置方法: (LyricsDocument, String)V
                if (params.length == 2 && mLyricsDocumentClass != null
                    && params[0].equals(mLyricsDocumentClass) && params[1].equals(String.class)) {
                    mSetLyricsMethod = m;
                    mSetLyricsMethod.setAccessible(true);
                } else if (params.length == 4 && songClass.equals(params[0])) {
                    // 2. 匹配切歌/手动播放入口方法: (Song, long, long, Long)V
                    mSongMethod = m;
                    mSongMethod.setAccessible(true);
                }
            }

            if (mSetLyricsMethod != null) {
                hook(mSetLyricsMethod, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            Object doc = getArg(0);
                            onLyricsDocumentUpdated(doc);
                        } catch (Throwable t) {
                            logE(tag, "Error in setLyricsMethod hook", t);
                        }
                    }
                });
                logI(tag, "Hooked MusicController setLyricsMethod successfully");
            } else {
                logW(tag, "MusicController setLyricsMethod not found");
            }

            if (mSongMethod != null) {
                hook(mSongMethod, new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            Object song = getArg(0);
                            if (song != null) {
                                updateTrackIfChanged(song);
                            }
                        } catch (Throwable t) {
                            logE(tag, "Error in songMethod hook", t);
                        }
                    }
                });
                logI(tag, "Hooked MusicController songMethod successfully");
            } else {
                logW(tag, "MusicController songMethod not found");
            }
        } catch (Throwable t) {
            logE(tag, "Failed to hook MusicController", t);
        }
    }

    /**
     * 响应宿主底层歌词文档更新事件。
     */
    private void onLyricsDocumentUpdated(@Nullable Object doc) {
        mCurrentLyricsDocument.set(doc);
        if (doc == null) {
            mCurrentLyricsData.set(null);
            checkSongAndUpdateTrack();
            return;
        }

        SuperLyricData fullData = parseLyricsDocument(doc);
        if (fullData != null && fullData.hasAllLyrics()) {
            mCurrentLyricsData.set(fullData);
            TrackContext active = mActiveTrack.get();
            if (active == null || !TextUtils.equals(active.getTrackId(), fullData.getLyricId())) {
                long gen = mTrackGeneration.incrementAndGet();
                active = new TrackContext(
                    gen,
                    fullData.getLyricId(),
                    fullData.getTitle(),
                    fullData.getArtist(),
                    fullData.getAlbum(),
                    fullData.getDuration()
                );
                mActiveTrack.set(active);
                logI(tag, "TrackContext updated from LyricsDocument: trackId=" + active.getTrackId()
                    + ", title=" + active.getTitle() + ", artist=" + active.getArtist());
            }
            if (mOrchestrator != null) {
                mOrchestrator.onTrackChanged(active);
                mOrchestrator.onHookFullLyricCaptured(active, fullData);
            }
            logI(tag, "Successfully captured Salt Player lyrics: trackId=" + fullData.getLyricId()
                + ", title=" + fullData.getTitle() + ", lines=" + fullData.getAllLyrics().length);
        }
    }

    /**
     * 解析宿主原生 LyricsDocument 结构为标准 SuperLyricData。
     */
    @Nullable
    private SuperLyricData parseLyricsDocument(@NonNull Object docObj) {
        try {
            if (!mFieldsResolved) {
                resolveModelFields();
            }
            if (mFieldDocLines == null) {
                return null;
            }

            List<?> rawLines = (List<?>) mFieldDocLines.get(docObj);
            if (rawLines == null || rawLines.isEmpty()) {
                return null;
            }

            List<SuperLyricLine> resultLines = new ArrayList<>(rawLines.size());
            for (Object lineObj : rawLines) {
                if (lineObj == null) continue;

                long t1 = mFieldLineTime1 != null ? (long) mFieldLineTime1.get(lineObj) : 0L;
                long t2 = mFieldLineTime2 != null ? (long) mFieldLineTime2.get(lineObj) : 0L;
                long lineStart = Math.min(t1, t2);
                long lineEnd = Math.max(t1, t2);

                String str1 = mFieldLineSubText != null ? (String) mFieldLineSubText.get(lineObj) : null;
                String str2 = mFieldLineMainText != null ? (String) mFieldLineMainText.get(lineObj) : null;

                List<?> rawCells = mFieldLineCells != null ? (List<?>) mFieldLineCells.get(lineObj) : null;
                List<SuperLyricWord> wordsList = new ArrayList<>();
                StringBuilder wordsSb = new StringBuilder();

                if (rawCells != null && !rawCells.isEmpty()) {
                    int cellCount = rawCells.size();
                    for (int c = 0; c < cellCount; c++) {
                        Object cellObj = rawCells.get(c);
                        if (cellObj == null) continue;
                        long ct1 = mFieldCellTime1 != null ? (long) mFieldCellTime1.get(cellObj) : 0L;
                        long ct2 = mFieldCellTime2 != null ? (long) mFieldCellTime2.get(cellObj) : 0L;
                        long cStart = Math.min(ct1, ct2);
                        long cEnd = Math.max(ct1, ct2);
                        String cText = mFieldCellText != null ? (String) mFieldCellText.get(cellObj) : null;
                        if (cText != null) {
                            wordsSb.append(cText);
                            if (cEnd <= cStart) {
                                // 启发式自愈：cEnd <= cStart 时优先采纳下一词起始，否则按字符权重保底
                                long nextStart = -1L;
                                if (c + 1 < cellCount) {
                                    Object nextCell = rawCells.get(c + 1);
                                    if (nextCell != null && mFieldCellTime1 != null && mFieldCellTime2 != null) {
                                        long nct1 = (long) mFieldCellTime1.get(nextCell);
                                        long nct2 = (long) mFieldCellTime2.get(nextCell);
                                        nextStart = Math.min(nct1, nct2);
                                    }
                                }
                                if (nextStart > cStart) {
                                    cEnd = nextStart;
                                } else {
                                    cEnd = cStart + Math.max(120L, (long) cText.length() * 150L);
                                    if (lineEnd > cStart && cEnd > lineEnd) {
                                        cEnd = lineEnd;
                                    }
                                }
                            }
                            if (cEnd <= cStart) {
                                cEnd = cStart + Math.max(120L, (long) cText.length() * 150L);
                            }
                            wordsList.add(new SuperLyricWord(cText, cStart, cEnd));
                        }
                    }
                    if (!wordsList.isEmpty()) {
                        LyricSanitizer.healWordTimings(wordsList, lineStart, lineEnd);
                    }
                }

                String fullWordsText = wordsSb.toString();
                String mainText;
                String subText;

                if (!TextUtils.isEmpty(fullWordsText)) {
                    if (TextUtils.equals(str2, fullWordsText)) {
                        mainText = str2;
                        subText = str1;
                    } else if (TextUtils.equals(str1, fullWordsText)) {
                        mainText = str1;
                        subText = str2;
                    } else {
                        mainText = !TextUtils.isEmpty(str2) ? str2 : fullWordsText;
                        subText = str1;
                    }
                } else {
                    mainText = !TextUtils.isEmpty(str2) ? str2 : str1;
                    subText = !TextUtils.isEmpty(str2) ? str1 : null;
                }

                SuperLyricWord[] wordsArr = wordsList.size() > 1 ? wordsList.toArray(new SuperLyricWord[0]) : null;
                SuperLyricLine rawLine = new SuperLyricLine(mainText, wordsArr, subText, lineStart, lineEnd);
                SuperLyricLine sanitized = LyricSanitizer.sanitizeLine(rawLine);
                if (sanitized != null) {
                    resultLines.add(sanitized);
                }
            }

            if (resultLines.isEmpty()) {
                return null;
            }

            SuperLyricData fullData = new SuperLyricData();
            fullData.setAllLyrics(resultLines.toArray(new SuperLyricLine[0]));

            Object song = getCurrentSongFromController();
            String title = null;
            String artist = null;
            String album = null;
            long duration = 0L;
            String trackId = null;

            if (song != null) {
                try {
                    title = (String) callMethod(song, "getTitle");
                    artist = (String) callMethod(song, "getArtist");
                    album = (String) callMethod(song, "getAlbum");
                    duration = (long) callMethod(song, "getDuration");
                    trackId = (String) callMethod(song, "getId");
                } catch (Throwable ignored) {
                }
            }

            TrackContext active = mActiveTrack.get();
            if (TextUtils.isEmpty(trackId) && active != null) {
                trackId = active.getTrackId();
            }
            if (TextUtils.isEmpty(title) && active != null) {
                title = active.getTitle();
            }
            if (TextUtils.isEmpty(artist) && active != null) {
                artist = active.getArtist();
            }
            if (TextUtils.isEmpty(album) && active != null) {
                album = active.getAlbum();
            }
            if (duration <= 0 && active != null) {
                duration = active.getDuration();
            }

            fullData.setLyricId(trackId);
            fullData.setTitle(title);
            fullData.setArtist(artist);
            fullData.setAlbum(album);
            fullData.setDuration(duration);

            return LyricSanitizer.sanitizeData(fullData);
        } catch (Throwable t) {
            logE(tag, "Failed to parse LyricsDocument", t);
            return null;
        }
    }

    /**
     * 实时读取 MusicController 当前 Song 对象（优先通过 StateFlow 探针，杜绝静态缓存滞留）。
     */
    @Nullable
    private Object getCurrentSongFromController() {
        // 1. 优先读取已解析的 StateFlow 字段
        if (mSongFlowField != null) {
            try {
                Object flowObj = mSongFlowField.get(null);
                if (flowObj != null) {
                    if (mSongClass != null && mSongClass.isInstance(flowObj)) {
                        mCurrentSong.set(flowObj);
                        return flowObj;
                    }
                    Method getVal = mSongFlowGetValueMethod != null
                        ? mSongFlowGetValueMethod
                        : flowObj.getClass().getMethod("getValue");
                    Object inner = getVal.invoke(flowObj);
                    if (inner != null && (mSongClass == null || mSongClass.isInstance(inner))) {
                        mSongFlowGetValueMethod = getVal;
                        mCurrentSong.set(inner);
                        return inner;
                    }
                    return null;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. 动态扫描 MusicController 静态字段探针
        try {
            Class<?> mcClass = findClass("com.salt.music.service.MusicController");
            Class<?> songClass = mSongClass != null ? mSongClass : findClass("com.salt.music.data.entry.Song");
            mSongClass = songClass;

            for (Field f : mcClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) {
                        if (songClass.isInstance(val)) {
                            mSongFlowField = f;
                            mCurrentSong.set(val);
                            return val;
                        }
                        try {
                            Method getVal = val.getClass().getMethod("getValue");
                            Object inner = getVal.invoke(val);
                            if (inner != null && songClass.isInstance(inner)) {
                                mSongFlowField = f;
                                mSongFlowGetValueMethod = getVal;
                                mCurrentSong.set(inner);
                                return inner;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 3. 回退至 Hook 捕获的最新 Song 引用
        return mCurrentSong.get();
    }

    /**
     * 实时读取 MusicController 当前存储的 LyricsDocument 对象。
     */
    @Nullable
    private Object getCurrentLyricsDocumentFromController(@Nullable Class<?> lyricsDocClass) {
        if (lyricsDocClass == null) return mCurrentLyricsDocument.get();

        if (mLyricsFlowField != null) {
            try {
                Object flowObj = mLyricsFlowField.get(null);
                if (flowObj != null) {
                    if (lyricsDocClass.isInstance(flowObj)) {
                        mCurrentLyricsDocument.set(flowObj);
                        return flowObj;
                    }
                    Method getVal = mLyricsFlowGetValueMethod != null
                        ? mLyricsFlowGetValueMethod
                        : flowObj.getClass().getMethod("getValue");
                    Object inner = getVal.invoke(flowObj);
                    if (inner != null && lyricsDocClass.isInstance(inner)) {
                        mLyricsFlowGetValueMethod = getVal;
                        mCurrentLyricsDocument.set(inner);
                        return inner;
                    }
                    return null;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            Class<?> mcClass = findClass("com.salt.music.service.MusicController");
            for (Field f : mcClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) {
                        if (lyricsDocClass.isInstance(val)) {
                            mLyricsFlowField = f;
                            mCurrentLyricsDocument.set(val);
                            return val;
                        }
                        try {
                            Method getVal = val.getClass().getMethod("getValue");
                            Object inner = getVal.invoke(val);
                            if (inner != null && lyricsDocClass.isInstance(inner)) {
                                mLyricsFlowField = f;
                                mLyricsFlowGetValueMethod = getVal;
                                mCurrentLyricsDocument.set(inner);
                                return inner;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return mCurrentLyricsDocument.get();
    }
}
