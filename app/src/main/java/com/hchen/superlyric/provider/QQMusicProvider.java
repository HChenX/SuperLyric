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
import com.hchen.superlyric.engine.multisource.MultiSourceLyricEngine;
import com.hchen.superlyric.engine.multisource.QQMusicLyricSource;
import com.hchen.superlyric.parser.QrcDecoder;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐统一歌词提供者。
 * <p>
 * <b>架构革新与原生报文自解析 (QQ 音乐 20.9.0+)：</b>
 * <ul>
 *   <li><b>彻底斩断末端混淆模型反射：</b>
 *       全面废除在 UI 与消费端深层反射解析混淆行/词模型（{@code com.lyricengine.base.t} 等）的旧方案，
 *       删除数百行脆弱的字段拓扑推导与启发式试探代码；</li>
 *   <li><b>源头获取官方 songMID / songID：</b>
 *       仅通过 Hook 宿主核心载体 {@code LyricLoadBean} 构造函数（特征常量 {@code "LyricLoadBean(songInfo="}）
 *       或系统级 {@code MediaSession}，安全提取当前曲目不可变唯一标识；</li>
 *   <li><b>原生 QRC 3DES 解密与 XML 规整：</b>
 *       直接由 {@link QrcDecoder} 对拉取的原生加密报文进行 3DES+ZLib 独立解码，
 *       获取 100% 原始、无任何宿主篡改的逐字时序与双语翻译；</li>
 *   <li><b>双轨多源仲裁保障：</b>
 *       当官方 QRC 未命中或曲目无版权时，平滑降级至 {@link MultiSourceLyricEngine} 多源打分检索。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.tencent.qqmusic")
public class QQMusicProvider extends UnifiedLyricProvider {
    private static final String TAG = "QQMusicProvider";

    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();
    private volatile String mLastProcessedTrackId = null;

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
                hookLyricLoadBeanInit();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                mActiveTrack.set(context);
                return resolveOfficialQrc(context);
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
     */
    private static boolean isPlausibleHumanText(@Nullable String s) {
        if (s == null) return false;
        String str = s.trim();
        if (str.isEmpty() || str.length() > 80) return false;
        if (str.startsWith("http://") || str.startsWith("https://")) return false;
        if (str.contains("\n") || str.contains("\r") || str.contains("\t")) return false;
        if ("未知歌手".equals(str) || "未知专辑".equals(str) || "null".equalsIgnoreCase(str))
            return false;
        if (str.length() >= 16 && str.matches("^[0-9A-Fa-f]+$")) return false;
        if (str.startsWith("{") || str.startsWith("[") || str.startsWith("<")) return false;
        return true;
    }

    /**
     * Hook {@code LyricLoadBean} 构造函数以获取当前曲目 {@code SongInfo}。
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
                    if (c.getParameterCount() >= 4) {
                        c.setAccessible(true);
                        hook(c, new AbsHook() {
                            @Override
                            public void after() {
                                Object songInfo = getArg(0);
                                handleCapturedSongInfo(songInfo, "LyricLoadBean.<init>");
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
     * 截获到曲目实体信息后，直接根据官方 ID 进行原生 QRC 解析与分发。
     */
    private void handleCapturedSongInfo(@Nullable Object songInfo, @NonNull String source) {
        if (songInfo == null) return;

        String processName = getProcessNameCompat();
        if (processName != null && !processName.endsWith(":QQPlayerService") && mActiveTrack.get() == null) {
            AndroidLog.logD(TAG, "Ignoring captured songInfo from non-player process (" + processName + ")");
            return;
        }

        try {
            TrackMetadata meta = extractMetadata(songInfo, 0L);
            if (meta.title.isEmpty() && meta.songMid.isEmpty() && meta.songNumericId <= 0) {
                return;
            }

            if (Objects.equals(mLastProcessedTrackId, meta.trackId)) {
                return;
            }

            TrackContext active = mActiveTrack.get();
            long gen;
            if (active != null && Objects.equals(active.getTrackId(), meta.trackId)) {
                gen = active.getGeneration();
            } else {
                gen = mTrackGeneration.incrementAndGet();
            }
            TrackContext context = new TrackContext(gen, meta.trackId, meta.title, meta.artist, meta.album, meta.duration);
            mActiveTrack.set(context);

            CompletableFuture.runAsync(() -> {
                try {
                    SuperLyricData fullData = resolveOfficialQrc(context, meta);
                    if (fullData != null && fullData.hasAllLyrics()) {
                        mLastProcessedTrackId = meta.trackId;
                        if (mOrchestrator != null) {
                            mOrchestrator.onTrackChanged(context);
                            mOrchestrator.onHookFullLyricCaptured(context, fullData);
                        }
                        AndroidLog.logI(TAG, "[" + source + "] Successfully resolved native QRC for " + meta.title
                            + " - " + meta.artist + " (mid=" + meta.songMid + ", id=" + meta.songNumericId + ")");
                    } else {
                        if (mOrchestrator != null) {
                            mOrchestrator.onHookDeterminedInvalid(context);
                        }
                    }
                } catch (Throwable t) {
                    AndroidLog.logW(TAG, "[" + source + "] Error resolving QRC: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "[" + source + "] Error handling captured songInfo", t);
        }
    }

    /**
     * 核心：基于 songMID / songID 直接拉取原生加密 QRC 报文并进行原生自解析。
     */
    @Nullable
    private SuperLyricData resolveOfficialQrc(@NonNull TrackContext context) {
        TrackMetadata meta = extractMetadata(null, context.getDuration());
        return resolveOfficialQrc(context, meta);
    }

    @Nullable
    private SuperLyricData resolveOfficialQrc(@NonNull TrackContext context, @NonNull TrackMetadata meta) {
        String mid = !meta.songMid.isEmpty() ? meta.songMid :
            (context.getTrackId() != null && context.getTrackId().length() == 14 ? context.getTrackId() : "");
        long id = meta.songNumericId;
        if (id <= 0 && context.getTrackId() != null && context.getTrackId().matches("^\\d+$")) {
            try {
                id = Long.parseLong(context.getTrackId());
            } catch (Throwable ignored) {
            }
        }

        if (mid.isEmpty() && id <= 0) {
            return null;
        }

        SuperLyricData qrcData = QQMusicLyricSource.fetchLyricByMid(mid, id, context.getTitle(), context.getArtist());
        if (qrcData != null && qrcData.hasAllLyrics()) {
            qrcData.setLyricId(context.getTrackId());
            return LyricSanitizer.sanitizeData(qrcData);
        }
        return null;
    }

    /**
     * 自适应提取曲目元数据（优先 MediaSession 与 SongInfo 标准公开字段，零混淆依赖）。
     */
    @NonNull
    private TrackMetadata extractMetadata(@Nullable Object songInfo, long fallbackDuration) {
        String title = "";
        String artist = "";
        String album = "";
        String trackId = "";
        String songMid = "";
        long songNumericId = 0L;
        long duration = fallbackDuration;

        TrackContext active = mActiveTrack.get();
        if (active != null) {
            if (isPlausibleHumanText(active.getTitle())) title = active.getTitle();
            if (isPlausibleHumanText(active.getArtist())) artist = active.getArtist();
            if (isPlausibleHumanText(active.getAlbum())) album = active.getAlbum();
            if (active.getDuration() > 0) duration = active.getDuration();
            if (active.getTrackId() != null && !active.getTrackId().isEmpty())
                trackId = active.getTrackId();
        }

        if (songInfo != null) {
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

        return new TrackMetadata(title, artist, album, trackId, songMid, songNumericId, duration);
    }

    private static class TrackMetadata {
        final String title;
        final String artist;
        final String album;
        final String trackId;
        final String songMid;
        final long songNumericId;
        final long duration;

        TrackMetadata(String title, String artist, String album, String trackId, String songMid, long songNumericId, long duration) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.trackId = trackId;
            this.songMid = songMid;
            this.songNumericId = songNumericId;
            this.duration = duration;
        }
    }
}
