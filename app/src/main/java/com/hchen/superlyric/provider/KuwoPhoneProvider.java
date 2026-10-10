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

import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.engine.multisource.MultiSourceLyricEngine;
import com.hchen.superlyric.parser.KuwoLrcxParser;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.engine.INetworkLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.SuperLyricData;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 酷我音乐（手机版）统一歌词提供者。
 * <p>
 * <b>逆向适配与架构设计说明（{@code cn.kuwo.player} 手机版）：</b>
 * <ul>
 *   <li><b>源头截获解密后的 LRCX 明文：</b>
 *       手机版宿主内部由 {@code cn.kuwo.mod.lyrics.b0#m(byte[], LyricsDefine$LyricsType)}
 *       统一承担歌词报文入口：先经 {@code cn.kuwo.base.utils.y2#b(byte[], int, int)} 做字节还原，
 *       再由 {@code cn.kuwo.base.utils.o2#g(byte[], String)} 以 UTF-8 还原为字符串；
 *       当 {@code LyricsDefine$LyricsType#isLRCX()} 为真时，进一步调用
 *       {@code x0.b#d(String, String, String)}（算法盐 {@code "yeelion"}）完成 LRCX 逐字报文解密，
 *       并最终返回明文。本 Provider 直接在该方法 <b>返回值处</b> 实施轻量拦截，
 *       零侵入地拿到宿主已解密完成的原生报文，彻底规避在数据消费末端反射解析
 *       混淆行/词模型的脆弱性；</li>
 *   <li><b>模块原生自解析：</b>
 *       捕获到的原始报文直接交由 {@link KuwoLrcxParser#parseLrcx} 进行高精数学自解析
 *       （自动识别 {@code [kuwo:xxx]} 八进制密钥头部并解算逐字时间轴），
 *       从根源上规避宿主编解码器可能造成的 0ms 坍缩缺陷；</li>
 *   <li><b>元数据兜底与双轨网络引擎：</b>
 *       曲目元数据优先取自 {@code MediaSession} 上下文，缺失时由报文内的
 *       {@code [ti:]/[ar:]/[al:]} 标签自动补齐；未命中或纯本地音轨时
 *       无缝交由 {@link MultiSourceLyricEngine} 多源聚合兜底。</li>
 * </ul>
 *
 * <p><b>零破坏说明：</b>本 Provider 仅在 {@code b0#m} 的 {@code after} 阶段读取返回值，
 * 不修改任何入参、不改变宿主返回结果、不阻断原有调用链，宿主行为与未安装模块时完全一致。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "cn.kuwo.player")
public class KuwoPhoneProvider extends UnifiedLyricProvider {
    private static final String TAG = "KuwoPhoneProvider";

    /**
     * 手机版歌词解密中枢类（包路径稳定）。
     */
    private static final String CLS_LYRICS_DECODER = "cn.kuwo.mod.lyrics.b0";
    /**
     * 歌词类型枚举，用于区分原文 / 翻译 / 音译等报文。
     */
    private static final String CLS_LYRICS_TYPE = "cn.kuwo.mod.lyrics.LyricsDefine$LyricsType";

    private final AtomicReference<TrackContext> mActiveTrack = new AtomicReference<>();
    /**
     * 报文指纹，用于抑制宿主对同一份歌词的重复回调与重复上报。
     */
    private final AtomicReference<String> mLastLrcxSignature = new AtomicReference<>("");

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
                hookLrcxDecryptor();
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                mActiveTrack.set(context);
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                // 纯异步报文事件驱动：拦截到解密后的原始 LRCX 后即时上报，此处无需同步返回。
                return null;
            }
        };
    }

    /**
     * 拦截手机版歌词解密中枢 {@code cn.kuwo.mod.lyrics.b0#m(byte[], LyricsDefine$LyricsType)}。
     * <p>
     * 该方法接收原始字节数组并返回已解密（LRCX）或已还原（LRC）的歌词明文，
     * 是本 Provider 唯一的挂载点，保证拦截发生在宿主完成全部解码之后、数据被消费之前。
     * <p>
     * 采用「精确签名反射定位 + Method 直挂」方式，避免依赖类型名字符串解析，
     * 亦不触发目标类的静态初始化，最大限度保证零破坏。
     */
    private void hookLrcxDecryptor() {
        try {
            ClassLoader hostLoader = ModuleData.getClassLoader();
            if (hostLoader == null) {
                AndroidLog.logW(TAG, "Host classloader is null, skip hooking LRCX decryptor");
                return;
            }
            Class<?> decoderClass = Class.forName(CLS_LYRICS_DECODER, false, hostLoader);
            Class<?> lyricsTypeClass = Class.forName(CLS_LYRICS_TYPE, false, hostLoader);

            Method decryptMethod = decoderClass.getDeclaredMethod("m", byte[].class, lyricsTypeClass);
            decryptMethod.setAccessible(true);

            hook(decryptMethod, new AbsHook() {
                @Override
                public void after() {
                    try {
                        Object result = getResult();
                        if (!(result instanceof String lyricText)) {
                            return;
                        }
                        processRawLyricText(lyricText, "b0.m");
                    } catch (Throwable t) {
                        AndroidLog.logW(TAG, "Error in b0.m hook: " + t.getMessage());
                    }
                }
            });
            AndroidLog.logI(TAG, "Hooked Kuwo(phone) LRCX decryptor: " + CLS_LYRICS_DECODER + "#m");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            AndroidLog.logW(TAG, "Kuwo(phone) LRCX decryptor not found, skip: " + e.getMessage());
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to hook Kuwo(phone) LRCX decryptor", t);
        }
    }

    /**
     * 核心：接收宿主解密完成的原生报文，交由模块原生解析器自解析并调度上报。
     *
     * @param rawText 宿主返回的原始歌词明文
     * @param source  数据来源标记，便于日志定位
     */
    private void processRawLyricText(@NonNull String rawText, @NonNull String source) {
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        // 仅接受具备时间轴特征的歌词报文，过滤宿主可能返回的提示串 / 空报文 / 错误码。
        if (!trimmed.startsWith("[")) {
            return;
        }
        // 报文指纹去重，避免同一份歌词在多次解密回调中反复解析与上报。
        String signature = trimmed.length() + ":" + trimmed.substring(0, Math.min(96, trimmed.length()));
        if (Objects.equals(mLastLrcxSignature.get(), signature)) {
            return;
        }

        try {
            TrackContext active = mActiveTrack.get();
            String title = active != null ? active.getTitle() : "";
            String artist = active != null ? active.getArtist() : "";
            String album = active != null ? active.getAlbum() : "";
            long duration = active != null ? active.getDuration() : 0L;

            SuperLyricData parsedData = KuwoLrcxParser.parseLrcx(trimmed, title, artist, album, duration);
            if (parsedData == null || !parsedData.hasAllLyrics()) {
                AndroidLog.logW(TAG, "[" + source + "] Failed to parse raw LRCX, lines=0");
                if (active != null && mOrchestrator != null) {
                    mOrchestrator.onHookDeterminedInvalid(active);
                }
                return;
            }

            SuperLyricData cleanData = LyricSanitizer.sanitizeData(parsedData);
            if (cleanData == null) {
                return;
            }
            mLastLrcxSignature.set(signature);

            // 元数据兜底：报文内 [ti:]/[ar:]/[al:] 已由解析器回填，此处再与 ActiveTrack 合并一次。
            if (cleanData.getTitle() == null || cleanData.getTitle().isEmpty()) {
                cleanData.setTitle(title);
            }
            if (cleanData.getArtist() == null || cleanData.getArtist().isEmpty()) {
                cleanData.setArtist(artist);
            }
            if (cleanData.getAlbum() == null || cleanData.getAlbum().isEmpty()) {
                cleanData.setAlbum(album);
            }

            String trackId = (active != null && !active.getTrackId().isEmpty())
                ? active.getTrackId()
                : (cleanData.getTitle() + "_" + cleanData.getArtist());
            cleanData.setLyricId(trackId);

            long gen = (active != null && Objects.equals(active.getTrackId(), trackId))
                ? active.getGeneration()
                : mTrackGeneration.incrementAndGet();
            TrackContext context = new TrackContext(gen, trackId,
                cleanData.getTitle(), cleanData.getArtist(), cleanData.getAlbum(), duration);
            mActiveTrack.set(context);

            if (mOrchestrator != null) {
                mOrchestrator.onTrackChanged(context);
                mOrchestrator.onHookFullLyricCaptured(context, cleanData);
            }
            AndroidLog.logI(TAG, "[" + source + "] Captured LRCX for: " + cleanData.getTitle()
                + " - " + cleanData.getArtist() + " (lines=" + cleanData.getAllLyrics().length + ")");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "[" + source + "] Error processing raw LRCX text", t);
        }
    }
}
