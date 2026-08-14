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
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 波点音乐
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "cn.wenyu.bodian")
public final class Bodian extends AbsPublisher {
    private static class LineData {
        String text;
        String translation;
        int startTime;
        int endTime;
        SuperLyricWord[] words;
        SuperLyricWord[] translationWords;

        @NonNull
        @Override
        public String toString() {
            return "LineData{" +
                "text='" + text + '\'' +
                ", translation='" + translation + '\'' +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", words=" + (words != null ? words.length : 0) +
                ", translationWords=" + (translationWords != null ? translationWords.length : 0) +
                '}';
        }
    }

    // 缓存字段
    private final List<LineData> mCachedLines = new CopyOnWriteArrayList<>();
    private String mCachedTitle;
    private String mCachedArtist;
    private String mCachedAlbum;
    private int mCachedDuration = -1;
    private int mCurrentPosition;
    private String mCachedRid;
    private LineData mLastSentLine;
    private String mLastSentFallbackText;
    private String mLastSentTitle;
    private String mLastSentArtist;
    private String mLastSentAlbum;

    // 运行时发现的混淆字段（e.g/e$b）
    private Field mLineTimestampField;   // e$b: Integer 时间戳
    private Field mLineTextField;        // e$b: String 歌词文本
    private Field mLineIsTransField;     // e$b: boolean isTranslation
    private Field mLineSubElementsField; // e$g: List<e.h> 逐字子元素
    private boolean mLineFieldsDiscovered;
    private int mLineFieldRetryCount;

    // 运行时发现的混淆字段（e.h）
    // e.h 有 5 个 int: a(字符偏移起始), b(字符偏移结束), c(索引), d(开始ms), e(结束ms)
    private Field mSubCharStartField;    // e.h: int 字符偏移起始
    private Field mSubCharEndField;      // e.h: int 字符偏移结束
    private Field mSubStartTimeField;    // e.h: int 开始时间 ms
    private Field mSubEndTimeField;      // e.h: int 结束时间 ms

    private boolean mSubFieldsDiscovered;
    private int mSubFieldRetryCount;

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);

        Class<?> deskLyricViewClass = findClass("cn.kuwo.player.util.DeskLyricView");
        Method methodData = DexkitCache.findMember("bodian$1", new IDexkit<MethodData>() {
            @NonNull
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(deskLyricViewClass)
                        .paramCount(1)
                        .paramTypes(String.class)
                        .returnType(float.class)
                        .addInvoke("Landroid/graphics/Paint;->measureText(Ljava/lang/String;)F")
                    )
                ).single();
            }
        });
        hook(methodData,
            new AbsHook() {
                @Override
                public void before() {
                    String lyric = (String) getArg(0);
                    if (lyric == null || lyric.trim().isEmpty()) {
                        clearLyricCache();
                        sendStop();
                        return;
                    }

                    // 按当前播放位置匹配歌词行
                    LineData matchedLine = findLineByPosition(mCurrentPosition);
                    if (matchedLine != null && !Objects.equals(matchedLine.text, lyric)) {
                        matchedLine = null;
                    }

                    if (matchedLine == mLastSentLine
                        && Objects.equals(mLastSentFallbackText, lyric)
                        && Objects.equals(mLastSentTitle, mCachedTitle)
                        && Objects.equals(mLastSentArtist, mCachedArtist)
                        && Objects.equals(mLastSentAlbum, mCachedAlbum)) {
                        return;
                    }

                    SuperLyricData data = new SuperLyricData();
                    data.setTitle(mCachedTitle);
                    data.setArtist(mCachedArtist);
                    data.setAlbum(mCachedAlbum);

                    if (matchedLine != null) {
                        data.setLyric(
                            new SuperLyricLine(lyric, matchedLine.words, matchedLine.startTime, matchedLine.endTime)
                        );
                        if (matchedLine.translation != null) {
                            data.setTranslation(
                                new SuperLyricLine(matchedLine.translation, matchedLine.translationWords,
                                    matchedLine.startTime, matchedLine.endTime)
                            );
                        }
                    } else {
                        data.setLyric(new SuperLyricLine(lyric));
                    }

                    sendLyric(data);
                    mLastSentLine = matchedLine;
                    mLastSentFallbackText = lyric;
                    mLastSentTitle = mCachedTitle;
                    mLastSentArtist = mCachedArtist;
                    mLastSentAlbum = mCachedAlbum;
                }
            }
        );

        // Hook: 拦截 StatusBarLyricLayout.getMusic() 获取歌曲元数据
        hookMethod("cn.kuwo.audio_player.StatusBarLyricLayout",
            "getMusic",
            new AbsHook() {
                @Override
                public void after() {
                    Object music = getResult();
                    if (music == null) return;

                    // 检测切歌
                    String rid = (String) getField(music, "rid");
                    if (!Objects.equals(rid, mCachedRid)) {
                        mCachedRid = rid;
                        mCachedTitle = null;
                        mCachedArtist = null;
                        mCachedAlbum = null;
                        mCachedDuration = -1;
                        mCachedLines.clear();
                        clearLastSentData();
                        sendStop();
                    }

                    mCachedTitle = (String) callMethod(music, "getName");
                    mCachedArtist = (String) callMethod(music, "getArtist");
                    mCachedAlbum = (String) callMethod(music, "getAlbum");
                    mCachedDuration = (int) callMethod(music, "getDur");
                }
            }
        );

        // Hook: 拦截 LyricsMgr4FlutterImpl.g(e.a, c3.a, c3.a, boolean)
        // 歌词加载完成后会调用此方法，参数 1 为完整的 ILyrics 对象
        try {
            Method lyricLinesMethod = DexkitCache.findMember("bodian$2", new IDexkit<MethodData>() {
                @Nullable @Override
                public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    Class<?> c = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("加载歌曲歌词:")
                        )
                    ).single().getInstance(ModuleData.getClassLoader());
                    String p = c.getName();
                    AndroidLog.logD(TAG, "class name: " + p);
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(findClass(p.substring(0, p.indexOf("$"))))
                            .paramCount(4)
                        )
                    ).single();
                }
            });
            hook(lyricLinesMethod, new AbsHook() {
                @Override
                public void after() {
                    Object iLyrics = getArg(1);
                    if (iLyrics != null) {
                        List<?> lines = (List<?>) callMethod(iLyrics, "g");
                        if (lines != null && !lines.isEmpty())
                            cacheLyricLines(lines);
                    }
                }
            });
        } catch (Throwable e) {
            logW(TAG, e);
        }

        // Hook: 拦截 DeskLyricView.getCurrentPos() 获取当前播放位置
        hookMethod("cn.kuwo.player.util.DeskLyricView",
            "getCurrentPos",
            new AbsHook() {
                @Override
                public void after() {
                    mCurrentPosition = (int) getResult();
                }
            }
        );

        hookMethod("io.flutter.plugin.common.MethodCall",
            "argument",
            String.class,
            new AbsHook() {
                @Override
                public void before() {
                    String key = (String) getArg(0);
                    if (Objects.equals(key, "isShow"))
                        setResult(true);
                }
            }
        );

        hookMethod("cn.kuwo.audio_player.StatusBarLyricLayout",
            "getLayoutBinding",
            new AbsHook() {
                @Override
                public void after() {
                    View view = (View) getThisObject();
                    view.setAlpha(0f);
                }
            }
        );
    }

    /**
     * 解析完整的歌词行数据，提取时间戳、逐字数据和翻译。
     */
    private void cacheLyricLines(List<?> rawLines) {
        if (rawLines.isEmpty()) return;

        // 首次运行：发现混淆字段（最多重试 3 次）
        if (!mLineFieldsDiscovered && mLineFieldRetryCount < 3) {
            discoverLineFields(rawLines.get(0));
            mLineFieldRetryCount++;
        }

        List<LineData> originals = new ArrayList<>();
        LineData pendingLine = null;

        for (Object raw : rawLines) {
            if (mLineTimestampField == null || mLineTextField == null
                || mLineIsTransField == null) continue;
            Integer timestamp = (Integer) getField(mLineTimestampField, raw);
            String text = (String) getField(mLineTextField, raw);
            Object isTranslationObj = getField(mLineIsTransField, raw);
            boolean isTranslation = isTranslationObj instanceof Boolean && (Boolean) isTranslationObj;

            if (text == null || timestamp == null) continue;

            // 提取逐字数据
            SuperLyricWord[] wordArray = null;
            Object subElements = mLineSubElementsField != null ? getField(mLineSubElementsField, raw) : null;
            if (subElements instanceof List<?> subList && !subList.isEmpty()) {
                if (!mSubFieldsDiscovered && mSubFieldRetryCount < 3) {
                    discoverSubElementFields(subList);
                    mSubFieldRetryCount++;
                }
                if (mSubStartTimeField != null && mSubEndTimeField != null) {
                    wordArray = new SuperLyricWord[subList.size()];
                    boolean useCharOffsets = mSubCharStartField != null && mSubCharEndField != null;
                    for (int j = 0; j < subList.size(); j++) {
                        Object eh = subList.get(j);
                        int startTimeMs = (int) getField(mSubStartTimeField, eh);
                        int endTimeMs = (int) getField(mSubEndTimeField, eh);
                        String wordText;
                        if (useCharOffsets) {
                            int startOff = (int) getField(mSubCharStartField, eh);
                            int endOff = (int) getField(mSubCharEndField, eh);
                            wordText = endOff <= text.length() && startOff <= endOff
                                ? text.substring(startOff, endOff) : "";
                        } else {
                            wordText = "";
                        }
                        wordArray[j] = new SuperLyricWord(wordText, startTimeMs, endTimeMs);
                    }
                }
            }

            if (isTranslation && pendingLine != null) {
                pendingLine.translation = text;
                pendingLine.translationWords = wordArray;
            } else {
                pendingLine = new LineData();
                pendingLine.text = text;
                pendingLine.startTime = timestamp;
                pendingLine.words = wordArray;
                originals.add(pendingLine);
            }
        }

        // 计算每行的结束时间
        for (int i = 0; i < originals.size(); i++) {
            if (i + 1 < originals.size()) {
                originals.get(i).endTime = originals.get(i + 1).startTime;
            } else if (mCachedDuration > 0) {
                originals.get(i).endTime = mCachedDuration;
            } else {
                originals.get(i).endTime = originals.get(i).startTime + 5000;
            }
        }

        mCachedLines.clear();
        mCachedLines.addAll(originals);
    }

    /**
     * 通过类型匹配发现歌词行（e$b/e$g）的混淆字段。
     * e$b 包含: Integer(时间戳), String(文本), boolean(isTranslation)
     * e$g 额外: List(子元素)
     * 注意: e$g 继承 e$b，用 getSuperclass() 遍历父类字段。
     * 只在首次调用时执行一次，后续直接用 Field 对象。
     */
    private void discoverLineFields(Object firstLine) {
        discoverFieldsFromClass(firstLine.getClass());
        Class<?> superClass = firstLine.getClass().getSuperclass();
        if (superClass != null && superClass != Object.class) {
            discoverFieldsFromClass(superClass);
        }
        // 确认所有核心字段都发现后才标记
        if (mLineTimestampField != null && mLineTextField != null
            && mLineIsTransField != null) {
            mLineFieldsDiscovered = true;
        }
    }

    private void discoverFieldsFromClass(Class<?> clazz) {
        for (Field f : clazz.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == Integer.class && mLineTimestampField == null) {
                mLineTimestampField = f;
                f.setAccessible(true);
            } else if (t == String.class && mLineTextField == null) {
                mLineTextField = f;
                f.setAccessible(true);
            } else if (t == boolean.class && mLineIsTransField == null) {
                mLineIsTransField = f;
                f.setAccessible(true);
            } else if (List.class.isAssignableFrom(t) && mLineSubElementsField == null) {
                mLineSubElementsField = f;
                f.setAccessible(true);
            }
        }
    }

    /**
     * 按声明顺序 + 值验证发现逐字元素（e.h）的混淆字段。
     * e.h 是预混淆 SDK 类，dex 字节码字段顺序固定为 a→b→c→d→e。
     * 用运行时值做合理性确认：b>0(偏移结束), d≤e(起止时间)。
     */
    private void discoverSubElementFields(List<?> subList) {
        if (subList.isEmpty()) return;

        Object firstSub = subList.get(0);

        // 按声明顺序收集 5 个 int 字段
        List<Field> allInts = new ArrayList<>(5);
        for (Field f : firstSub.getClass().getDeclaredFields()) {
            if (f.getType() == int.class) {
                f.setAccessible(true);
                allInts.add(f);
            }
        }
        if (allInts.size() != 5) {
            // int 数量不是 5，说明这不是 e.h 类型，直接标记免重试
            mSubFieldsDiscovered = true;
            return;
        }

        // 声明顺序 a(0), b(1), c(2), d(3), e(4)
        // 验证 b(1) 偏移结束 > 0
        if (readFieldIntRaw(allInts.get(1), firstSub) <= 0) return;
        // 验证 d(3) ≤ e(4)
        int d = readFieldIntRaw(allInts.get(3), firstSub);
        int e = readFieldIntRaw(allInts.get(4), firstSub);
        if (d > e) return;

        mSubCharStartField = allInts.get(0);
        mSubCharEndField = allInts.get(1);
        mSubStartTimeField = allInts.get(3);
        mSubEndTimeField = allInts.get(4);
        mSubFieldsDiscovered = true;
    }

    /**
     * 读取 int 字段值，失败返回 0。
     */
    private int readFieldIntRaw(Field f, Object obj) {
        try {
            return f.getInt(obj);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void clearLastSentData() {
        mLastSentLine = null;
        mLastSentFallbackText = null;
        mLastSentTitle = null;
        mLastSentArtist = null;
        mLastSentAlbum = null;
    }

    private void clearLyricCache() {
        mCachedLines.clear();
        mCachedTitle = null;
        mCachedArtist = null;
        mCachedAlbum = null;
        mCachedDuration = -1;
        mCurrentPosition = 0;
        mCachedRid = null;
        clearLastSentData();
    }

    /**
     * 根据播放位置查找对应的歌词行。
     */
    private LineData findLineByPosition(int positionMs) {
        if (mCachedLines.isEmpty()) return null;

        for (LineData line : mCachedLines) {
            if (positionMs >= line.startTime && positionMs < line.endTime) {
                return line;
            }
        }

        if (positionMs >= mCachedLines.get(mCachedLines.size() - 1).endTime) {
            return mCachedLines.get(mCachedLines.size() - 1);
        }

        return null;
    }
}
