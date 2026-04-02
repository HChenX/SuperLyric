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

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 椒盐音乐
 */
@AutoHook(targetPackage = "com.salt.music")
public final class SaltMusic extends AbsPublisher {
    @Override
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        Class<?> lyricDataClass = DexkitCache.findMember("salt$1", new IDexkit<ClassData>() {
            @NonNull @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings("LyricsLine(startTime=")
                    )
                ).single();
            }
        });
        Field lyricListField = null; // 用于存储逐字等信息
        ArrayList<Field> fields = new ArrayList<>(); // 用于存储 歌词 或者 翻译
        for (Field declaredField : lyricDataClass.getDeclaredFields()) {
            if (declaredField.getType().equals(List.class)) {
                lyricListField = declaredField;
            }
            if (declaredField.getType().equals(String.class)) {
                fields.add(declaredField);
            }
        }

        Objects.requireNonNull(lyricListField, "Lyric list field must not be null.");
        if (fields.size() != 2) {
            throw new RuntimeException("Lyric data string field can only be two.");
        }

        Class<?> lyricsCellClass = DexkitCache.findMember("salt$2", new IDexkit<ClassData>() {
            @NonNull @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings("LyricsCell(startTime=")
                    )
                ).single();
            }
        });
        Field lyricField = null; // 储存歌词文本
        ArrayList<Field> timesFileds = new ArrayList<>(); // 储存 开始时间 和 结束时间
        for (Field declaredField : lyricsCellClass.getDeclaredFields()) {
            if (declaredField.getType().equals(long.class)) {
                timesFileds.add(declaredField);
            }
            if (declaredField.getType().equals(String.class)) {
                lyricField = declaredField;
            }
        }

        Objects.requireNonNull(lyricField, "Lyric field must not be null.");
        if (timesFileds.size() != 2) {
            throw new RuntimeException("Time field can only be two.");
        }

        Field finalLyricField = lyricField;
        Field finalLyricListField = lyricListField;
        Method method = DexkitCache.findMember("salt$3", new IDexkit<MethodData>() {
            @NonNull @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create()
                            .usingEqStrings("EmojiCompat.FontRequestEmojiCompatConfig.buildTypeface")
                        )
                        .usingEqStrings("EmojiCompat.FontRequestEmojiCompatConfig.buildTypeface")
                    )
                ).single();
            }
        });
        Field typeField = Arrays.stream(method.getDeclaringClass().getDeclaredFields())
            .filter(new Predicate<Field>() {
                @Override
                public boolean test(Field field) {
                    return Objects.equals(field.getType(), int.class);
                }
            }).findFirst().orElseThrow();

        Field field = Arrays.stream(method.getDeclaringClass().getDeclaredFields())
            .filter(new Predicate<Field>() {
                @Override
                public boolean test(Field field) {
                    return Objects.equals(field.getType(), Object.class);
                }
            }).findFirst().orElseThrow();

        Objects.requireNonNull(typeField);
        Objects.requireNonNull(field);

        hook(method, new AbsHook() {
                @Override
                public void before() {
                    if (timesFileds.isEmpty() || fields.isEmpty()) {
                        return;
                    }

                    int type = (int) getField(typeField, getThisObject());
                    if (type == 20) {
                        Object lyricData = getField(field, getThisObject());
                        List<?> lyrics = (List<?>) getField(finalLyricListField, lyricData);
                        if (lyrics != null) {
                            List<LyricData> data = new ArrayList<>();
                            for (Object l : lyrics) {
                                long[] times = new long[2];
                                long startTime;
                                long endTime;
                                String lyric;

                                for (int i = 0; i < timesFileds.size(); i++) {
                                    times[i] = (long) getField(timesFileds.get(i), l);
                                }
                                startTime = Math.min(times[0], times[1]);
                                endTime = Math.max(times[0], times[1]);
                                lyric = (String) getField(finalLyricField, l);

                                data.add(new LyricData(startTime, endTime, lyric));
                            }

                            long delay = 0L;
                            StringBuilder sb = new StringBuilder();
                            SuperLyricWord[] words = new SuperLyricWord[data.size()];
                            for (int i = 0; i < data.size(); i++) {
                                delay = delay + (data.get(i).endTime - data.get(i).startTime);
                                sb.append(data.get(i).lyric);
                                words[i] = new SuperLyricWord(
                                    data.get(i).lyric,
                                    (int) data.get(i).startTime,
                                    (int) data.get(i).endTime
                                );
                            }

                            String[] strings = new String[2];
                            for (int i = 0; i < fields.size(); i++) {
                                strings[i] = (String) getField(fields.get(i), lyricData);
                            }
                            String translation;
                            if (strings[0] == null || strings[1] == null) {
                                translation = null;
                            } else {
                                translation = TextUtils.equals(strings[0], sb.toString()) ? strings[1] : strings[0];
                            }

                            sendLyric(
                                sb.toString(),
                                (int) delay,
                                new SuperLyricData()
                                    .setLyricWordData(words)
                                    .setTranslation(translation)
                            );

                            AndroidLog.logI(TAG, "LyricData: " + data);
                        }
                    }
                }
            }
        );
    }

    private record LyricData(long startTime, long endTime, String lyric) {
        @Override
        @NonNull
        public String toString() {
            return "LyricData{" +
                "startTime=" + startTime +
                ", endTime=" + endTime +
                ", lyric='" + lyric + '\'' +
                '}';
        }

        @Override
        public boolean equals(Object o) {
            // noinspection DeconstructionCanBeUsed
            if (!(o instanceof LyricData lyricData)) return false;
            return startTime == lyricData.startTime && endTime == lyricData.endTime && lyric.equals(lyricData.lyric);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(startTime);
            result = 31 * result + Long.hashCode(endTime);
            result = 31 * result + lyric.hashCode();
            return result;
        }
    }
}
