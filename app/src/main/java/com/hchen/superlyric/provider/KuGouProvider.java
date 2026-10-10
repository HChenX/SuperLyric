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
import android.os.Environment;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.engine.multisource.KuGouLyricSource;
import com.hchen.superlyric.engine.multisource.MultiSourceLyricEngine;
import com.hchen.superlyric.parser.KrcDecoder;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 酷狗音乐统一歌词提供者。
 * <p>
 * <b>架构革新与原生报文自解析 (酷狗音乐 20.8.2+)：</b>
 * <ul>
 *   <li><b>彻底斩断末端模型反射：</b>
 *       废除对宿主内部已被赋值的 {@code LyricData} 进行行时间、字时间、文本矩阵的多层反射抽取，
 *       杜绝因宿主内部类混淆、结构变动造成的偶发崩溃与 0ms 时序缺陷；</li>
 *   <li><b>源头精准 Hash 锚定与原生 KRC 自解析：</b>
 *       仅通过宿主跨进程核心 {@code @twin:GlobalVariate} 提取不可变的 32 位音频 {@code SongHash}（Key=207），
 *       直接获取原生 KRC 密文报文（本地私有缓存或官方直拉），由 {@link KrcDecoder} 进行模块内 XOR+ZLib 原生自解析；</li>
 *   <li><b>绝对免疫蓝牙歌词污染：</b>
 *       直接使用底层不可变的音频 Hash 作为音轨唯一标识，彻底消除切歌与推演时的数据闪烁与多余开销。</li>
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
    private volatile Method mGetHashMethod;
    private volatile Method mSetLyricDataMethod;

    public KuGouProvider() {
        super();
    }

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
                findGlobalValueAccessor();
                hookLyricDataSetter();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                mActiveTrack.set(context);
                return resolveKrcLyric(context);
            }
        };
    }

    /**
     * 定位全局 IPC 跨进程访问中枢，获取 SongHash (Key=207) 读取方法与 LyricData (Key=41) 写入通知方法。
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
                if (Modifier.isStatic(m.getModifiers())) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 && params[0] == int.class
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

            AndroidLog.logI(TAG, "Found Kugou GlobalValue accessors: getHash=" + mGetHashMethod
                + ", setLyricData=" + mSetLyricDataMethod);
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to find Kugou GlobalValue accessor", t);
        }
    }

    /**
     * 拦截宿主向全局 IPC 总线写入 {@code LyricData} (Key=41) 的统一 Setter 方法。
     * <p>
     * 当酷狗加载或异步更新歌词完毕时触发，直接提取当前 Hash 并拉取原生 KRC 自解析。
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
                            String songHash = getSongHash();
                            if (!TextUtils.isEmpty(songHash) && !TextUtils.equals(mLastProcessedHash, songHash)) {
                                TrackContext active = mActiveTrack.get();
                                CompletableFuture.runAsync(() -> {
                                    TrackContext ctx = active;
                                    if (ctx == null) {
                                        long gen = mTrackGeneration.incrementAndGet();
                                        ctx = new TrackContext(gen, songHash, "", "", "", 0L);
                                        mActiveTrack.set(ctx);
                                    }
                                    SuperLyricData data = resolveKrcLyric(ctx);
                                    if (data != null && data.hasAllLyrics()) {
                                        mLastProcessedHash = songHash;
                                        if (mOrchestrator != null) {
                                            mOrchestrator.onTrackChanged(ctx);
                                            mOrchestrator.onHookFullLyricCaptured(ctx, data);
                                        }
                                    }
                                });
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
     * 提取音轨唯一标识，优先直接提取宿主全局双进程 IPC 中枢的不可变音频 Hash（Key=207）。
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
     * 读取当前播放曲目的全局 32 位 Hash。
     */
    @NonNull
    private String getSongHash() {
        if (mGetHashMethod != null) {
            try {
                Object hashObj = mGetHashMethod.invoke(null, 207, "");
                if (hashObj instanceof String s && !s.trim().isEmpty()) {
                    return s.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /**
     * 核心：基于 32 位 Hash 读取原生 KRC 报文并进行模块内原生自解析。
     */
    @Nullable
    private SuperLyricData resolveKrcLyric(@NonNull TrackContext context) {
        String hash = getSongHash();
        if (hash.isEmpty() && context.getTrackId() != null && context.getTrackId().length() == 32) {
            hash = context.getTrackId();
        }

        if (hash.isEmpty()) {
            return null;
        }

        String title = context.getTitle();
        String artist = context.getArtist();
        long duration = context.getDuration();

        // 1. 尝试从酷狗本地文件缓存快速读取原生 KRC 报文
        SuperLyricData localData = tryReadLocalKrcFile(hash);
        if (localData != null && localData.hasAllLyrics()) {
            AndroidLog.logI(TAG, "Successfully loaded KRC from local disk cache for: " + hash);
            return LyricSanitizer.sanitizeData(localData);
        }

        // 2. 官方 KRC 接口毫秒级直拉（零签名、零鉴权、原生 16 字节 XOR 密文流）
        SuperLyricData netData = KuGouLyricSource.fetchLyricByHash(hash, duration, title, artist);
        if (netData != null && netData.hasAllLyrics()) {
            return LyricSanitizer.sanitizeData(netData);
        }

        return null;
    }

    /**
     * 检索酷狗本地私有磁盘缓存中的 KRC 密文文件。
     */
    @Nullable
    private SuperLyricData tryReadLocalKrcFile(@NonNull String hash) {
        String[] candidateDirs = new String[]{
            "/sdcard/kugou/lyric/",
            "/sdcard/Android/data/com.kugou.android/files/kugou/lyric/",
            "/data/user/0/com.kugou.android/files/lyric/"
        };

        for (String dir : candidateDirs) {
            try {
                File f = new File(dir, hash + ".krc");
                if (f.exists() && f.isFile() && f.length() > 4) {
                    byte[] bytes = Files.readAllBytes(f.toPath());
                    SuperLyricLine[] lines = KrcDecoder.decode(bytes);
                    if (lines != null && lines.length > 0) {
                        SuperLyricData data = new SuperLyricData();
                        data.setLyricId(hash);
                        data.setAllLyrics(lines);
                        return data;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
