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

import android.os.SystemClock;

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
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.InterfacesMatcher;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 酷我 Hook 锚点，取回解密后的 LRCX 全文
 * <p>
 * 手机版与车机版共用逻辑：按 LRCX 参数名定位歌词包，再按签名查找解析入口与取词入口。
 * 锚点位于宿主行/词模型之前，避免其重叠裁切破坏逐字时间。曲目探针只提供
 * {@code Music.rid}，不参与取词。
 *
 * @author 焕晨HChen
 */
public final class KuwoAnchors {
    private static final String TAG = "KuwoAnchors";

    /**
     * 解析入口的缓存键
     *
     * <p>按方法而不是按类缓存：类名随混淆变化，而“单 String 入参、返回歌词实现”的签名
     * 由宿主接口决定，跨版本稳定。宿主升级后缓存失效会自动重新检索。
     *
     * <p>改动检索条件（搜索域、匹配器）必须同时更换键：已有缓存保存的是过期条件的
     * 方法集，继续复用会使新条件不生效。后缀只用于使已有缓存失效，不代表版本语义。
     */
    private static final String KEY_PARSER_METHODS = "kuwo_parser_methods_v5";

    /**
     * 解密入口的缓存键
     */
    private static final String KEY_DECRYPT_METHODS = "kuwo_decrypt_methods_v2";

    /**
     * 歌词包的缓存键
     *
     * <p>键中带返回值形态：{@code ClassData} 与 {@code ClassDataList} 会缓存为不同的
     * Java 类型（{@code Class} 与 {@code Class[]}），复用同一键会强转失败。
     * 更换检索形态时必须更换键。
     */
    private static final String KEY_LYRIC_PACKAGE = "kuwo_lyric_module_anchor_v1";

    /**
     * 包内类列表的缓存键前缀；后接包名，避免不同包共用同一键
     *
     * <p>v2：检索结果补齐 {@code null} 与空列表归一，“空结果”的形态变化，必须更换键；
     * 否则已有缓存中的空数组会被当成有效命中继续复用。
     */
    private static final String KEY_PACKAGE_CLASSES = "kuwo_pkg_classes_v2_";

    /**
     * 解析入口的名字
     *
     * <p>两个变体的解析入口当前都叫 {@code a}（混淆产物）。它不作为判据，只用于缩小
     * DexKit 检索范围：只给 {@code paramCount} 时 DexKit 在该版本匹配不到任何方法，
     * 手机版会因此整条失效。真正判据是 {@link #isLyricParser} 的签名
     * （单 {@code String} 入参 + 返回歌词包内类型）。
     *
     * <p>名字变化不会导致失联：车机版通过反射扫描歌词包，不看名字。
     */
    private static final String ANCHOR_PARSER_NAME = "a";

    /**
     * LRCX 的 URL 查询参数名
     *
     * <p>形如 {@code lrcx=}，是酷我请求逐字歌词时携带的参数名。两个变体的 dex 中各只
     * 出现一次，且只属于歌词模块内的类（车机版在 {@code lyric/h}，即解密类）。
     * 用它定位歌词模块，再由类名推出包名。
     *
     * <p>选择理由：比字段名唯一（{@code lrcx_roma} 会被网络层引用），比类名稳定，
     * 比正则片段精确（{@code \d{1,2}} 会被一批工具类引用）。该参数属于酷我歌词协议，
     * 只要支持逐字歌词就会存在。
     */
    private static final String ANCHOR_LRCX_PARAM = "lrcx=";

    /**
     * 安装尝试的总次数上限（含首轮）
     */
    private static final int MAX_INSTALL_ATTEMPTS = 3;

    /**
     * 两次安装尝试之间的最小间隔
     */
    private static final long MIN_INSTALL_RETRY_INTERVAL_MS = 5000L;

    /**
     * 解析入口是否已成功挂上
     *
     * <p>语义是“成功”而非“尝试过”：{@link #installParsers} 定位不到时只记日志、
     * 不抛异常；若把一次失败当成已安装，本进程内不会再有第二次机会，而 {@link #install}
     * 只在宿主进程注入时调用一次（{@code MusicProvider.onPackageReady()}）。
     *
     * <p>宿主 dex 未完全加载、DexKit 首轮检索超时都会使首次定位落空。表现为歌词时有时无，
     * 重启宿主后恢复。
     */
    private static volatile boolean sParserInstalled;

    /**
     * 解密入口是否已尝试挂载；重试不得再次挂载，否则回调会叠加
     */
    private static boolean sDecryptorsInstalled;

    /**
     * 已尝试安装的次数；用于封顶重试，避免每次切歌重复执行 DexKit 检索
     */
    private static int sInstallAttempts;

    /**
     * 最近一次安装尝试的时刻（{@link SystemClock#elapsedRealtime()}）
     */
    private static long sLastInstallAttemptAt;

    /**
     * 首次安装时传入的回调
     *
     * <p>重试时没有 listener 参数，需要保存它。
     */
    private static volatile LyricTextListener sListener;

    /**
     * 最近一次取词入口上报的 rid
     *
     * <p>宿主的取词入口（{@code (Music, boolean, Music)}）一定早于解析入口调用，
     * 两者属于同一次取词流程。解析入口的入参只有歌词正文，拿不到 rid，因此由这里转交。
     *
     * <p>该字段放在 Java 侧：宿主是单线程同步调用，跨线程传递会使“谁属于哪次取词”
     * 重新变得不确定，而那正是要消除的错配。
     */
    private static volatile String sCurrentRid = "";

    private KuwoAnchors() {
    }

    /**
     * 取到明文歌词时的回调
     */
    public interface LyricTextListener {
        /**
         * @param lyricText 明文歌词全文；LRCX 或普通 LRC
         * @param rid       这份歌词所属曲目的 rid，与 {@link TrackListener#onTrack} 上报同源；
         *                  拿不到时为空串
         *
         *                  <p>必须由回调自带 rid：宿主的取词入口与曲目探针是两次独立回调；
         *                  切歌时探针先翻到新 rid，而歌词回调持有的是上一首正文。若此处读取
         *                  共享当前 rid，会把过期正文配上新标识，解析结果被身份校验判掉而丢失。
         */
        void onLyricText(@NonNull String lyricText, @NonNull String rid);
    }

    /**
     * 宿主当前曲目回调
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
     * 定位并挂载 Hook，可重复调用
     *
     * <p>全工程只有 {@code MusicProvider#onPackageReady()} 一处调用，一个进程一次；
     * 首次定位可能因宿主 dex 未加载完或 DexKit 首轮检索超时而落空。定位失败时不置
     * “已安装”，留给 {@link #retryInstallIfNeeded()} 在切歌时重试；否则解析入口在本进程内
     * 永久缺失，表现为歌词时有时无、重启宿主后恢复。
     */
    public static void install(@NonNull LyricTextListener listener) {
        sListener = listener;
        attemptInstall();
    }

    /**
     * 解析入口尚未挂上时再试一次安装
     *
     * <p>由 {@link KuwoHookSource} 在每次切歌（{@code start()}）时调用：此时宿主已运行，
     * dex 必然加载完毕，是首轮失败后的补救时机。
     *
     * <p>限流与封顶：两次尝试至少间隔 {@link #MIN_INSTALL_RETRY_INTERVAL_MS}，
     * 总次数不超过 {@link #MAX_INSTALL_ATTEMPTS}。DexKit 全包检索开销高，
     * 无节制重试会使每次切歌都执行一次。
     *
     * @return 解析入口当前是否已挂上
     */
    public static boolean retryInstallIfNeeded() {
        if (sParserInstalled) return true;
        return attemptInstall();
    }

    /**
     * 解析入口是否已成功挂上
     */
    public static boolean isParserInstalled() {
        return sParserInstalled;
    }

    /**
     * 执行一次安装尝试
     *
     * @return 解析入口是否已挂上
     */
    private static synchronized boolean attemptInstall() {
        if (sParserInstalled) return true;
        LyricTextListener listener = sListener;
        if (listener == null) return false;
        if (sInstallAttempts >= MAX_INSTALL_ATTEMPTS) return false;

        long now = SystemClock.elapsedRealtime();
        // 首轮不限间隔（sInstallAttempts == 0），重试才限流
        if (sInstallAttempts > 0 && now - sLastInstallAttemptAt < MIN_INSTALL_RETRY_INTERVAL_MS) {
            return false;
        }
        sInstallAttempts++;
        sLastInstallAttemptAt = now;
        AndroidLog.logI(TAG, "Install attempt #" + sInstallAttempts + "/" + MAX_INSTALL_ATTEMPTS);

        // retryInstallIfNeeded 会被 Hook 回调线程调用，异常不得向上抛：
        // 定位链路上的 locateLyricPackage DexKit 调用没有内部 try/catch。
        try {
            if (!installParsers(listener)) {
                AndroidLog.logW(TAG, "Parser entry not hooked yet (attempt #"
                    + sInstallAttempts + "); will retry on a later track change");
            }
            if (!sDecryptorsInstalled) {
                // 解密入口只挂一次：重试针对解析入口，再次挂载解密会使回调叠加
                sDecryptorsInstalled = true;
                installDecryptors(listener);
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Install attempt #" + sInstallAttempts + " failed: " + t.getMessage());
        }
        return sParserInstalled;
    }

    /**
     * 检索歌词解析入口的方法
     *
     * <p>解析入口的签名是唯一稳定的锚：{@code (String) → 歌词对象}。入参是宿主解密的
     * 歌词明文，返回值是其歌词模型。两个变体都按该签名命中：
     * <ul>
     *   <li>手机版：{@code j6/d.a(String)}、{@code j6/f.a(String)}，返回值是
     *       {@code cn.kuwo.mod.lyrics.e}</li>
     *   <li>车机版：{@code cn.kuwo.mod.lyric.g.a(String)}（普通 LRC）与
     *       {@code cn.kuwo.mod.lyric.n.a(String)}（逐字 LRCX），返回值是
     *       {@code cn.kuwo.mod.lyric.a}</li>
     * </ul>
     *
     * <p>两条检索路径互补，都必须执行：
     * <ol>
     *   <li>DexKit：实现歌词包内任意接口的类及其直接子类。手机版解析入口
     *       （{@code j6/d}、{@code j6/f}）不在歌词包内，只有这一路能圈到。</li>
     *   <li>反射扫描歌词包内全部类。车机版解析入口就在歌词包内
     *       （{@code g} / {@code n} 实现包外的解析器接口 {@code a8.b}），按接口实现类
     *       圈定会全部漏掉，只挂到 {@code a(int)} 这类接口实现方法，导致整首没有歌词。</li>
     * </ol>
     * 第二条不走 DexKit 方法检索：包内类列表已由 {@link #classesInPackage} 缓存，
     * 直接反射扫描开销低，也不依赖 DexKit 匹配器行为。
     *
     * <p>方法名只用于缩小 DexKit 检索范围，不是判据：它是混淆产物，也不保证两个变体一致。
     * 真正约束是参数类型（单个 {@code String}）与返回类型（位于歌词包内），见
     * {@link #isLyricParser}。
     *
     * <p>DexKit 侧结果为空时必须返回 {@code null} 而非空列表：DexkitCache 只对 {@code null}
     * 跳过缓存写入；空列表会被当成有效结果落盘，之后命中缓存反序列化出空数据并抛
     * {@code Illegal state}，检索条件修改后不再生效。
     */
    @NonNull
    private static List<Method> locateParserMethods() {
        String lyricPackage = locateLyricPackage();
        if (lyricPackage == null) {
            AndroidLog.logW(TAG, "Lyric module not located; parser hook unavailable");
            return new ArrayList<>();
        }
        // LinkedHashSet：两条路径会命中同一批方法，去重必须按方法身份而不是名字
        Set<Method> found = new LinkedHashSet<>();
        collectParsersByDexKit(lyricPackage, found);
        collectParsersByReflection(lyricPackage, found, true);
        if (found.isEmpty()) {
            // 收紧判据无结果时才退回宽松判据，避免整条路径空手
            collectParsersByReflection(lyricPackage, found, false);
        }
        AndroidLog.logI(TAG, "Parser entries located: " + found.size()
            + " for " + lyricPackage);
        return new ArrayList<>(found);
    }

    /**
     * DexKit 路径：接口实现类 + 其直接子类
     *
     * <p>手机版的解析入口不在歌词包内，只能靠继承体系圈定。
     */
    private static void collectParsersByDexKit(@NonNull String lyricPackage,
                                               @NonNull Set<Method> out) {
        try {
            Method[] located = DexkitCache.findMember(KEY_PARSER_METHODS,
                new IDexkit<MethodDataList>() {
                    @Override
                    public MethodDataList dexkit(@NonNull DexKitBridge bridge)
                        throws ReflectiveOperationException {
                        // 接口名不硬编码：只要实现了歌词包内的接口就算命中
                        ClassDataList implementers = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                .interfaces(InterfacesMatcher.create()
                                    .add(lyricPackage + ".", StringMatchType.StartsWith, true)
                                )
                            )
                        );
                        if (implementers == null || implementers.isEmpty()) {
                            AndroidLog.logW(TAG, "No lyric interface implementer in " + lyricPackage);
                            return null;
                        }

                        // 解析入口在实现类下面再派生一层，所以要补子类
                        ClassDataList domain = new ClassDataList(implementers);
                        for (ClassData implementer : implementers) {
                            ClassDataList subclasses = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                    .superClass(implementer.getName(), StringMatchType.Equals, true)
                                )
                            );
                            if (subclasses != null && !subclasses.isEmpty()) {
                                domain.addAll(subclasses);
                            }
                        }

                        // 参数个数 + 名字共同约束：只给 paramCount 时 DexKit 在该版本匹配不到
                        // 方法（手机版会因此整条 Hook 路径失效），名字必须保留。名字是混淆产物，
                        // 只用于缩小 DexKit 检索范围；真正判据仍是 Java 侧签名（见 isLyricParser），
                        // 车机版路径完全不依赖它。
                        MethodDataList result = bridge.findMethod(FindMethod.create()
                            .searchInClass(domain)
                            .matcher(MethodMatcher.create()
                                .paramCount(1)
                                .name(ANCHOR_PARSER_NAME, StringMatchType.Equals, true)
                            )
                        );
                        return (result == null || result.isEmpty()) ? null : result;
                    }
                });
            if (located == null) return;
            for (Method method : located) {
                if (isLyricParser(method, lyricPackage)) out.add(method);
            }
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "DexKit parser lookup failed: " + t.getMessage());
        }
    }

    /**
     * 反射路径：歌词包内的全部类
     *
     * <p>车机版解析入口在此。不依赖 DexKit 方法检索，只依赖包内类列表；该列表由
     * {@link #classesInPackage} 取得，曲目探针走同一路径。
     *
     * <p>{@code strictOnly} 为真时只收返回类型为歌词模型的方法。收紧是必要的：
     * 歌词包内还有 {@code AIColorPicker$Companion.g(String)} 这类工具方法，
     * 签名同样是单 String 入参、返回歌词包内类型。调用方仅在两条路径合计无结果时
     * 使用宽松判据重扫，避免把工具方法重新加入。
     */
    private static void collectParsersByReflection(@NonNull String lyricPackage,
                                                   @NonNull Set<Method> out,
                                                   boolean strictOnly) {
        int skipped = 0;
        for (Class<?> clazz : classesInPackage(lyricPackage)) {
            Method[] methods;
            try {
                methods = clazz.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method method : methods) {
                if (!isLyricParser(method, lyricPackage)) continue;
                if (strictOnly && !isLyricModelType(method.getReturnType(), lyricPackage)) {
                    skipped++;
                    continue;
                }
                out.add(method);
            }
        }
        if (strictOnly && skipped > 0) {
            AndroidLog.logI(TAG, "Reflection path skipped " + skipped
                + " non-lyric method(s) in " + lyricPackage);
        }
    }

    /**
     * 返回类型是否为歌词模型：歌词包内的接口，或实现了歌词包内接口的类
     *
     * <p>解析入口的返回值必然是宿主自己的歌词模型：车机版返回接口
     * {@code cn.kuwo.mod.lyric.a}，手机版返回接口 {@code cn.kuwo.mod.lyrics.e}。
     * 而同一包里还有颜色选择器一类工具类型，它们不实现歌词接口。
     */
    private static boolean isLyricModelType(@NonNull Class<?> type, @NonNull String lyricPackage) {
        if (type.isInterface()) {
            return type.getName().startsWith(lyricPackage + ".");
        }
        for (Class<?> itf : type.getInterfaces()) {
            if (itf.getName().startsWith(lyricPackage + ".")) return true;
        }
        Class<?> superClass = type.getSuperclass();
        return superClass != null && superClass != Object.class
            && isLyricModelType(superClass, lyricPackage);
    }

    /**
     * 是否为歌词解析入口：单 {@code String} 入参、返回歌词包内的类型、可挂载
     *
     * <p>参数类型这一条不能省：只按"返回歌词对象"会命中接口实现方法（如 {@code a(int)}），
     * 它们永远拿不到歌词正文，挂上去只是噪音。
     *
     * <p>抽象方法与接口一并排除：歌词接口本身带着同签名的抽象方法，
     * 挂上去只会得到 "Cannot hook abstract methods" 的噪音。
     *
     * <p>枚举也排除：{@code LyricsDefine$LyricsType.valueOf(String)} 这类方法签名同样是
     * "单 String 入参、返回歌词包内类型"，但它只是枚举反查，永远不会收到歌词正文。
     */
    private static boolean isLyricParser(@NonNull Method method, @NonNull String lyricPackage) {
        if (Modifier.isAbstract(method.getModifiers())) return false;
        Class<?> declaring = method.getDeclaringClass();
        if (Modifier.isAbstract(declaring.getModifiers())) return false;
        if (declaring.isEnum()) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 || params[0] != String.class) return false;
        return method.getReturnType().getName().startsWith(lyricPackage + ".");
    }

    /**
     * 由 LRCX 参数名定位歌词模块所在的包
     *
     * <p>不硬编码包名：两个变体的歌词包名不同（{@code lyrics} / {@code lyric}），
     * 且都可能随版本变化。使用一个只可能在歌词模块出现的参数名反查所在类，
     * 再由类名推出包名，不依赖固定包名。
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
     * 检索取词入口的方法
     *
     * <p>与 {@link #locateTrackConstructors} 同路：DexKit 的 {@code findMethod} 配
     * {@code searchPackages} 在该版本匹配不到，改用包内反射扫描。
     */
    @NonNull
    private static List<Executable> locateTrackEntries() {
        List<Executable> found = new ArrayList<>();
        String lyricPackage = locateLyricPackage();
        if (lyricPackage == null) return found;
        for (Class<?> clazz : classesInPackage(lyricPackage)) {
            Method[] methods;
            try {
                methods = clazz.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method method : methods) {
                if (isTrackEntry(method)) found.add(method);
            }
        }
        return found;
    }

    /**
     * 检索解密入口的方法
     *
     * <p>签名取 {@code ([B, LyricsType) → String}。取返回值：入参是密文字节，
     * 解密后的明文只在返回值中。解析入口与解密入口各自独立命中：解析入口接收已解密全文，
     * 解密入口位于其前一步；两者都挂上才能覆盖宿主不同的取词路径。
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
                            // 字节处理方法；歌词类型参数是解密入口独有的特征
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
     * 由 DexKit 直接定位解密入口并挂载
     *
     * <p>取返回值：入参是密文字节，解密后的明文只在返回值里。
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
                                listener.onLyricText(text, sCurrentRid);
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

    private static boolean installParsers(@NonNull LyricTextListener listener) {
        List<Method> located = locateParserMethods();
        if (located.isEmpty()) {
            AndroidLog.logW(TAG, "No lyric parser entry hooked; Kuwo hook path is dead");
            return false;
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
        AndroidLog.logI(TAG, "Hooked " + hooked + " lyric parser entry(ies)");
        if (hooked > 0) {
            // 只有真正挂上才置位：部分成功时若允许重试，会对同一个 Method 二次 hook，
            // 回调叠加比"少挂一个入口"更难排查。
            sParserInstalled = true;
        }
        return hooked > 0;
    }

    /**
     * 从解析入口抽取明文歌词
     *
     * <p>取入参：调用时宿主手里就是解密后的全文。宿主随后才会做
     * {@code replaceAll} 与逐行裁切，此处拿到的是裁切前的原始形态。
     */
    private static void handleParse(@Nullable Object rawArg, @NonNull LyricTextListener listener) {
        if (!(rawArg instanceof String raw) || raw.isEmpty()) return;
        listener.onLyricText(raw, sCurrentRid);
    }

    /**
     * 挂载曲目探针
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
        AndroidLog.logI(TAG, "locateTrackProbes: methods=" + entryMethods.size()
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
                            // 先记下本次取词的 rid：解析入口随后就会被调用，它拿不到 rid
                            sCurrentRid = readStringField(hostMusic, "rid");
                            listener.onTrack(
                                sCurrentRid,
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
     * <p>不使用 DexKit：{@code ClassMatcher.methods} 匹配器在该版本匹配不到构造器
     * （{@code lyric/i} 的 {@code (Music, boolean, Music)} 即存在）。包名已由
     * {@link #locateLyricPackage} 推出，直接扫描包内类更可靠；歌词包内类数量有限，
     * 一次枚举成本可忽略，且不依赖 DexKit 匹配器行为。
     */
    @NonNull
    private static List<Constructor<?>> locateTrackConstructors() {
        List<Constructor<?>> found = new ArrayList<>();
        String lyricPackage = locateLyricPackage();
        if (lyricPackage == null) return found;
        for (Class<?> clazz : classesInPackage(lyricPackage)) {
            Constructor<?>[] ctors;
            try {
                ctors = clazz.getDeclaredConstructors();
            } catch (Throwable t) {
                continue;
            }
            for (Constructor<?> ctor : ctors) {
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
     * <p>通过 DexKit 按包名列出，不反射 Android 的 {@code DexPathList}；该内部结构在系统
     * 升级后可能变化，失败时返回空列表，排查困难。
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
                        ClassDataList classes = bridge.findClass(FindClass.create()
                            .searchPackages(packageName)
                        );
                        // 必须归一：DexkitCache 只对 null 跳过缓存写入，
                        // 空列表会被当成有效结果落盘，此后命中缓存只会反序列化出空数据
                        return (classes == null || classes.isEmpty()) ? null : classes;
                    }
                });
            if (classes != null) Collections.addAll(result, classes);
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to enumerate package " + packageName + ": " + t.getMessage());
        }
        return result;
    }

    /**
     * 挂载宿主曲目探针（对外入口）
     *
     * <p>探针不参与取词，只用于取得宿主自己的 {@code Music}：其中 {@code rid} 是稳定标识，
     * {@code name}/{@code artist} 是干净曲名。酷我的 {@code MediaMetadata} 会把当前歌词行
     * 写进 {@code METADATA_KEY_TITLE}，元数据不可信。
     *
     * <p>读不到时不影响运行：车机版的 {@code Music} 字段名全被混淆成单字母，{@code rid}
     * 会落空，标识退化为元数据身份；手机版字段名未混淆，可以读取。
     *
     * @see #installTrackProbes 实际的定位与挂载
     */
    public static void installTrackProbe(@NonNull TrackListener listener) {
        installTrackProbes(listener);
    }

    /**
     * 从宿主 {@code Music} 对象读取字段并转成字符串
     *
     * <p>直读字段而不调用 getter：{@code Music} 有上百个字段与方法，大量 getter 会查询数据库
     * 或产生副作用，而 {@code rid} / {@code name} / {@code artist} 都是普通字段。
     *
     * <p>字段不存在时返回空串而非抛异常：混淆后的字段名（车机版是单字母）会变化；
     * 读取失败只应使标识退化，不应影响宿主。
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
