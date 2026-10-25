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

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.ParameterMatcher;
import org.luckypray.dexkit.query.matchers.ParametersMatcher;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;

/**
 * 酷我音乐 Hook 锚点：取回<b>解密后的 LRCX 全文</b>（含逐字时间戳）。
 *
 * <h3>两个分发变体</h3>
 * 手机版（{@code cn.kuwo.player}）与车机版（{@code cn.kuwo.kwmusiccar}）的歌词管线
 * 同构，但混淆后的包名与类名都不同。两者共用本类的全部逻辑，不做成两份。
 *
 * <h3>锚点怎么定位</h3>
 * 一律按<b>行为特征</b>定位，不硬编码任何类名或包名 —— 那些都是混淆产物，
 * 宿主一次升级就可能全变。流程是：
 * <ol>
 *   <li>用 LRCX 协议固有的参数名 {@code lrcx=} 反查歌词模块内的一个类，
 *       再由类名推出包名（见 {@link #locateLyricPackage()}）</li>
 *   <li>在该包内按签名检索解析入口与取词入口</li>
 * </ol>
 * 各步骤的检索条件与实测坑见对应方法的注释。
 *
 * <h3>取到的数据是什么</h3>
 * 解析入口的入参就是<b>已解密、未被宿主裁切</b>的 LRCX 全文：此时 zlib 已解、
 * yeelion 异或已去、逐字标签 {@code <v1,v2>} 与八进制密钥头 {@code [kuwo:KEY]}
 * 都还是原样。
 *
 * <h3>为什么绕开宿主的行/词模型</h3>
 * 拿到明文后，宿主的下一层会对相邻词元做重叠裁切，使逐字时戳出现 0ms 坍缩、动效卡死。
 * 锚在它之前就绕开了那套裁切，与波点、椒盐的做法同源。
 *
 * <h3>进度怎么来</h3>
 * 本模块<b>不</b>从宿主取进度。播放位置与行切换由框架侧唯一时钟负责（L3），
 * Provider 只发布全量歌词。
 *
 * <h3>还挂了一个曲目探针</h3>
 * 见 {@link #installTrackProbes(TrackListener)}。它不参与取词，只为拿到宿主的 {@code Music.rid} 作为
 * 稳定标识 —— 酷我的 {@code MediaMetadata} 不可信。
 *
 * @author 焕晨HChen
 */
public final class KuwoAnchors {
    private static final String TAG = "KuwoAnchors";

    // ---------------------------------------------------------------- 缓存键

    /**
     * 解析入口的缓存键
     *
     * <p>按<b>方法</b>而不是按类缓存：类名会随混淆变，而"单 String 入参、返回歌词实现"
     * 这个签名由宿主接口决定，跨版本稳定。宿主升级后缓存失效会自动重新检索。
     */
    private static final String KEY_PARSER_METHODS = "kuwo_parser_methods_v2";

    /**
     * 解密入口的缓存键
     */
    private static final String KEY_DECRYPT_METHODS = "kuwo_decrypt_methods_v2";

    /**
     * 歌词包的缓存键
     *
     * <p>键里带返回值形态：{@code ClassData} 与 {@code ClassDataList} 会被缓存成
     * 不同的 Java 类型（{@code Class} 与 {@code Class[]}），复用同一个键会强转失败。
     * 换检索形态时必须换键。
     */
    private static final String KEY_LYRIC_PACKAGE = "kuwo_lyric_module_anchor_v1";

    /**
     * 包内类列表的缓存键前缀；后接包名，避免不同包共用同一键
     */
    private static final String KEY_PACKAGE_CLASSES = "kuwo_pkg_classes_";

    /**
     * 解析入口的名字
     *
     * <p>两个变体的解析入口都叫 {@code a}（混淆产物）。名字本身不稳定，因此只作为
     * 检索条件之一，真正的约束来自签名。
     */
    private static final String ANCHOR_PARSER_NAME = "a";

    /**
     * LRCX 的 URL 查询参数名
     *
     * <p>形如 {@code lrcx=}，是酷我请求逐字歌词时带的参数名。实测在两个变体的 dex 里
     * 各只出现一次，且只属于歌词模块内的类（车机版在 {@code lyric/h}，即解密类）。
     * 用它定位歌词模块，再由类名推出包名。
     *
     * <p>选它的理由：比字段名（{@code lrcx_roma} 会被网络层引用）唯一，比类名稳定，
     * 比正则片段（{@code \d{1,2}} 会被一批工具类引用）精确。它是酷我歌词协议的一部分，
     * 只要还支持逐字歌词就得有这个参数。
     */
    private static final String ANCHOR_LRCX_PARAM = "lrcx=";

    private static volatile boolean sInstalled;

    private KuwoAnchors() {
    }

    /**
     * 取到明文歌词时的回调。
     */
    public interface LyricTextListener {
        /**
         * @param lyricText 明文歌词全文；LRCX 或普通 LRC
         */
        void onLyricText(@NonNull String lyricText);
    }

    /**
     * 宿主当前曲目回调。
     */
    public interface TrackListener {
        /**
         * @param rid    宿主曲目标识
         * @param title  曲名
         * @param artist 歌手
         */
        void onTrack(@NonNull String rid, @NonNull String title, @NonNull String artist);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志。
     */
    public static void install(@NonNull LyricTextListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        installParsers(listener);
        installDecryptors(listener);
    }

    /**
     * 检索歌词解析入口的方法
     *
     * <p>结果为空时必须返回 {@code null} 而不是空列表：DexkitCache 只对 {@code null}
     * 跳过缓存写入，空列表会被当成有效结果落盘，此后每次命中缓存都反序列化出空数据
     * 并抛 {@code Illegal state}，检索条件再怎么改都不再生效。
     */
    @NonNull
    private static List<Method> locateParserMethods() {
        List<Method> found = new ArrayList<>();
        String lyricPackage = locateLyricPackage();
        if (lyricPackage == null) {
            AndroidLog.logW(TAG, "Lyric module not located; parser hook unavailable");
            return found;
        }
        try {
            Method[] located = DexkitCache.findMember(KEY_PARSER_METHODS, new IDexkit<MethodDataList>() {
                @Override
                public MethodDataList dexkit(@NonNull DexKitBridge bridge)
                    throws ReflectiveOperationException {
                    MethodDataList result = bridge.findMethod(FindMethod.create()
                        .searchPackages(lyricPackage)
                        .matcher(MethodMatcher.create()
                            .paramCount(1)
                            .name(ANCHOR_PARSER_NAME, StringMatchType.Equals, true)
                        )
                    );
                    return (result == null || result.isEmpty()) ? null : result;
                }
            });
            if (located == null) return found;

            // 同包内有一批同名同签名的方法（CoverLinkCache.a、d.a 等）。DexKit 的
            // declaredClass/usingStrings 与 returnType 在本版本实测都不生效，因此这一层
            // 放到 Java 侧做：只保留返回类型落在歌词包内的，真正的解析入口必然在其中。
            // 多余的几个签名相同，回调里对非 String 入参自然被过滤，不会错误发布。
            //
            // 另需排除抽象方法与接口：同包的歌词接口（如 lyric.a）带着同签名的抽象方法，
            // 挂上去只会得到 "Cannot hook abstract methods" 的噪音
            for (Method method : located) {
                if (Modifier.isAbstract(method.getModifiers())) continue;
                if (Modifier.isAbstract(method.getDeclaringClass().getModifiers())) continue;
                if (method.getReturnType().getName().startsWith(lyricPackage + ".")) {
                    found.add(method);
                }
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to locate parser methods: " + t.getMessage());
        }
        return found;
    }

    /**
     * 由 LRCX 参数名定位歌词模块所在的包
     *
     * <p>不硬编码包名：两个变体的歌词包名不同（{@code lyrics} / {@code lyric}），
     * 且都可能随版本改。改用一个只可能在歌词模块出现的参数名反查所在类，
     * 再从类名推出包名 —— 包名怎么变都能跟上。
     *
     * @return 歌词包名；定位不到返回 {@code null}
     */
    @Nullable
    private static String locateLyricPackage() {
        // 必须用 Class<?> 接收：findMember 会把 ClassData 解析成 Class 再缓存，
        // 用 ClassData 接收会在缓存命中时强转失败
        Class<?> located = DexkitCache.findMember(KEY_LYRIC_PACKAGE, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(@NonNull DexKitBridge bridge)
                throws ReflectiveOperationException {
                ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings(ANCHOR_LRCX_PARAM)
                    )
                );
                return (classes == null || classes.isEmpty()) ? null : classes.get(0);
            }
        });

        if (located == null) {
            AndroidLog.logW(TAG, "Lyric module not located by anchor: " + ANCHOR_LRCX_PARAM);
            return null;
        }
        String name = located.getName();
        AndroidLog.logD(TAG, "Lyric module anchor class: " + name);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }

    /**
     * 检索解密入口的方法（车机版）
     *
     * <p>签名取 {@code ([B, LyricsType) → String}。取<b>返回值</b>：入参是密文字节，
     * 解密后的明文只在返回值里。
     *
     * <p>第二个参数校验为 LyricsType：只按「两参 + 首参 byte[]」会命中一批无关的
     * 字节处理方法，而歌词类型参数是解密入口独有的。
     */
    @NonNull
    private static List<Method> locateDecryptMethods() {
        List<Method> found = new ArrayList<>();
        String lyricPackage = locateLyricPackage();
        if (lyricPackage == null) return found;
        try {
            Method[] located = DexkitCache.findMember(KEY_DECRYPT_METHODS, new IDexkit<MethodDataList>() {
                @Override
                public MethodDataList dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    MethodDataList result = bridge.findMethod(FindMethod.create()
                        .searchPackages(lyricPackage)
                        .matcher(MethodMatcher.create()
                            .returnType("Ljava/lang/String;", StringMatchType.Equals, true)
                            .paramCount(2)
                            // 两个参数都要列出：只描述首参 byte[] 会命中一批无关的
                            // 字节处理方法，而歌词类型参数是解密入口独有的
                            .params(ParametersMatcher.create().params(Arrays.asList(
                                ParameterMatcher.create()
                                    .type("[B", StringMatchType.Equals, true),
                                ParameterMatcher.create()
                                    .type("LyricsType", StringMatchType.Contains, true))))
                        )
                    );
                    return (result == null || result.isEmpty()) ? null : result;
                }
            });
            if (located != null) Collections.addAll(found, located);
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to locate decrypt methods: " + t.getMessage());
        }
        return found;
    }

    /**
     * 检索取词入口的方法
     *
     * <p>与 {@link #locateTrackConstructors()} 同路：DexKit 的 {@code findMethod} 配
     * {@code searchPackages} 在本版本实测也匹配不到，改用包内反射扫。
     */
    @NonNull
    private static List<Executable> locateTrackEntries() {
        List<Executable> found = new ArrayList<>();
        String lyricPackage = locateLyricPackage();
        if (lyricPackage == null) return found;
        for (Class<?> clazz : classesInPackage(lyricPackage)) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (isTrackEntry(method)) found.add(method);
            }
        }
        return found;
    }

    /**
     * 由 DexKit 定位解析入口并挂载
     *
     * <p>刻意不做硬编码类名的兜底：那些名字是混淆产物，几乎必然随版本变，
     * 留着只会给人"已经兼容"的错觉。定位不到就明确报错，让人知道要去修检索条件。
     */
    private static void installParsers(@NonNull LyricTextListener listener) {
        List<Method> located = locateParserMethods();
        AndroidLog.logD(TAG, "locateParsers: dexkit=" + located.size());
        if (located.isEmpty()) {
            AndroidLog.logW(TAG, "No lyric parser entry hooked; Kuwo hook path is dead");
            return;
        }

        int hooked = 0;
        for (Method method : located) {
            try {
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
                AndroidLog.logI(TAG, "Hooked parser: " + method.getDeclaringClass().getName()
                    + "." + method.getName());
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Failed to hook parser " + method + ": " + t.getMessage());
            }
        }
        AndroidLog.logI(TAG, "Hooked " + hooked + " lyric parser entry(ies) via DexKit");
    }

    /**
     * 从解析入口抽取明文歌词
     *
     * <p>取<b>入参</b>：调用时宿主手里就是解密后的全文。宿主随后才会做
     * {@code replaceAll} 与逐行裁切，我们拿的是那之前的原始形态。
     */
    private static void handleParse(@Nullable Object rawArg, @NonNull LyricTextListener listener) {
        if (!(rawArg instanceof String raw) || raw.isEmpty()) return;
        listener.onLyricText(raw);
    }

    /**
     * 由 DexKit 直接定位解密入口并挂载
     *
     * <p>取<b>返回值</b>：入参是密文字节，解密后的明文只在返回值里。
     */
    private static void installDecryptors(@NonNull LyricTextListener listener) {
        List<Method> located = locateDecryptMethods();
        AndroidLog.logD(TAG, "locateDecryptors: dexkit=" + located.size());
        if (located.isEmpty()) {
            AndroidLog.logD(TAG, "DexKit located no decrypt method; skipped");
            return;
        }
        int hooked = 0;
        for (Method method : located) {
            try {
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
                AndroidLog.logI(TAG, "Hooked decrypt: " + method.getDeclaringClass().getName()
                    + "." + method.getName());
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Failed to hook decrypt " + method + ": " + t.getMessage());
            }
        }
        AndroidLog.logI(TAG, "Hooked " + hooked + " decrypt entry(ies) via DexKit");
    }

    /**
     * 由 DexKit 定位取词入口并挂载（曲目探针）
     *
     * <p>同时覆盖方法与构造器：两个变体的取词入口形态不同（手机版是方法，
     * 车机版是构造器）。两条路都走包内反射扫，命中哪个算哪个。
     */
    private static void installTrackProbes(@NonNull TrackListener listener) {
        List<Executable> members = new ArrayList<>();
        List<Executable> entryMethods = locateTrackEntries();
        List<Constructor<?>> entryCtors = locateTrackConstructors();
        members.addAll(entryMethods);
        members.addAll(entryCtors);
        AndroidLog.logD(TAG, "locateTrackProbes: methods=" + entryMethods.size()
            + " ctors=" + entryCtors.size());
        if (members.isEmpty()) {
            AndroidLog.logW(TAG, "No (Music, boolean, Music) entry found; track probe unavailable");
            return;
        }

        int hooked = 0;
        for (Executable member : members) {
            try {
                member.setAccessible(true);
                CoreTool.hook(member, new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            Object hostMusic = getArg(0) != null ? getArg(0) : getArg(2);
                            if (hostMusic == null) return;
                            listener.onTrack(
                                readStringField(hostMusic, "rid"),
                                readStringField(hostMusic, "name"),
                                readStringField(hostMusic, "artist"));
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in track probe: " + t.getMessage());
                        }
                    }
                });
                hooked++;
                AndroidLog.logI(TAG, "Hooked track entry: " + member);
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Failed to hook track entry " + member + ": " + t.getMessage());
            }
        }
        AndroidLog.logI(TAG, "Hooked " + hooked + " track entry(ies)");
    }

    /**
     * 检索取词入口（方法与构造器）
     *
     * <p>不用 DexKit：{@code ClassMatcher.methods} 这个匹配器在本版本实测匹配不到构造器
     * （{@code lyric/i} 的 {@code (Music, boolean, Music)} 明明存在）。包名已经由
     * {@link #locateLyricPackage()} 推出，直接在包内反射扫更可靠 —— 歌词包内类数量有限，
     * 一次性枚举的成本可以忽略，且完全不依赖 DexKit 匹配器的具体行为。
     */
    @NonNull
    private static List<Constructor<?>> locateTrackConstructors() {
        List<Constructor<?>> found = new ArrayList<>();
        String lyricPackage = locateLyricPackage();
        if (lyricPackage == null) return found;
        for (Class<?> clazz : classesInPackage(lyricPackage)) {
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                if (isTrackEntry(ctor)) found.add(ctor);
            }
        }
        return found;
    }

    /**
     * 是否为取词入口：参数形如 {@code (Music, boolean, Music)}
     */
    private static boolean isTrackEntry(@NonNull Executable member) {
        Class<?>[] params = member.getParameterTypes();
        if (params.length != 3) return false;
        if (!params[0].getName().endsWith("base.bean.Music")) return false;
        if (params[1] != boolean.class) return false;
        return params[2].getName().endsWith("base.bean.Music");
    }

    /**
     * 枚举指定包内的所有类
     *
     * <p>走 DexKit 按包名列出，不反射 Android 的 {@code DexPathList} —— 那套内部结构
     * 在系统升级后随时会变，且失败时是静默返回空列表，排查困难。
     */
    @NonNull
    private static List<Class<?>> classesInPackage(@NonNull String packageName) {
        List<Class<?>> result = new ArrayList<>();
        try {
            // ClassDataList 会被 findMember 转成 Class[]，必须用 Class[] 接收
            Class<?>[] classes = DexkitCache.findMember(KEY_PACKAGE_CLASSES + packageName,
                new IDexkit<ClassDataList>() {
                    @Override
                    public ClassDataList dexkit(@NonNull DexKitBridge bridge)
                        throws ReflectiveOperationException {
                        ClassDataList found = bridge.findClass(FindClass.create()
                            .searchPackages(packageName)
                        );
                        // 空列表必须返回 null：DexkitCache 会把空列表当有效结果落盘，
                        // 之后每次命中都反序列化出空数组，探针永久失效。
                        return (found == null || found.isEmpty()) ? null : found;
                    }
                });
            if (classes != null) Collections.addAll(result, classes);
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to enumerate package " + packageName + ": " + t.getMessage());
        }
        return result;
    }

    /**
     * 挂载宿主曲目探针（对外入口）。
     *
     * <p>探针本身不参与取词，只为拿到宿主自己的 {@code Music}：其 {@code rid} 是稳定
     * 标识，{@code name}/{@code artist} 是干净曲名。之所以需要它，是因为酷我的
     * {@code MediaMetadata} 会把<b>当前歌词行</b>写进 {@code METADATA_KEY_TITLE}
     * （实测每次回调都变），元数据不可信。
     *
     * @see installTrackProbes 实际的定位与挂载
     */
    public static void installTrackProbe(@NonNull TrackListener listener) {
        installTrackProbes(listener);
    }

    /**
     * 从宿主 {@code Music} 对象上读 rid / 曲名 / 歌手。
     *
     * <p>字段直读而不是调 getter：酷我的 {@code Music} 有 278 个字段、194 个方法，
     * 大量 getter 有副作用（查数据库），而 {@code rid} / {@code name} / {@code artist}
     * 是普通字段。
     */
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

}
