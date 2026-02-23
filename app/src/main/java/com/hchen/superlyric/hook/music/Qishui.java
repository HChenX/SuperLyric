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

import com.hchen.collect.Collect;
import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.helper.FieldHelper;
import com.hchen.hooktool.hook.IHook;
import com.hchen.superlyric.hook.LyricRelease;
import com.hchen.superlyricapi.SuperLyricData;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 汽水音乐
 *
 * @author 焕晨HChen
 */
@Collect(targetPackage = "com.luna.music")
public class Qishui extends LyricRelease {

    @Override
    protected void init() {
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
        Field e = new FieldHelper(findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricViewModel"))
            .withFieldClass(findClass("kotlin.Pair"))
            .single();
        // 此字段存储当前播放歌词的索引位置
        Field g = new FieldHelper(findClass("com.luna.biz.playing.lyric.bluetoothlyrics.BlueToothLyricViewModel"))
            .withFieldClass(Integer.class)
            .single();

        hook(m3, new IHook() {
            private int lastIndex = -1;

            @Override
            public void after() {
                Integer index = (Integer) getThisField(g);
                if (index != null) {
                    if (lastIndex == -1 || index != lastIndex) {
                        lastIndex = index;
                        Object pair = getThisField(e);
                        List<?> second = (List<?>) callMethod(pair, "getSecond");
                        Object sentence = second.get(index);

                        CharSequence lyric = (CharSequence) callMethod(sentence, "getContent");
                        long startTime = (long) callMethod(sentence, "getStartTimeMs");
                        long endTime = (long) callMethod(sentence, "getEndTimeMs");

                        List<?> wordList = (List<?>) callMethod(sentence, "wordList");
                        SuperLyricData.EnhancedLRCData[] data = new SuperLyricData.EnhancedLRCData[wordList.size()];
                        for (int i = 0; i < wordList.size(); i++) {
                            CharSequence content = (CharSequence) getField(wordList.get(i), "content");
                            long startTimeMs = (long) getField(wordList.get(i), "startTimeMs");
                            long endTimeMs = (long) getField(wordList.get(i), "endTimeMs");

                            data[i] = new SuperLyricData.EnhancedLRCData((String) content, (int) startTimeMs, (int) endTimeMs);
                        }

                        sendLyric((String) lyric, (int) (endTime - startTime), data);
                        // logD(TAG, sentence.toString());
                    }
                }
            }
        });
    }
}
