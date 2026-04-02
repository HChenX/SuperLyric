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

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 汽水音乐
 *
 * @author 焕晨HChen
 */
@AutoHook(targetPackage = "com.luna.music")
public final class Qishui extends AbsPublisher {

    @Override
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        fakeBluetoothA2dpEnabled();

        // 此方法判断外接设备是否为蓝牙
        Method m = DexkitCache.findMember("qishui$1", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricsManager"))
                        .paramCount(1)
                        .returnType(boolean.class)
                    )
                ).single();
            }
        });
        hook(m, returnResult(true));

        Method m1 = DexkitCache.findMember("qishui$2", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(findClass("com.luna.common.arch.device.OutputDevice"))
                        .paramCount(0)
                        .returnType(boolean.class)
                    )
                ).single();
            }
        });
        hook(m1, returnResult(true));

        // 阻止触发蓝牙断连逻辑，防止终止蓝牙歌词事件
        Method m2 = DexkitCache.findMember("qishui$3", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricsManager"))
                        .usingNumbers(13)
                    )
                ).single();
            }
        });
        hook(m2, doNothing());

        // 发布蓝牙歌词信息的方法
        Method m3 = DexkitCache.findMember("qishui$4", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricViewModel"))
                        .paramTypes(findClass("com.luna.common.arch.playable.TrackPlayable"), long.class)
                        .returnType(void.class)
                    )
                ).single();
            }
        });
        // 此字段存储歌词信息
        Field e = Arrays.stream(findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricViewModel").getDeclaredFields())
            .filter(new Predicate<Field>() {
                final Class<?> c = findClass("kotlin.Pair");

                @Override
                public boolean test(Field field) {
                    return Objects.equals(field.getType(), c);
                }
            }).findFirst().orElseThrow();

        // 此字段存储当前播放歌词的索引位置
        Field g = Arrays.stream(findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricViewModel").getDeclaredFields())
            .filter(new Predicate<Field>() {
                @Override
                public boolean test(Field field) {
                    return Objects.equals(field.getType(), Integer.class);
                }
            }).findFirst().orElseThrow();

        hook(m3, new AbsHook() {
            private int lastIndex = -1;

            @Override
            public void after() {
                Integer index = (Integer) getField(g, getThisObject());
                if (index != null) {
                    if (lastIndex == -1 || index != lastIndex) {
                        lastIndex = index;
                        Object pair = getField(e, getThisObject());
                        List<?> second = (List<?>) callMethod(pair, "getSecond");
                        Object sentence = second.get(index);

                        LyricData lyricData = create(sentence);
                        LyricData translationLyricData = null;

                        Map<?, ?> translationMap = (Map<?, ?>) callMethod(sentence, "getTranslationMap");
                        if (translationMap != null) {
                            Object CHINESE = getStaticField("com.luna.common.arch.db.entity.lyrics.NetLyricsLanguage", "CHINESE");
                            if (translationMap.containsKey(CHINESE)) {
                                Object translationSentence = translationMap.get(CHINESE);
                                if (translationSentence != null) {
                                    translationLyricData = create(translationSentence);
                                }
                            }
                        }

                        SuperLyricData superLyricData = new SuperLyricData().setLyricWordData(lyricData.words);
                        if (translationLyricData != null) {
                            superLyricData.setTranslation((String) translationLyricData.lyric)
                                .setTranslationDelay((int) (translationLyricData.endTime - translationLyricData.startTime))
                                .setTranslationWordData(translationLyricData.words);
                        }

                        sendLyric(
                            (String) lyricData.lyric,
                            (int) (lyricData.endTime - lyricData.startTime),
                            superLyricData
                        );
                        // AndroidLog.logI(TAG, sentence.toString());
                    }
                }
            }
        });
    }

    private LyricData create(Object sentence) {
        CharSequence lyric = (CharSequence) callMethod(sentence, "getContent");
        long startTime = (long) callMethod(sentence, "getStartTimeMs");
        long endTime = (long) callMethod(sentence, "getEndTimeMs");

        SuperLyricWord[] words = null;
        List<?> wordList = (List<?>) callMethod(sentence, "getWordList");
        if (wordList != null) {
            words = new SuperLyricWord[wordList.size()];
            for (int i = 0; i < wordList.size(); i++) {
                CharSequence content = (CharSequence) getField(wordList.get(i), "content");
                long startTimeMs = (long) getField(wordList.get(i), "startTimeMs");
                long endTimeMs = (long) getField(wordList.get(i), "endTimeMs");

                words[i] = new SuperLyricWord((String) content, (int) startTimeMs, (int) endTimeMs);
            }
        }

        return new LyricData(lyric, startTime, endTime, words);
    }

    private static class LyricData {
        CharSequence lyric;
        long startTime;
        long endTime;
        SuperLyricWord[] words;

        public LyricData(CharSequence lyric, long startTime, long endTime, SuperLyricWord[] words) {
            this.lyric = lyric;
            this.startTime = startTime;
            this.endTime = endTime;
            this.words = words;
        }
    }
}

// Sentence(
// type=ORIGIN,
// content=輝き放っている,
// startTimeMs=51130,
// endTimeMs=54920,
// wordList=[
// Word(content=輝, startTimeMs=51130, endTimeMs=52120),
// Word(content=き, startTimeMs=52120, endTimeMs=52520),
// Word(content=放, startTimeMs=52520, endTimeMs=52730),
// Word(content=っ, startTimeMs=52730, endTimeMs=52990),
// Word(content=て, startTimeMs=52990, endTimeMs=53310),
// Word(content=い, startTimeMs=53310, endTimeMs=53590),
// Word(content=る, startTimeMs=53590, endTimeMs=54920)
// ],
// translationMap={
// CHINESE=Sentence(
// type=ORIGIN,
// content=令人骄傲的光芒,
// startTimeMs=51130,
// endTimeMs=55670,
// wordList=[
// Word(content=令, startTimeMs=51130, endTimeMs=51671),
// Word(content=人, startTimeMs=51671, endTimeMs=52212),
// Word(content=骄, startTimeMs=52212, endTimeMs=52754),
// Word(content=傲, startTimeMs=52754, endTimeMs=53295),
// Word(content=的, startTimeMs=53295, endTimeMs=53837),
// Word(content=光, startTimeMs=53837, endTimeMs=54378),
// Word(content=芒, startTimeMs=54378, endTimeMs=54920)],
// translationMap=null)
// }
// )
