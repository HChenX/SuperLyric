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
import com.hchen.superlyric.utils.LyricDiag;

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

    /** 上一次已输出的原始串，用于抑制宿主的重复构造刷屏。 */
    private static volatile String sLastRawToString;
    private static volatile String sLastRawShortMessage;
    private static volatile String sLastExtracted;

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
            // I 级而非 D 级：判据写死了子进程名，宿主一旦改名这里就**永不挂载**且悄无声息，
            // 必须能在默认日志级别下看见"没挂"
            AndroidLog.logI(TAG, "Skipping lyric anchor in non-player sub-process: " + process
                + " (expected suffix: " + TARGET_PROCESS_SUFFIX + ")");
            return;
        }

        try {
            Class<?> beanClass = locateBeanClass();
            if (beanClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate LyricLoadBean");
                return;
            }

            dumpConstructors(beanClass);

            int hooked = 0;
            for (Constructor<?> constructor : beanClass.getDeclaredConstructors()) {
                // Kotlin 会为一个 8 参主构造器额外合成一个 (…, int, DefaultConstructorMarker) 桥接。
                // 按参数个数筛选是不可靠的：一旦选中桥接，而宿主实际走 8 参路径，
                // Hook 就永远不会触发。此处按**首参类型**精确筛选。
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length == 0) continue;
                if (!isSongInfo(parameterTypes[0])) continue;

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
                hooked++;
                AndroidLog.logD(TAG, "Hooked LyricLoadBean ctor: " + constructor.toGenericString());
            }

            if (hooked == 0) {
                AndroidLog.logW(TAG, "No SongInfo-typed LyricLoadBean constructor found");
            }
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install QQ anchors", t);
        }
    }

    /**
     * 首参是否为宿主曲目信息类型。
     *
     * 这里出现的 {@code SongInfo} 是宿主的**业务类名**（非混淆产物），跨版本稳定；
     * 判据用后缀匹配以容忍包路径调整。
     */
    private static boolean isSongInfo(@Nullable Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        return "SongInfo".equals(name) || name.endsWith(".SongInfo");
    }

    /** 输出候选构造器清单，用于核对"到底挂了哪一个"。 */
    private static void dumpConstructors(@NonNull Class<?> beanClass) {
        StringBuilder builder = new StringBuilder();
        for (Constructor<?> constructor : beanClass.getDeclaredConstructors()) {
            builder.append("\n  ").append(constructor.toGenericString());
        }
        LyricDiag.dumpPayload(TAG, "CTORS", beanClass.getName() + " ctors:" + builder);
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

        // 未解析的原始数据：这两个串正是 MID/ID/NAME 正则的输入，必须原样可见。
        // 宿主会反复重建同一份 SongInfo，因此只在内容变化时输出。
        String raw = String.valueOf(songInfo);
        if (!raw.equals(sLastRawToString)) {
            sLastRawToString = raw;
            LyricDiag.dumpPayload(TAG, "RAW-toString", raw);
        }

        String songMid = "";
        long songId = 0L;
        String title = "";
        String artist = "";

        try {
            Object shortMessage = CoreTool.callMethodIfExists(songInfo, "shortMessage");
            if (shortMessage instanceof String text && !text.isEmpty()) {
                if (!text.equals(sLastRawShortMessage)) {
                    sLastRawShortMessage = text;
                    LyricDiag.dumpPayload(TAG, "RAW-shortMessage", text);
                }
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

        String extracted = songMid + "|" + songId + "|" + title + "|" + artist;
        if (!extracted.equals(sLastExtracted)) {
            sLastExtracted = extracted;
            LyricDiag.dumpPayload(TAG, "EXTRACTED", "songMid=" + songMid + " songId=" + songId
                + " title=\"" + title + "\" artist=\"" + artist + "\"");
        }
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
