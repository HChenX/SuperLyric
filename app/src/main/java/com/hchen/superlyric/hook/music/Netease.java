/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.hook.music;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.helper.MeizuHelper;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 网易云音乐（网络优先 + 状态栏兜底双路径）。
 * <p>
 * 网络路径由共享基类 {@link NeteaseNetworkPublisher} 提供（ticket 05 逻辑整体上移，
 * 供荣耀音乐复用）；本类保留 ticket 06 双轨互斥兜底（由纯状态机
 * {@link LyricSourceMachine} 决策）：两条路径同时 hook；
 * 切歌时缓存未命中且网络请求失败（断网/超时/接口异常）→ 按歌曲粒度启用魅族状态栏兜底，
 * 只发单行原文；下次切歌重新尝试网络路径。兜底活跃时网络路径不发布，反之亦然，
 * 播放中不切换、不横跳。兜底时增强功能缺失为预期降级。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.netease.cloudmusic")
public final class Netease extends NeteaseNetworkPublisher {
    // 主兜底 hook（状态栏 onLyricText）是否安装成功；失败时以通知栏兜底替代
    private volatile boolean mStatusBarFallbackReady = false;
    private volatile boolean mNotificationFallbackInstalled = false;

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);

        // 魅族模拟保持启用：供兜底路径随时可收到 onLyricText（两条路径同时 hook）
        MeizuHelper.shallowLayerDeviceMock();
        setupStatusBarFallback();
    }

    // ------------------------------ 状态栏兜底（双轨互斥） ------------------------------

    private void setupStatusBarFallback() {
        try {
            Method musicInfoMethod = DexkitCache.findMember("music_info", new IDexkit<MethodData>() {
                @NonNull
                @Override
                public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create()
                                .modifiers(Modifier.FINAL)
                                .usingEqStrings("getPlayingMusicInfo")
                                .superClass("java.lang.Object")
                            )
                            .usingEqStrings("getPlayingMusicInfo")
                        )
                    ).single();
                }
            });
            Object p = getStaticField(
                Arrays.stream(musicInfoMethod.getDeclaringClass().getDeclaredFields())
                    .filter(new Predicate<Field>() {
                        @Override
                        public boolean test(Field field) {
                            return Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers());
                        }
                    }).findFirst().orElseThrow()
            );

            Class<?> statusBarLyricController = DexkitCache.findMember("status_bar_lyric", new IDexkit<ClassData>() {
                @NonNull
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("StatusBarLyricController")
                        )
                    ).single();
                }
            });
            Method lyricMethod = null;
            for (Field declaredField : statusBarLyricController.getDeclaredFields()) {
                try {
                    lyricMethod = declaredField.getType().getDeclaredMethod("onLyricText", String.class, String.class);
                    break;
                } catch (NoSuchMethodException ignore) {
                }
            }

            Objects.requireNonNull(lyricMethod);
            hook(lyricMethod,
                new AbsHook() {
                    @Override
                    public void before() {
                        // 互斥发布：网络路径活跃时兜底不发布
                        if (!isFallbackActive()) return;

                        try {
                            String name = null;
                            String artists = null;
                            String album = null;

                            if (p != null) {
                                Object musicInfo = callMethod(musicInfoMethod, p);
                                if (musicInfo != null) {
                                    name = (String) callMethod(musicInfo, "getName");
                                    artists = (String) callMethod(musicInfo, "getArtistsName");
                                    album = (String) callMethod(musicInfo, "getAlbumName");
                                }
                            }

                            List<?> mSentences = (List<?>) getField(getThisObject(), "mSentences");
                            int mCurLyricIndex = (int) getField(getThisObject(), "mCurLyricIndex");

                            // 快速切歌 / 清空瞬间索引可能越界：跳过本轮，网络路径已负责清空显示
                            if (mSentences == null || mCurLyricIndex < 0 || mCurLyricIndex >= mSentences.size()) {
                                logD(TAG, "Fallback lyric state not ready, skip: index=" + mCurLyricIndex);
                                return;
                            }

                            Object mSentence = mSentences.get(mCurLyricIndex);
                            String lyric = (String) callMethod(mSentence, "getContent");
                            if (TextUtils.isEmpty(lyric)) {
                                logD(TAG, "Fallback lyric empty, skip");
                                return;
                            }
                            int endTime = (int) callMethod(mSentence, "getEndTime");
                            int startTime = (int) callMethod(mSentence, "getStartTime");

                            // 兜底只发单行原文：无翻译/音译/逐字/设置联动（预期降级）
                            sendLyric(
                                new SuperLyricData()
                                    .setTitle(name)
                                    .setArtist(artists)
                                    .setAlbum(album)
                                    .setLyric(
                                        new SuperLyricLine(
                                            lyric,
                                            startTime,
                                            endTime
                                        )
                                    )
                            );
                        } catch (Throwable t) {
                            logW(TAG, "Fallback lyric publish skipped due to unexpected state", t);
                        }
                    }
                }
            );
            mStatusBarFallbackReady = true;
        } catch (Throwable throwable) {
            // 通知栏兜底保持安装但不发布：仅兜底活跃时开启（互斥）
            MeizuHelper.setNotificationLyricEnabled(false);
            // 状态栏兜底未就绪：以通知栏兜底替代（保持安装、默认不发布，互斥由状态机控制）
            logW(TAG, "Status bar fallback hook setup failed, notification lyric fallback will be used when needed", throwable);
            if (!mNotificationFallbackInstalled) {
                try {
                    MeizuHelper.hookNotificationLyric();
                    mNotificationFallbackInstalled = true;
                } catch (Throwable t) {
                    logW(TAG, "Notification lyric fallback hook also failed", t);
                }
            }
        }
    }

    // ------------------------------ 双轨互斥状态机（按歌曲粒度） ------------------------------

    @Override
    protected boolean fallbackSupported() {
        return true;
    }

    @Override
    protected void onFallbackActive(long id) {
        // 仅当兜底来源所属歌曲仍是当前歌曲时生效（切歌竞态防护）
        SongInfo song = currentSong();
        if (song == null || song.id != id) {
            logD(TAG, "Ignore fallback for stale track " + id);
            return;
        }

        if (!mStatusBarFallbackReady && !mNotificationFallbackInstalled) {
            try {
                MeizuHelper.hookNotificationLyric();
                mNotificationFallbackInstalled = true;
            } catch (Throwable t) {
                logW(TAG, "Notification lyric fallback hook failed at fallback enable", t);
            }
        }
        // 通知栏兜底仅在状态栏兜底未就绪时启用，避免两条兜底同时发布
        MeizuHelper.setNotificationLyricEnabled(!mStatusBarFallbackReady);
    }

    @Override
    protected void onFallbackInactive() {
        MeizuHelper.setNotificationLyricEnabled(false);
    }
}
