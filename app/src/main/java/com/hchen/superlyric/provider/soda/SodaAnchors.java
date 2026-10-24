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
package com.hchen.superlyric.provider.soda;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.utils.LyricDiag;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 汽水音乐 Hook 锚点：截获宿主的<b>原始歌词正文</b>与它的格式声明
 *
 * <h3>为什么锚在这里</h3>
 * 汽水的歌词模型 {@code com.luna.common.arch.db.entity.lyrics.Lyric} 的构造器签名是
 * {@code (NetLyricType type, String content, NetLyricsLanguage lang, ILunaQueueItem item,
 * boolean …, Function1 …)}。该构造器的字节码先把 {@code content} 按 {@code \n} 切行，
 * 再按 {@code type} 分派给 {@code KrcLyricParser} / {@code LrcLyricParser} ——
 * 也就是说，<b>入参就是宿主自己拿到的那份未加工的歌词文本</b>，格式（LRC / KRC / TEXT）
 * 也由宿主一并给出。
 *
 * <p>相对地，宿主的 {@code Sentence} / {@code Word} 模型是解析之后的产物：字段名被混淆、
 * 译文被塞进 {@code translationMap}，从它反射抠数据等于跟着宿主的内部结构走。
 * 锚在解析之前，宿主改一次模型与本层无关。
 *
 * <p>类名与包名都<b>不是</b>混淆产物（{@code com.luna.*} 全部可读），因此这里按名字定位；
 * 定位不到就明确报错，不硬编码"看起来能兜住"的备选类名。
 *
 * <h3>代价</h3>
 * 只挂一个构造器（含 Kotlin 为默认参数合成的桥接构造器），仅此一处。
 *
 * @author 焕晨HChen
 */
public final class SodaAnchors {
    private static final String TAG = "SodaAnchors";

    /** 歌词实体：宿主解析歌词的入口。 */
    private static final String LYRIC_CLASS = "com.luna.common.arch.db.entity.lyrics.Lyric";

    /** 格式枚举：{@code LRC} / {@code KRC} / {@code TEXT}。 */
    private static final String TYPE_CLASS_SUFFIX = ".NetLyricType";

    /** 语言枚举：区分主歌词与译文。 */
    private static final String LANGUAGE_CLASS_SUFFIX = ".NetLyricsLanguage";

    /** 曲目歌词实体：{@code getTrackId()} 给出该份歌词所属的曲目。 */
    private static final String TRACK_LYRIC_CLASS = "com.luna.common.arch.db.entity.TrackLyric";

    private static volatile boolean sInstalled;

    /** 数据入口上一次已上报的内容指纹，用于压掉构造 / setter / getter 的重复 */
    private static volatile String sLastEntityKey;

    /**
     * 曲目歌词的取回方法（{@code ILunaQueueItem -> TrackLyric}）
     *
     * <p>队列项是接口，方法名被混淆成数字串，只能按<b>返回类型</b>识别；
     * 同一个接口上返回 {@code TrackLyric} 的无参方法只有一个。
     */
    private static volatile Method sQueueTrackLyric;

    private SodaAnchors() {
    }

    /** 截获到一份歌词正文时的回调。 */
    public interface PayloadListener {
        /**
         * @param type     宿主的格式声明（{@code LRC} / {@code KRC} / {@code TEXT}）
         * @param language 语言枚举名；译文与主歌词靠它区分
         * @param payload  原始歌词正文
         * @param trackId  该份歌词所属曲目的标识；无法取得时为 {@code null}
         */
        void onPayload(@NonNull String type,
                       @NonNull String language,
                       @NonNull String payload,
                       @Nullable String trackId);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志。
     */
    public static void install(@NonNull PayloadListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        try {
            Class<?> lyricClass = CoreTool.findClassIfExists(LYRIC_CLASS);
            if (lyricClass == null) {
                AndroidLog.logW(TAG, "Lyric entity not found: " + LYRIC_CLASS
                    + "; soda hook path is dead");
                return;
            }

            int hooked = 0;
            for (Constructor<?> constructor : lyricClass.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                // 判据是前两个参数的类型而不是参数个数：Kotlin 的默认参数会额外合成
                // (…, int, DefaultConstructorMarker) 桥接，按个数筛选会漏掉真实入口
                if (parameterTypes.length < 2) continue;
                if (!parameterTypes[0].getName().endsWith(TYPE_CLASS_SUFFIX)) continue;
                if (parameterTypes[1] != String.class) continue;
                // 桥接构造器只是 `this(…)` 转发，主构造器必然被调用。两边都挂会让每份载荷
                // 被截获两次：解析翻倍、日志翻倍，且两次投递在编排器里只会被收下第一次
                if (isSyntheticBridge(parameterTypes)) continue;

                constructor.setAccessible(true);
                CoreTool.hook(constructor, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            dispatch(
                                enumName(getArg(0)),
                                enumName(getArg(2)),
                                asString(getArg(1)),
                                trackIdOf(getArg(3)),
                                listener
                            );
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in lyric constructor hook: " + t.getMessage());
                        }
                    }
                });
                hooked++;
                AndroidLog.logI(TAG, "Hooked lyric ctor: " + constructor.toGenericString());
            }

            if (hooked == 0) {
                AndroidLog.logW(TAG, "No (NetLyricType, String, …) constructor found on "
                    + LYRIC_CLASS);
            }
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install soda anchors", t);
        }

        installTrackLyricHooks(listener);
    }

    /**
     * 第二锚点：曲目歌词实体 {@code TrackLyric} 的数据入口
     *
     * <h3>为什么还需要它</h3>
     * 汽水的歌词是<b>按需解析</b>的：宿主只在歌词界面活跃、或它自己预取队列时才构造
     * {@code Lyric}。后台播放、快速切歌、以及大量 UGC 视频条目下，解析入口整首都不触发 ——
     * 这不是本模块丢了数据，而是宿主根本没解析。而 {@code TrackLyric} 是宿主装载歌词的
     * <b>数据</b>入口（网络回包写库、读库回放都经过它），与歌词界面是否可见无关。
     *
     * <h3>三处一起挂</h3>
     * {@code lyric} 字段可能由 Room 直接写字段、也可能走 setter，两种都要覆盖；再补一个
     * getter —— 任何读取路径都会把内容带出来。三处都命中同一份内容时由去重键压掉。
     */
    private static void installTrackLyricHooks(@NonNull PayloadListener listener) {
        Class<?> entityClass = CoreTool.findClassIfExists(TRACK_LYRIC_CLASS);
        if (entityClass == null) {
            AndroidLog.logW(TAG, "TrackLyric entity not found: " + TRACK_LYRIC_CLASS);
            return;
        }

        int hooked = 0;
        for (Constructor<?> constructor : entityClass.getDeclaredConstructors()) {
            // 空构造器拿不到内容；带参构造器才是"把字段装进去"的那次
            if (constructor.getParameterCount() == 0) continue;
            try {
                constructor.setAccessible(true);
                CoreTool.hook(constructor, new AbsHook() {
                    @Override
                    public void after() {
                        dispatchEntity(getThisObject(), listener);
                    }
                });
                hooked++;
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Failed to hook TrackLyric ctor: " + t.getMessage());
            }
        }

        try {
            Method setLyric = entityClass.getDeclaredMethod("setLyric", String.class);
            setLyric.setAccessible(true);
            CoreTool.hook(setLyric, new AbsHook() {
                @Override
                public void after() {
                    dispatchEntity(getThisObject(), listener);
                }
            });
            hooked++;
        } catch (Throwable t) {
            AndroidLog.logD(TAG, "No setLyric(String): " + t.getMessage());
        }

        try {
            Method getLyric = entityClass.getDeclaredMethod("getLyric");
            getLyric.setAccessible(true);
            CoreTool.hook(getLyric, new AbsHook() {
                @Override
                public void after() {
                    // getter 里不能再调 getLyric（自递归），内容直接取返回值
                    dispatchContent(asString(getResult()), getThisObject(), listener);
                }
            });
            hooked++;
        } catch (Throwable t) {
            AndroidLog.logD(TAG, "No getLyric(): " + t.getMessage());
        }

        if (hooked == 0) {
            AndroidLog.logW(TAG, "No usable TrackLyric entry; data path unavailable");
        } else {
            AndroidLog.logI(TAG, "Hooked TrackLyric data entries: " + hooked);
        }
    }

    /** 从实体上读出正文与标识 */
    private static void dispatchEntity(@Nullable Object entity, @NonNull PayloadListener listener) {
        if (entity == null) return;
        try {
            dispatchContent(asString(call(entity, "getLyric")), entity, listener);
        } catch (Throwable t) {
            AndroidLog.logD(TAG, "TrackLyric read failed: " + t.getMessage());
        }
    }

    private static void dispatchContent(@NonNull String content,
                                        @Nullable Object entity,
                                        @NonNull PayloadListener listener) {
        if (content.isEmpty()) return;
        String trackId = asString(call(entity, "getTrackId"));
        String type = enumName(call(entity, "getType"));

        // 同一份内容会被构造 / setter / getter 反复带出来，按内容指纹压掉
        String key = trackId + "|" + type + "|" + content.hashCode();
        if (key.equals(sLastEntityKey)) return;
        sLastEntityKey = key;

        // 主歌词语言：TrackLyric 只把译文放进 langTranslations，正文恒为主歌词
        dispatch(type, "CHINESE", content, trackId.isEmpty() ? null : trackId, listener);
    }

    @Nullable
    private static Object call(@Nullable Object target, @NonNull String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void dispatch(@NonNull String type,
                                 @NonNull String language,
                                 @NonNull String payload,
                                 @Nullable String trackId,
                                 @NonNull PayloadListener listener) {
        if (payload.isEmpty()) {
            // 宿主会为"正在加载/确实没有歌词"构造一份空载荷。空载荷不是"没有歌词"的证据，
            // 交回上层按"读不出来"处理，绝不在这里下结论
            AndroidLog.logD(TAG, "Empty lyric payload (type=" + type + ", lang=" + language + ")");
            return;
        }
        LyricDiag.dumpPayload(
            TAG,
            "SODA-PAYLOAD",
            "type=" + type + " lang=" + language + " trackId=" + trackId
                + " length=" + payload.length() + "\n" + payload
        );
        listener.onPayload(type, language, payload, trackId);
    }

    /**
     * 是否为 Kotlin 默认参数合成的桥接构造器
     *
     * <p>这类构造器只做 {@code this(…)} 转发，真正干活的永远是主构造器。两边都挂等于
     * 把同一份载荷截获两次
     */
    private static boolean isSyntheticBridge(@NonNull Class<?>[] parameterTypes) {
        for (Class<?> type : parameterTypes) {
            if ("kotlin.jvm.internal.DefaultConstructorMarker".equals(type.getName())) return true;
        }
        return false;
    }

    /**
     * 取该份歌词所属的曲目标识
     *
     * <p>歌词可能先于元数据到达，也可能属于<b>预加载的下一首</b>；两种情形只能靠标识区分，
     * 拿不到标识时返回 {@code null}，由上层按"本代际内的载荷"处理。
     */
    @Nullable
    private static String trackIdOf(@Nullable Object queueItem) {
        if (queueItem == null) return null;
        try {
            Method getter = sQueueTrackLyric;
            if (getter == null) {
                for (Method method : queueItem.getClass().getMethods()) {
                    if (method.getParameterCount() != 0) continue;
                    if (!TRACK_LYRIC_CLASS.equals(method.getReturnType().getName())) continue;
                    method.setAccessible(true);
                    sQueueTrackLyric = getter = method;
                    break;
                }
            }
            if (getter == null) return null;

            Object trackLyric = getter.invoke(queueItem);
            if (trackLyric == null) return null;

            Method getTrackId = trackLyric.getClass().getMethod("getTrackId");
            getTrackId.setAccessible(true);
            Object id = getTrackId.invoke(trackLyric);
            String trimmed = asString(id);
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Throwable t) {
            // 队列项实现类可能不可访问；标识拿不到只是失去一次校验，不影响取词
            AndroidLog.logD(TAG, "Track id unavailable: " + t.getClass().getSimpleName());
            return null;
        }
    }

    @NonNull
    private static String enumName(@Nullable Object value) {
        if (value instanceof Enum<?> e) return e.name();
        return asString(value);
    }

    @NonNull
    private static String asString(@Nullable Object value) {
        return value instanceof String s ? s : "";
    }
}
