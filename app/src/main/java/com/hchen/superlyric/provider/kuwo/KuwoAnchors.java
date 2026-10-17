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
package com.hchen.superlyric.provider.kuwo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 酷我音乐 Hook 锚点：取回**解密后的 LRCX 全文**（含逐字时间戳）。
 *
 * <h3>两个分发变体</h3>
 * 手机版与车机版的歌词管线同构，但混淆后的包名与类名都不同，因此所有锚点都按
 * 候选清单逐个尝试，命中哪个用哪个，不为两个变体维护两份代码：
 * <ul>
 *   <li>手机版 {@code cn.kuwo.player}：歌词包 {@code cn.kuwo.mod.lyrics}（复数）</li>
 *   <li>车机版 {@code cn.kuwo.kwmusiccar}：歌词包 {@code cn.kuwo.mod.lyric}（单数）</li>
 * </ul>
 *
 * <h3>锚点位置</h3>
 * <ul>
 *   <li><b>手机版</b>：{@code j6.f.a(String)} 与 {@code j6.d.a(String)}，即接口
 *       {@code cn.kuwo.mod.lyrics.g} 的两个实现（LRCX 与普通 LRC）。入参就是
 *       <b>已解密、未被宿主裁切</b>的全文 —— 此时 zlib 已解、yeelion 异或已去、
 *       逐字标签 {@code <v1,v2>} 与八进制密钥头 {@code [kuwo:KEY]} 都还是原样。</li>
 *   <li><b>车机版</b>：{@code cn.kuwo.mod.lyric.h.h([B, LyricsType) → String}。
 *       车机版把解密与解析拆开了：{@code g.a(String)} 只负责解析，明文由
 *       {@code h.h} 产出，所以只挂解析入口等不到数据，必须锚在解密出口。</li>
 * </ul>
 *
 * <h3>为什么绕开宿主的行/词模型</h3>
 * 拿到明文后，宿主的下一层（{@code LyricsImpl → w → x}）会对相邻词元做重叠裁切
 * （见 {@code Lj6.f.f} 里逐词回写 {@code e = d}），使逐字时戳出现 0ms 坍缩、动效卡死。
 * 锚在它之前就绕开了那套裁切，与波点、椒盐的做法同源。
 *
 * <h3>进度怎么来</h3>
 * 本模块<b>不</b>从宿主取进度。播放位置与行切换由框架侧唯一时钟负责（L3），
 * Provider 只发布全量歌词。
 *
 * <h3>还挂了一个曲目探针</h3>
 * 见 [installTrackProbe]。它不参与取词，只为拿到宿主的 {@code Music.rid} 作为
 * 稳定标识 —— 酷我的 {@code MediaMetadata} 不可信。
 *
 * @author 焕晨HChen
 */
public final class KuwoAnchors {
    private static final String TAG = "KuwoAnchors";

    /**
     * 歌词解析器的候选全限定名
     *
     * <p>两个变体的混淆结果完全不同：
     * <ul>
     *   <li>手机版：混淆器把 {@code j6} 包提到了顶层，解析实现是 {@code j6.f}（LRCX）
     *       与 {@code j6.d}（纯 LRC）。**不是** {@code cn.kuwo.mod.lyrics.j6.f}</li>
     *   <li>车机版：解析器就是 {@code cn.kuwo.mod.lyric.g} 一个类，LRCX 与纯 LRC
     *       由它内部按标签分派，没有拆成两个实现</li>
     * </ul>
     * 逐个尝试，命中哪个用哪个。
     */
    private static final String[] PARSER_CANDIDATES = {
        "j6.f",
        "j6.d",
        "cn.kuwo.mod.lyric.g",
    };

    /**
     * 解密入口（车机版）
     *
     * <p>{@code h.h([B, LyricsType) → String}：输入密文字节与歌词类型，输出明文。
     * 车机版把解密与解析拆开了 —— {@code g.a(String)} 只是解析，喂给它的明文由
     * {@code h.h} 产出，因此只挂 {@code g.a} 等不到数据，必须锚在解密出口。
     */
    private static final String DECRYPT_ENTRY = "cn.kuwo.mod.lyric.h";

    /**
     * 歌词包名
     *
     * <p>两个变体包名不同：手机版是 {@code cn.kuwo.mod.lyrics}（复数），
     * 车机版是 {@code cn.kuwo.mod.lyric}（单数）。按候选列表逐个尝试，
     * 命中哪个就用哪个，避免为两个变体维护两份锚点。
     */
    private static final String[] LYRIC_PACKAGES = {
        "cn.kuwo.mod.lyrics",
        "cn.kuwo.mod.lyric",
    };

    private static volatile boolean sInstalled;

    private KuwoAnchors() {
    }

    /** 取到明文歌词时的回调。 */
    public interface LyricTextListener {
        /**
         * @param lyricText 明文歌词全文；LRCX 或普通 LRC
         */
        void onLyricText(@NonNull String lyricText);
    }

    /** 宿主当前曲目回调。 */
    public interface TrackListener {
        /**
         * @param music    宿主曲目对象；可能为 null
         * @param fallback 宿主给出的第二曲目参数（K歌/伴唱场景）；可能为 null
         */
        void onTrack(@Nullable Object music, @Nullable Object fallback);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志。
     */
    public static void install(@NonNull LyricTextListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        int hooked = 0;
        for (String className : PARSER_CANDIDATES) {
            hooked += installParserHook(className, listener);
        }
        hooked += installDecryptHook(listener);

        if (hooked == 0) {
            AndroidLog.logW(TAG, "No lyric parser entry hooked; Kuwo hook path is dead");
        } else {
            AndroidLog.logI(TAG, "Hooked " + hooked + " lyric parser entry(ies)");
        }
    }

    /**
     * 挂载单个解析器实现的 {@code a(String) → LyricsImpl}。
     *
     * <p>按<b>参数个数与返回类型</b>定位而不是按名字：{@code a} 是混淆产物，
     * 而"单 String 入参、返回歌词实现"这个签名由 {@code g} 接口唯一确定。
     * 取**入参**：调用时宿主手里就是解密后的全文。
     */
    private static int installParserHook(
        @NonNull String className,
        @NonNull LyricTextListener listener
    ) {
        try {
            Class<?> parserClass = CoreTool.findClassIfExists(className);
            if (parserClass == null) {
                // 两个变体只命中一个，另一个类不存在是预期情况，不必告警
                AndroidLog.logD(TAG, "Parser class not found: " + className);
                return 0;
            }

            int hooked = 0;
            for (Method method : parserClass.getDeclaredMethods()) {
                if (method.getParameterCount() != 1) continue;
                if (method.getParameterTypes()[0] != String.class) continue;
                // 必须精确到返回歌词实现：只按"非 void 非 String"筛会把同类上的私有
                // 辅助方法一并挂上。实测手机版 {@code j6.f.g(String) → Matcher} 是逐行
                // 解析时的内部回调，挂上后同一份歌词会被拆成若干片段（每次只有一行），
                // 片段解析不出行又会判成 Unsupported，反而盖掉真正成功的取词。
                //
                // 返回类型随变体而变：手机版 {@code cn.kuwo.mod.lyrics.e}、
                // 车机版 {@code cn.kuwo.mod.lyric.a}，因此只校验它落在歌词包内。
                // 注意是比较内容：Java 的 == 比的是引用，写错会让入口一个都挂不上
                String returned = method.getReturnType().getName();
                if (!isLyricImpl(returned)) continue;

                method.setAccessible(true);
                CoreTool.hook(method, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            handleParse(getArg(0), listener);
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in lyric parser hook: " + t.getMessage());
                        }
                    }
                });
                hooked++;
                AndroidLog.logI(TAG, "Hooked " + parserClass.getName() + "." + method.getName());
            }

            if (hooked == 0) {
                AndroidLog.logW(TAG, "No (String) entry on " + parserClass.getName());
            }
            return hooked;
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to install parser hook for " + className + ": " + t.getMessage());
            return 0;
        }
    }

    /**
     * 从解析入口抽取明文歌词。
     *
     */
    private static void handleParse(@Nullable Object rawArg, @NonNull LyricTextListener listener) {
        if (!(rawArg instanceof String raw) || raw.isEmpty()) return;
        listener.onLyricText(raw);
    }

    /**
     * 是否为歌词实现类型
     *
     * <p>返回类型落在 {@link #LYRIC_PACKAGES} 任一首字母包内即算。包名比类名稳定：
     * 混淆会让类名在 {@code e} / {@code a} 之间变，但歌词包本身不会搬走。
     */
    private static boolean isLyricImpl(@NonNull String returnedName) {
        for (String pkg : LYRIC_PACKAGES) {
            if (returnedName.startsWith(pkg + ".")) return true;
        }
        return false;
    }

    /**
     * 挂载解密入口 {@code h.h([B, LyricsType) → String}
     *
     * <p>取**返回值**而不是入参：入参是密文字节，解密后的明文只在返回值里。
     * 这是车机版唯一产出明文的位置 —— 它的 {@code g.a(String)} 只负责解析，
     * 明文由这里喂进去，所以只挂解析入口等不到任何数据。
     */
    private static int installDecryptHook(@NonNull LyricTextListener listener) {
        try {
            Class<?> clazz = CoreTool.findClassIfExists(DECRYPT_ENTRY);
            if (clazz == null) {
                AndroidLog.logD(TAG, "Decrypt entry not found: " + DECRYPT_ENTRY);
                return 0;
            }

            int hooked = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                // 签名：([B, LyricsType) → String
                if (method.getParameterCount() != 2) continue;
                if (method.getParameterTypes()[0] != byte[].class) continue;
                if (!method.getParameterTypes()[1].getName().contains("LyricsType")) continue;
                if (method.getReturnType() != String.class) continue;

                method.setAccessible(true);
                CoreTool.hook(method, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            Object raw = getResult();
                            if (raw instanceof String text && !text.isEmpty()) {
                                listener.onLyricText(text);
                            }
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in decrypt hook: " + t.getMessage());
                        }
                    }
                });
                hooked++;
                AndroidLog.logI(TAG, "Hooked decrypt entry: " + clazz.getName() + "." + method.getName());
            }

            if (hooked == 0) {
                AndroidLog.logW(TAG, "No ([B, LyricsType) entry on " + clazz.getName());
            }
            return hooked;
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to install decrypt hook: " + t.getMessage());
            return 0;
        }
    }

    /**
     * 挂载宿主曲目探针。
     *
     * <p>两个变体的取词入口形态不同，但参数签名一致 {@code (Music, boolean, Music)}：
     * 手机版是 {@code cn.kuwo.mod.lyrics.a0.B(...)} 这个方法，车机版是
     * {@code cn.kuwo.mod.lyric.i} 的构造器。方法与构造器都挂上，
     * 命中哪个算哪个。
     *
     * <p>拿到的是宿主自己的 {@code Music}，其 {@code rid} 是稳定标识，
     * {@code name}/{@code artist} 是干净曲名。之所以需要它：酷我的
     * {@code MediaMetadata} 会把<b>当前歌词行</b>写进 {@code METADATA_KEY_TITLE}
     * （实测每次回调都变），元数据不可信。
     */
    public static void installTrackProbe(@NonNull TrackListener listener) {
        int hooked = 0;
        for (String pkg : LYRIC_PACKAGES) {
            hooked += hookTrackEntry(pkg + ".a0", listener);
            hooked += hookTrackEntry(pkg + ".i", listener);
        }

        if (hooked == 0) {
            AndroidLog.logW(TAG, "No (Music, boolean, Music) entry found; track probe unavailable");
        } else {
            AndroidLog.logI(TAG, "Hooked " + hooked + " track entry(ies)");
        }
    }

    /**
     * 挂载单个类上所有 {@code (Music, boolean, Music)} 形态的成员
     *
     * <p>构造器与方法都试：手机版用方法、车机版用构造器，而两者都能用同一套
     * 参数签名描述。
     */
    private static int hookTrackEntry(@NonNull String className, @NonNull TrackListener listener) {
        try {
            Class<?> clazz = CoreTool.findClassIfExists(className);
            if (clazz == null) {
                AndroidLog.logD(TAG, "Track entry class not found: " + className);
                return 0;
            }

            int hooked = 0;
            List<Executable> members = new ArrayList<>();
            Collections.addAll(members, clazz.getDeclaredConstructors());
            Collections.addAll(members, clazz.getDeclaredMethods());

            for (Executable member : members) {
                Class<?>[] params = member.getParameterTypes();
                if (params.length != 3) continue;
                if (!params[0].getName().endsWith("base.bean.Music")) continue;
                if (params[1] != boolean.class) continue;
                if (!params[2].getName().endsWith("base.bean.Music")) continue;

                member.setAccessible(true);
                CoreTool.hook(member, new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            listener.onTrack(getArg(0), getArg(2));
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in track probe: " + t.getMessage());
                        }
                    }
                });
                hooked++;
                AndroidLog.logI(TAG, "Hooked track entry: " + member);
            }
            return hooked;
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to install track probe for " + className + ": " + t.getMessage());
            return 0;
        }
    }

    /**
     * 从宿主 {@code Music} 对象上读 rid / 曲名 / 歌手。
     *
     * <p>字段直读而不是调 getter：酷我的 {@code Music} 有 278 个字段、194 个方法，
     * 大量 getter 有副作用（查数据库），而 {@code rid} / {@code name} / {@code artist}
     * 是普通字段。
     */
    @NonNull
    public static KuwoTrack readTrack(@Nullable Object music) {
        if (music == null) return KuwoTrack.EMPTY;
        String rid = readStringField(music, "rid");
        String title = readStringField(music, "name");
        String artist = readStringField(music, "artist");
        return new KuwoTrack(rid, title, artist);
    }

    @NonNull
    private static String readStringField(@NonNull Object target, @NonNull String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(target);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 宿主曲目的最小信息集。 */
    public static final class KuwoTrack {
        public static final KuwoTrack EMPTY = new KuwoTrack("", "", "");

        public final String rid;
        public final String title;
        public final String artist;

        KuwoTrack(@NonNull String rid, @NonNull String title, @NonNull String artist) {
            this.rid = rid;
            this.title = title;
            this.artist = artist;
        }
    }
}
