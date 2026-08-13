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
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyric.patches.kugou.KuGouCrashFix;
import com.hchen.superlyric.patches.kugou.KuGouLyricDistributor;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

/**
 * 酷狗音乐概念版
 */
@HookThis(targetPackage = "com.kugou.android.lite")
public final class KuGouLite extends AbsPublisher {
    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);

        KuGouLyricDistributor.enableStatusBarLyric("kugoulite$1");
        if (getVersionCode() <= 10935) {
            if (getVersionCode() == 10645) {
                hookMeizuLyricV10645();
            } else {
                KuGouLyricDistributor.hookLyricBroadcast("android.support.v4.content.LocalBroadcastManager");
            }
        } else {
            if (getVersionCode() == 11450) {
                hookMeizuLyricV11450();
            } else {
                KuGouLyricDistributor.hookLyricBroadcast("androidx.localbroadcastmanager.content.LocalBroadcastManager");
            }
            KuGouCrashFix.fixWifiServiceFetcherCrash();
        }
    }

    private void hookMeizuLyricV11450() {
        hookMethod("com.kugou.android.lyric.j",
            "d",
            Context.class, String.class, boolean.class,
            new AbsHook() {
                private Pair<String, Object> pair;

                @Override
                public void before() {
                    String lyric = (String) getArg(1);
                    boolean isClose = (boolean) getArg(2);

                    if (!isClose && lyric != null && !lyric.isEmpty()) {
                        Object c = callStaticMethod("uv.b", "c");
                        Object lyricData = callMethod(c, "f", 41);
                        String hash = (String) callMethod(c, "i", 207);
                        if (lyricData != null && hash != null) {
                            pair = new Pair<>(hash, lyricData);
                        }
                        if (lyricData == null && pair != null && TextUtils.equals(pair.first, hash)) {
                            lyricData = pair.second;
                        }
                        if (lyricData != null) {
                            int currentLine = (int) getField(getThisObject(), "a");
                            String[][] wordss = (String[][]) getField(lyricData, "f");
                            long[][] wordBegins = (long[][]) getField(lyricData, "i");
                            long[][] wordDelays = (long[][]) getField(lyricData, "j");
                            String[][] translateWordss = (String[][]) getField(lyricData, "k");
                            parseData(currentLine, wordss, translateWordss, wordBegins, wordDelays);
                        }
                    } else {
                        sendStop();
                    }
                }
            }
        );
    }

    private void hookMeizuLyricV10645() {
        hookMethod("com.kugou.android.lyric.e",
            "a",
            Context.class, String.class, boolean.class,
            new AbsHook() {
                @Override
                public void before() {
                    String lyric = (String) getArg(1);
                    boolean isClose = (boolean) getArg(2);

                    if (!isClose && lyric != null && !lyric.isEmpty()) {
                        Object lyricData = callMethod(callStaticMethod("com.kugou.framework.lyric.l", "a"), "k");
                        if (lyricData != null) {
                            int currentLine = (int) getField(getThisObject(), "a");
                            String[][] wordss = (String[][]) getField(lyricData, "e");
                            String[][] translateWordss = (String[][]) getField(lyricData, "h");
                            long[][] wordBegins = (long[][]) getField(lyricData, "f");
                            long[][] wordDelays = (long[][]) getField(lyricData, "g");
                            parseData(currentLine, wordss, translateWordss, wordBegins, wordDelays);
                        }
                    } else {
                        sendStop();
                    }
                }
            }
        );
    }

    private void parseData(int currentLine, String[][] wordss, String[][] translateWordss, long[][] wordBegins, long[][] wordDelays) {
        SuperLyricData data = new SuperLyricData();

        SuperLyricWord[] lyricWords = null;

        if (wordss != null && currentLine >= 0 && currentLine < wordss.length) {
            String[] words = wordss[currentLine];
            if (words == null) {
                return;
            }

            long lyricDelay = 0L;
            long[] begins = wordBegins[currentLine];
            long[] delays = wordDelays[currentLine];
            lyricWords = new SuperLyricWord[words.length];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < words.length; i++) {
                long begin = begins[i];
                long delay = delays[i];
                lyricDelay = lyricDelay + delay;

                sb.append(words[i]);
                lyricWords[i] = new SuperLyricWord(words[i], (int) begin, (int) (begin + delay));
            }

            data.setLyric(
                new SuperLyricLine(
                    sb.toString(),
                    lyricWords,
                    lyricDelay
                )
            );
        }

        if (translateWordss != null && currentLine < translateWordss.length) {
            String[] translateWords = translateWordss[currentLine];
            if (translateWords != null) {
                StringBuilder sb = new StringBuilder();
                for (String translateWord : translateWords) {
                    sb.append(translateWord);
                }
                data.setTranslation(
                    new SuperLyricLine(
                        sb.toString()
                    )
                );
            }
        }

        sendLyric(data);
    }
}
