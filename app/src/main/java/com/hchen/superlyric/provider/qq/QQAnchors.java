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
package com.hchen.superlyric.provider.qq;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Constructor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 音乐 Hook 锚点：从歌词加载请求的载体上取回当前曲目的官方标识。
 *
 * <h3>为什么锚在这里</h3>
 * QQ 音乐 20.9+ 的歌词行/词模型位于 {@code com.lyricengine.base.*}，深度混淆且结构随版本变动，
 * 是"末端反射"路线的典型陷阱。但无论模型怎么改，宿主都必须先把
 * {@code LyricLoadBean}（含 {@code SongInfo}）交给歌词引擎——这个构造入口是稳定的。
 *
 * 因此本层**只做一件事**：拦截该构造器，取出 {@code songMID} / {@code songID}。
 * 歌词本身由模块自行向官方接口拉取原生 QRC 报文并解密，
 * 与宿主内部模型彻底解耦——宿主改一次模型，本层不受影响。
 *
 * <h3>进程约束</h3>
 * 只在 {@code :QQPlayerService} 进程内挂载。QQ 音乐的多个子进程都会构造歌词相关对象，
 * 不设约束会引入与播放无关的噪声拦截。
 *
 * @author 焕晨HChen
 */
public final class QQAnchors {
    private static final String TAG = "QQAnchors";

    private static final String CACHE_KEY = "qqmusic_lyric_load_bean_class";

    /** 锚点：该构造器 {@code toString()} 的固定格式串。 */
    private static final String ANCHOR = "LyricLoadBean(songInfo=";

    private static final String TARGET_PROCESS_SUFFIX = ":QQPlayerService";

    private static final Pattern MID_PATTERN = Pattern.compile("mid=([^,]+)");
    private static final Pattern ID_PATTERN = Pattern.compile("id=(\\d+)");
    private static final Pattern NAME_PATTERN = Pattern.compile("name=([^,]+)");

    /** {@code shortMessage()} 中的紧凑格式：id=… name=… singer=… tmpPlayKey=… */
    private static final Pattern SHORT_MESSAGE_PATTERN = Pattern.compile(
        "id\\s*=\\s*(\\d+).*?name\\s*=\\s*(.*?)\\s+singer\\s*=\\s*(.*?)\\s+tmpPlayKey\\s*="
    );

    private static volatile boolean sInstalled;

    private QQAnchors() {
    }

    /** 取回当前曲目标识时的回调。 */
    public interface SongInfoListener {
        void onSongInfo(@NonNull String songMid, long songId, @NonNull String title, @NonNull String artist);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志。
     *
     * @param listener 宿主加载某曲目歌词时回调
     */
    public static void install(@NonNull SongInfoListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        String process = currentProcessName();
        if (process != null && process.indexOf(':') >= 0 && !process.endsWith(TARGET_PROCESS_SUFFIX)) {
            AndroidLog.logD(TAG, "Skipping lyric anchor in non-player sub-process: " + process);
            return;
        }

        try {
            Class<?> beanClass = locateBeanClass();
            if (beanClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate LyricLoadBean");
                return;
            }

            for (Constructor<?> constructor : beanClass.getDeclaredConstructors()) {
                if (constructor.getParameterCount() < 4) continue;

                constructor.setAccessible(true);
                CoreTool.hook(constructor, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            dispatch(getArg(0) != null ? getArg(0) : getThisObject(), listener);
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in LyricLoadBean hook: " + t.getMessage());
                        }
                    }
                });
                AndroidLog.logI(TAG, "Hooked LyricLoadBean constructor");
                return;
            }
            AndroidLog.logW(TAG, "No usable LyricLoadBean constructor found");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install QQ anchors", t);
        }
    }

    private static Class<?> locateBeanClass() {
        return DexkitCache.findMember(CACHE_KEY, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings(ANCHOR)
                    )
                );
                return (classes == null || classes.isEmpty()) ? null : classes.get(0);
            }
        });
    }

    /**
     * 从 {@code SongInfo} 中提取标识。优先用 {@code shortMessage()}（信息最完整），
     * 退回 {@code toString()}。
     */
    private static void dispatch(@Nullable Object songInfo, @NonNull SongInfoListener listener) {
        if (songInfo == null) return;

        String songMid = "";
        long songId = 0L;
        String title = "";
        String artist = "";

        try {
            Object shortMessage = CoreTool.callMethodIfExists(songInfo, "shortMessage");
            if (shortMessage instanceof String text && !text.isEmpty()) {
                Matcher matcher = SHORT_MESSAGE_PATTERN.matcher(text);
                if (matcher.find()) {
                    songId = parseLong(matcher.group(1));
                    title = isPlausible(matcher.group(2)) ? matcher.group(2).trim() : "";
                    String singer = matcher.group(3).trim();
                    int separator = singer.indexOf('·');
                    artist = isPlausible(separator > 0 ? singer.substring(0, separator) : singer)
                        ? (separator > 0 ? singer.substring(0, separator) : singer).trim()
                        : "";
                }
            }
        } catch (Throwable ignored) {
            // shortMessage 不可用时走 toString
        }

        try {
            Matcher midMatcher = MID_PATTERN.matcher(songInfo.toString());
            if (midMatcher.find()) songMid = midMatcher.group(1).trim();

            if (songId <= 0) {
                Matcher idMatcher = ID_PATTERN.matcher(songInfo.toString());
                if (idMatcher.find()) songId = parseLong(idMatcher.group(1));
            }
            if (title.isEmpty()) {
                Matcher nameMatcher = NAME_PATTERN.matcher(songInfo.toString());
                if (nameMatcher.find() && isPlausible(nameMatcher.group(1))) {
                    title = nameMatcher.group(1).trim();
                }
            }
        } catch (Throwable ignored) {
            // toString 不保证稳定
        }

        if (songMid.isEmpty() && songId <= 0) return;
        listener.onSongInfo(songMid, songId, title, artist);
    }

    private static long parseLong(@Nullable String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 过滤宿主内部字符串（URL、JSON、十六进制指纹等）冒充曲名的情况。
     */
    private static boolean isPlausible(@Nullable String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.isEmpty() || text.length() > 80) return false;
        if (text.startsWith("http://") || text.startsWith("https://")) return false;
        if (text.contains("\n") || text.contains("\r") || text.contains("\t")) return false;
        if ("未知歌手".equals(text) || "未知专辑".equals(text) || "null".equalsIgnoreCase(text)) return false;
        if (text.length() >= 16 && text.matches("^[0-9A-Fa-f]+$")) return false;
        char first = text.charAt(0);
        return first != '{' && first != '[' && first != '<';
    }

    @Nullable
    private static String currentProcessName() {
        try {
            return Application.getProcessName();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
