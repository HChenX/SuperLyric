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
package com.hchen.superlyric.hook.music.offline;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyric.patches.netease.NeteaseLogicHijacking;
import com.hchen.superlyric.utils.MeizuFaker;
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

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 网易云音乐。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.netease.cloudmusic")
public final class Netease extends AbsPublisher {
    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        NeteaseLogicHijacking.bypassPackProtection();
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        MeizuFaker.shallowLayerDeviceMock();

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

                        // 快速切歌 / 清空瞬间索引可能越界：跳过本轮，状态栏自行负责清空显示
                        if (mSentences == null || mCurLyricIndex < 0 || mCurLyricIndex >= mSentences.size()) {
                            logD(TAG, "Offline lyric state not ready, skip: index=" + mCurLyricIndex);
                            return;
                        }

                        Object mSentence = mSentences.get(mCurLyricIndex);
                        String lyric = (String) callMethod(mSentence, "getContent");
                        String translate = (String) callMethod(mSentence, "getTranslateContent");
                        if (lyric == null || lyric.isEmpty()) {
                            logD(TAG, "Offline lyric empty, skip");
                            return;
                        }
                        int endTime = (int) callMethod(mSentence, "getEndTime");
                        int startTime = (int) callMethod(mSentence, "getStartTime");

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
                                .setTranslation(new SuperLyricLine(translate))
                        );
                    }
                }
            );

            NeteaseLogicHijacking.hookLockScreenPermission();
        } catch (Throwable throwable) {
            logW(TAG, "Status bar lyric hook setup failed, notification lyric fallback will be used", throwable);
            MeizuFaker.hookNotificationLyric();
        }
    }
}
