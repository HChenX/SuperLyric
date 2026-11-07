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
 * 酷我 Hook 锚点：按 LRCX 参数名定位歌词包，再按签名查找解析与取词入口。
 * <p>
 * 锚点位于宿主行/词模型之前，避免重叠裁切破坏逐字时间。曲目探针只提供 rid。
 */
public final class KuwoAnchors {
    private static final String TAG = "KuwoAnchors";

    /**
     * 解析入口的缓存键；检索条件变化时必须更换。
     */
    private static final String KEY_PARSER_METHODS = "kuwo_parser_methods_v5";

    /**
     * 解密入口的缓存键
     */
    private static final String KEY_DECRYPT_METHODS = "kuwo_decrypt_methods_v2";

    /**
     * 歌词包的缓存键；键中带返回值形态，复用会导致强转失败。
     */
    private static final String KEY_LYRIC_PACKAGE = "kuwo_lyric_module_anchor_v1";

    /**
     * 包内类列表的缓存键前缀；后接包名，避免不同包共用同一键。
     */
    private static final String KEY_PACKAGE_CLASSES = "kuwo_pkg_classes_v2_";

    /**
     * 解析入口的混淆名，只用于缩小 DexKit 检索范围，不是判据。
     */
    private static final String ANCHOR_PARSER_NAME = "a";

    private static final String ANCHOR_LRCX_PARAM = "lrcx=";

    /**
     * 安装尝试的总次数上限（含首轮）
     */
    private static final int MAX_INSTALL_ATTEMPTS = 3;

    private static final long MIN_INSTALL_RETRY_INTERVAL_MS = 5000L;

    /**
     * 解析入口是否已成功挂上；失败不能置位，否则本进程内不会再有重试。
     */
    private static volatile boolean sParserInstalled;

    /**
     * 解密入口是否已尝试挂载；重试不得再次挂载，否则回调会叠加
     */
    private static boolean sDecryptorsInstalled;

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
     * 取词入口最近上报的 rid；解析入口只拿到正文，由这里转交。
     */
    private static volatile String sCurrentRid = "";

    private KuwoAnchors() {
    }

    /**
     * 取到明文歌词时的回调
     */
    public interface LyricTextListener {
        /**
         * 拿不到时为空串
         *
         * <p>必须由回调自带 rid：宿主的取词入口与曲目探针是两次独立回调；
         * 切歌时探针先翻到新 rid，而歌词回调持有的是上一首正文。若此处读取
         * 共享当前 rid，会把过期正文配上新标识，解析结果被身份校验判掉而丢失。
         */
        void onLyricText(@NonNull String lyricText, @NonNull String rid);
    }

    /**
     * 宿主当前曲目回调
     */
    public interface TrackListener {
        /**
         * @param rid 宿主曲目标识
         */
        void onTrack(@NonNull String rid, @NonNull String title, @NonNull String artist);
    }

    /**
     * 定位并挂载 Hook；失败时不置已安装，留给 retryInstallIfNeeded 重试。
     */
    public static void install(@NonNull LyricTextListener listener) {
        sListener = listener;
        attemptInstall();
    }

    /**
     * 首轮失败后在切歌时重试；有时间间隔与次数上限，避免反复执行 DexKit 全包检索。
     */
    public static boolean retryInstallIfNeeded() {
        if (sParserInstalled) return true;
        return attemptInstall();
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
     * 检索歌词解析入口：单 String 入参、返回歌词包内类型。
     * <p>
     * DexKit 覆盖不在歌词包内的手机版入口；反射扫描覆盖包内的车机版入口，两路都必须执行。
     * 方法名只用于缩小 DexKit 范围，不是判据。空结果必须返回 null，否则会被 DexkitCache
     * 当成有效结果缓存。
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
     * 反射扫描歌词包内的全部类。
     * <p>
     * strictOnly 为真时只收返回歌词模型的方法；两条路径都无结果时调用方才用宽松判据重扫。
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
     * 由 LRCX 参数名定位歌词模块包；定位不到返回 null。
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
     * 挂载曲目探针：只读取宿主 Music 中的 rid、曲名与歌手。
     * <p>
     * 酷我的 MediaMetadata 会把当前歌词行写进 TITLE，因此标识与曲名只信探针。
     */
    public static void installTrackProbe(@NonNull TrackListener listener) {
        installTrackProbes(listener);
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

}
