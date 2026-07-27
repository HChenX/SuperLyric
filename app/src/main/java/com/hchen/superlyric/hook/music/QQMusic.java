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

import static android.view.View.GONE;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * QQ 音乐
 * <p>
 * 数据来源：MarqueeLyricView.setLyric → mLyric(com.lyricengine.base.k)
 * 字段映射：
 * k.b = title, k.f4836c = artist, k.d = album, k.e = CopyOnWriteArrayList<t>
 * t.f4850a = text, t.b = startTime, t.f4851c = duration, t.g = ArrayList<a>
 * a.f4819a = wordStartTime, a.b = wordDuration, a.e = wordText
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.tencent.qqmusic")
public final class QQMusic extends AbsPublisher {
    private static class LineData {
        String text;
        long startTime;
        long duration;

        LineData(String text, long startTime, long duration) {
            this.text = text;
            this.startTime = startTime;
            this.duration = duration;
        }

        long endTime() {
            return startTime + duration;
        }
    }

    private static final LineData EMPTY_LINE = new LineData("", 0, 0);

    // k.e — DexKit 定位（k 类唯一的 CopyOnWriteArrayList 字段）
    private Field mSentencesField;

    // t 类字段 — 运行时发现
    private Field mText;
    private Field mStart;
    private Field mDuration;
    private Field mWords;
    // a 类字段 — 运行时发现
    private Field mWordText;
    private Field mWordStart;
    private Field mWordDuration;
    private boolean mFieldsDiscovered;

    private final List<LineData> mLines = new CopyOnWriteArrayList<>();
    private final List<String> mTransLines = new CopyOnWriteArrayList<>();
    private final List<SuperLyricWord[]> mWordsList = new CopyOnWriteArrayList<>();
    private String mTitle;
    private String mArtist;
    private String mAlbum;
    private int mLastLine = -1;

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        Class<?> marqueeLyricViewClass = findClass("com.lyricengine.ui.MarqueeLyricView");
        Class<?> marqueeTextViewClass = findClass("com.tencent.qqmusic.ui.MarqueeTextView");

        // 定位 k 类中的 CopyOnWriteArrayList 字段（混淆名 "e"）
        Class<?> kClass = findClass("com.lyricengine.base.k");
        mSentencesField = DexkitCache.findMember("qq_music$sentences", new IDexkit<FieldData>() {
            @Override
            public FieldData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findField(FindField.create()
                    .matcher(FieldMatcher.create()
                        .declaredClass(kClass)
                        .type(CopyOnWriteArrayList.class)
                    )
                ).single();
            }
        });

        // 定位状态栏 ViewDelegate
        Method showLyricMethod = DexkitCache.findMember("qq_music$2", new IDexkit<MethodData>() {
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create()
                            .usingStrings("showLyricView operateLyric:true")
                        )
                        .usingStrings("showLyricView operateLyric:true")
                    )
                ).single();
            }
        });
        Class<?> viewDelegateClass = showLyricMethod.getDeclaringClass();

        // showLyricTipsView：歌词隐藏 → 清空缓存
        Method hideLyricMethod = DexkitCache.findMember("qq_music$3", new IDexkit<MethodData>() {
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(viewDelegateClass)
                        .usingStrings("showLyricTipsView operateLyric:false")
                    )
                ).single();
            }
        });
        hook(hideLyricMethod,
            new AbsHook() {
                @Override
                public void after() {
                    mLines.clear();
                    mTransLines.clear();
                    mWordsList.clear();
                    mTitle = null;
                    mArtist = null;
                    mAlbum = null;
                    mLastLine = -1;
                }
            }
        );

        // showLyricView：隐藏原生状态栏歌词
        Field lyricViewField = null;
        for (Field f : viewDelegateClass.getDeclaredFields()) {
            if (Objects.equals(f.getType(), View.class)) {
                lyricViewField = f;
                break;
            }
        }
        if (lyricViewField == null) return;
        Field finalViewField = lyricViewField;
        hook(showLyricMethod,
            new AbsHook() {
                @Override
                public void after() {
                    View view = (View) getField(finalViewField, getThisObject());
                    view.setVisibility(GONE);
                }
            }
        );

        // lyricModel:：获取 MarqueeTextView 文本作为兜底
        Method lyricModelMethod = DexkitCache.findMember("qq_music$6", new IDexkit<MethodData>() {
            @Override
            public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .declaredClass(viewDelegateClass)
                        .usingStrings("  lyricModel:")
                    )
                ).single();
            }
        });
        Field marqueeTextViewField = Arrays.stream(viewDelegateClass.getDeclaredFields())
            .filter(new Predicate<Field>() {
                @Override
                public boolean test(Field f) {
                    return Objects.equals(f.getType(), marqueeTextViewClass);
                }
            }).findFirst().orElseThrow();
        hook(lyricModelMethod,
            new AbsHook() {
                @Override
                public void after() {
                    TextView textView = (TextView) getField(marqueeTextViewField, getThisObject());
                    textView.setVisibility(GONE);
                    sendLyric((String) textView.getText());
                }
            }
        );

        // MarqueeLyricView.setLyric：主要数据来源
        hookAllMethod(marqueeLyricViewClass,
            "setLyric",
            new AbsHook() {
                @Override
                public void after() {
                    Object mLyric = getField(getThisObject(), "mLyric");
                    if (mLyric == null) return;

                    // 每次 setLyric 都是全新的歌词数据，清空所有缓存
                    mTitle = null;
                    mArtist = null;
                    mAlbum = null;
                    mWordsList.clear();
                    mTransLines.clear();
                    mLastLine = -1;

                    extractMeta(mLyric);
                    extractLines(mLyric);
                    extractTranslation(getArg(0));
                }
            }
        );

        // findCurrentLine：触发逐行发送
        hookMethod("com.lyricengine.ui.base.BaseLyricView",
            "findCurrentLine",
            int.class, CopyOnWriteArrayList.class, long.class,
            new AbsHook() {
                @Override
                public void after() {
                    sendLyric((Integer) getResult());
                }
            }
        );

        // 隐藏桌面小部件
        Class<?> widgetClass = DexkitCache.findMember("qq_music$5", new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingStrings("[addWindowIfNotExist] addView")
                    )
                ).single();
            }
        });
        try {
            Method widgetMethod = null;
            for (Method m : widgetClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 2 && Objects.equals(m.getParameterTypes()[1], Object.class)) {
                    widgetMethod = m;
                    break;
                }
            }
            if (widgetMethod != null) {
                hook(widgetMethod,
                    new AbsHook() {
                        @Override
                        public void before() {
                            boolean b = (boolean) getArg(1);
                            if (!b) setResult(null);
                        }
                    }
                );
            }
        } catch (Throwable e) {
            logE(TAG, e);
        }
    }

    /**
     * 从 com.lyricengine.base.k 提取元数据。
     * k.b = title, k.f4836c = artist, k.d = album
     */
    private void extractMeta(Object engineLyric) {
        try {
            for (Field field : engineLyric.getClass().getDeclaredFields()) {
                if (!Objects.equals(field.getType(), String.class)) continue;
                String value = (String) getField(field, engineLyric);
                if (value == null || value.isEmpty()) continue;

                if (mTitle == null)
                    mTitle = value;
                else if (mArtist == null)
                    mArtist = value;
                else if (mAlbum == null) {
                    mAlbum = value;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 运行时发现 t 类和 a 类的混淆字段。
     * t: String→text, long×2→start/duration, ArrayList→words
     * a: String→wordText, long×2→wordStart/wordDuration
     */
    private void discoverFields(Object firstSentence) {
        for (Field f : firstSentence.getClass().getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == String.class && mText == null)
                mText = f;
            else if (t == long.class && mStart == null)
                mStart = f;
            else if (t == long.class && mDuration == null)
                mDuration = f;
            else if (t == ArrayList.class && mWords == null) {
                // t 类有 2 个 ArrayList（g=逐字, e=旧UI），只取元素含 long+String 的
                Object raw = getField(f, firstSentence);
                if (raw instanceof List<?> list && !list.isEmpty() && hasWordFields(list.get(0)))
                    mWords = f;
            }
        }
        mFieldsDiscovered = (mText != null && mStart != null && mDuration != null);
    }

    /**
     * 检查元素是否有逐字特征（long + String），区分 a 类和 u 类。
     */
    private static boolean hasWordFields(Object obj) {
        if (obj == null) return false;
        boolean hasLong = false;
        boolean hasString = false;
        for (Field f : obj.getClass().getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == long.class || t == Long.class) hasLong = true;
            else if (t == String.class) hasString = true;
        }
        return hasLong && hasString;
    }

    private void discoverWordFields(Object firstWord) {
        for (Field f : firstWord.getClass().getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == String.class && mWordText == null)
                mWordText = f;
            else if (t == long.class && mWordStart == null)
                mWordStart = f;
            else if (t == long.class && mWordDuration == null)
                mWordDuration = f;
        }
    }

    /**
     * 从 com.lyricengine.base.k.e (CopyOnWriteArrayList&lt;t&gt;) 提取行数据和逐字数据。
     */
    private void extractLines(Object engineLyric) {
        try {
            Object rawLines = getField(mSentencesField, engineLyric);
            if (!(rawLines instanceof List<?> lines)) return;
            if (lines.isEmpty()) return;

            mLines.clear();
            mWordsList.clear();

            // 首次运行时发现 t 类字段
            if (!mFieldsDiscovered && lines.get(0) != null) {
                discoverFields(lines.get(0));
            }
            if (mText == null) return;

            for (Object line : lines) {
                if (line == null) {
                    mLines.add(EMPTY_LINE);
                    mWordsList.add(null);
                    continue;
                }

                String text = (String) getField(mText, line);
                if (text == null || text.isEmpty()) {
                    mLines.add(EMPTY_LINE);
                    mWordsList.add(null);
                    continue;
                }

                long startTime = (long) getField(mStart, line);
                long duration = (long) getField(mDuration, line);
                mLines.add(new LineData(text, startTime, duration));

                // 逐字数据
                Object rawWords = mWords != null ? getField(mWords, line) : null;
                if (rawWords instanceof List<?> wordList && !wordList.isEmpty()) {
                    if (mWordText == null) discoverWordFields(wordList.get(0));
                    if (mWordText != null) {
                        SuperLyricWord[] words = new SuperLyricWord[wordList.size()];
                        for (int w = 0; w < wordList.size(); w++) {
                            Object a = wordList.get(w);
                            String wt = (String) getField(mWordText, a);
                            long ws = (long) getField(mWordStart, a);
                            long wd = (long) getField(mWordDuration, a);
                            if (wt != null && !wt.isEmpty())
                                words[w] = new SuperLyricWord(wt, (int) ws, (int) (ws + wd));
                        }
                        mWordsList.add(words);
                        continue;
                    }
                }
                mWordsList.add(null);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从 MarqueeLyricView.setLyric(k[]) 的 varargs 中提取翻译歌词。
     * kArr[0] = main lyric, kArr[1] = transLyric
     */
    private void extractTranslation(Object arg0) {
        mTransLines.clear();

        try {
            if (!(arg0 instanceof Object[] kArr)) return;
            if (kArr.length < 2) return;
            Object transK = kArr[1];
            if (transK == null) return;

            Object rawLines = getField(mSentencesField, transK);
            if (!(rawLines instanceof List<?> lines)) return;

            // mText 可能尚未初始化（extractLines 未执行或失败），此时兜底用遍历
            if (mText == null && !lines.isEmpty() && lines.get(0) != null) {
                for (Field f : lines.get(0).getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) { mText = f; break; }
                }
            }

            for (Object line : lines) {
                if (line == null) {
                    mTransLines.add("");
                    continue;
                }
                String text = mText != null ? (String) getField(mText, line) : "";
                mTransLines.add(text != null ? text : "");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 由 findCurrentLine 结果触发，发送逐字和翻译数据。
     */
    private void sendLyric(Integer line) {
        if (line == null) return;
        if (mLines.isEmpty()) return;
        if (line >= mLines.size()) {
            sendStop();
            return;
        }
        if (line == mLastLine) return;
        mLastLine = line;

        LineData data = mLines.get(line);
        if (data.text == null || data.text.isEmpty()) return;

        SuperLyricData lyricData = new SuperLyricData();
        if (mTitle != null) lyricData.setTitle(mTitle);
        if (mArtist != null) lyricData.setArtist(mArtist);
        if (mAlbum != null) lyricData.setAlbum(mAlbum);

        SuperLyricWord[] words = line < mWordsList.size() ? mWordsList.get(line) : null;
        lyricData.setLyric(
            new SuperLyricLine(data.text, words, data.startTime, data.endTime())
        );

        if (line < mTransLines.size()) {
            String trans = mTransLines.get(line);
            if (trans != null && !trans.isEmpty()) {
                lyricData.setTranslation(new SuperLyricLine(trans));
            }
        }

        sendLyric(lyricData);
    }
}
