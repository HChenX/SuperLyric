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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.processor.HookThis;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyric.utils.BluetoothFaker;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 汽水音乐
 *
 * @author 焕晨HChen
 * @author baka
 */
@HookThis(targetPackage = "com.luna.music")
public final class Qishui extends AbsPublisher {
    // Sentence / Word 的成员在 20.0 起被混淆（getContent -> a()、content -> a 等），
    // 故按字段类型 + 声明顺序运行时发现，避免每次版本更新都要跟着改名
    private final Map<Class<?>, Accessor> mAccessors = new ConcurrentHashMap<>();

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        BluetoothFaker.fakeBluetoothA2dpEnabled();

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
        Method[] m2 = DexkitCache.findMember("qishui$3new", new IDexkit<MethodDataList>() {
            @NonNull
            @Override
            public MethodDataList dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricsManager"))
                        .paramTypes(findClass("com.luna.common.arch.device.OutputDevice"))
                    )
                );
            }
        });
        hookAll(m2, doNothing());

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
            private Object lastPair;
            private Object lastTrackPlayable;

            @Override
            public void after() {
                Integer index = (Integer) getField(g, getThisObject());
                if (index == null) return;

                Object pair = getField(e, getThisObject());
                if (pair == null) return;
                List<?> second = (List<?>) callMethod(pair, "getSecond");
                // 索引由宿主用 getOrNull 取值，可能越界
                if (second == null || index < 0 || index >= second.size()) return;

                Object trackPlayable = getArg(0);
                if (lastPair == pair && lastTrackPlayable == trackPlayable && lastIndex == index) return;

                Object sentence = second.get(index);
                LyricData lyricData = create(sentence);
                if (lyricData == null) return;
                LyricData translationLyricData = null;

                Map<?, ?> translationMap = readTranslationMap(sentence);
                if (translationMap != null) {
                    Object CHINESE = getStaticField("com.luna.common.arch.db.entity.lyrics.NetLyricsLanguage", "CHINESE");
                    if (translationMap.containsKey(CHINESE)) {
                        Object translationSentence = translationMap.get(CHINESE);
                        if (translationSentence != null) {
                            translationLyricData = create(translationSentence);
                        }
                    }
                }

                String name = null;
                String album = null;
                String artist = null;
                try {
                    Object track = getField(trackPlayable, "track");
                    name = (String) callMethod(track, "getName");
                    album = (String) callMethod(callMethod(track, "getAlbum"), "getName");
                    List<?> artists = (List<?>) callMethod(track, "getArtists");

                    StringBuilder sb = new StringBuilder();
                    if (artists != null) {
                        for (int i = 0; i < artists.size(); i++) {
                            sb.append(callMethod(artists.get(i), "getName"));
                            if (artists.size() - 1 != i) {
                                sb.append("-");
                            }
                        }
                    }
                    artist = sb.toString();
                } catch (Throwable ignore) {
                }

                SuperLyricData superLyricData = new SuperLyricData();
                superLyricData.setTitle(name);
                superLyricData.setArtist(artist);
                superLyricData.setAlbum(album);

                superLyricData.setLyric(
                    new SuperLyricLine(
                        lyricData.lyric,
                        lyricData.words,
                        lyricData.startTime,
                        lyricData.endTime
                    )
                );

                if (translationLyricData != null) {
                    superLyricData.setTranslation(new SuperLyricLine(
                        translationLyricData.lyric,
                        translationLyricData.words,
                        translationLyricData.startTime,
                        translationLyricData.endTime
                    ));
                }

                sendLyric(superLyricData);
                lastPair = pair;
                lastTrackPlayable = trackPlayable;
                lastIndex = index;
            }
        });
    }

    /**
     * 读取 Sentence 的翻译表：key 为 NetLyricsLanguage，value 为对应语言的 Sentence
     */
    @Nullable
    private Map<?, ?> readTranslationMap(@NonNull Object sentence) {
        Accessor accessor = accessorOf(sentence);
        if (accessor.translation == null) return null;
        return (Map<?, ?>) getField(accessor.translation, sentence);
    }

    @Nullable
    private LyricData create(@Nullable Object sentence) {
        if (sentence == null) return null;

        Accessor accessor = accessorOf(sentence);
        if (!accessor.isTimedText()) return null;

        String lyric = toText(getField(accessor.content, sentence));
        long startTime = (long) getField(accessor.startTime, sentence);
        long endTime = (long) getField(accessor.endTime, sentence);

        SuperLyricWord[] words = null;
        List<?> wordList = accessor.wordList == null ? null : (List<?>) getField(accessor.wordList, sentence);
        if (wordList != null && !wordList.isEmpty()) {
            Accessor wordAccessor = accessorOf(wordList.get(0));
            if (wordAccessor.isTimedText()) {
                words = new SuperLyricWord[wordList.size()];
                for (int i = 0; i < wordList.size(); i++) {
                    Object word = wordList.get(i);
                    String content = toText(getField(wordAccessor.content, word));
                    long startTimeMs = (long) getField(wordAccessor.startTime, word);
                    long endTimeMs = (long) getField(wordAccessor.endTime, word);

                    words[i] = new SuperLyricWord(content, startTimeMs, endTimeMs);
                }
            }
        }

        return new LyricData(lyric, startTime, endTime, words);
    }

    /**
     * 按字段类型 + 声明顺序发现 Sentence / Word 的混淆成员，按类缓存。
     * <p>
     * 两者形状一致：一个 CharSequence 文本 + 两个 long 时间戳，Sentence 另有
     * List(逐字) 与 Map(翻译)。混淆后字段名为 a/b/c...，dex 按名排序后的声明顺序
     * 即 startTimeMs -> endTimeMs；若日后不再混淆则会反过来，故用首个实例的取值纠正。
     */
    @NonNull
    private Accessor accessorOf(@NonNull Object sample) {
        Accessor cached = mAccessors.get(sample.getClass());
        if (cached != null) return cached;

        Accessor accessor = new Accessor();
        List<Field> times = new ArrayList<>(2);
        Field[] fields = sample.getClass().getDeclaredFields();
        Arrays.sort(fields, (a, b) -> a.getName().compareTo(b.getName()));
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);

            Class<?> type = field.getType();
            if (accessor.content == null && CharSequence.class.isAssignableFrom(type))
                accessor.content = field;
            else if (type == long.class)
                times.add(field);
            else if (accessor.wordList == null && List.class.isAssignableFrom(type))
                accessor.wordList = field;
            else if (accessor.translation == null && Map.class.isAssignableFrom(type))
                accessor.translation = field;
        }

        if (times.size() >= 2) {
            Field start = times.get(0);
            Field end = times.get(1);
            if (readTimeRaw(start, sample) > readTimeRaw(end, sample)) {
                Field temp = start;
                start = end;
                end = temp;
            }
            accessor.startTime = start;
            accessor.endTime = end;
        }

        mAccessors.put(sample.getClass(), accessor);
        return accessor;
    }

    /**
     * 读取 long 字段值，失败返回 0
     */
    private long readTimeRaw(@NonNull Field field, @NonNull Object obj) {
        try {
            return field.getLong(obj);
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    @Nullable
    private String toText(@Nullable Object content) {
        return content == null ? null : content.toString();
    }

    private static class Accessor {
        Field content;     // CharSequence
        Field startTime;   // long
        Field endTime;     // long
        Field wordList;    // List<Word>，仅 Sentence 有
        Field translation; // Map<NetLyricsLanguage, Sentence>，仅 Sentence 有

        boolean isTimedText() {
            return content != null && startTime != null && endTime != null;
        }
    }

    private static class LyricData {
        String lyric;
        long startTime;
        long endTime;
        SuperLyricWord[] words;

        public LyricData(String lyric, long startTime, long endTime, SuperLyricWord[] words) {
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
