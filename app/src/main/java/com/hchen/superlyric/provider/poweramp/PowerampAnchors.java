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
package com.hchen.superlyric.provider.poweramp;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Poweramp Hook 锚点
 *
 * <h3>为什么只需要两个入口</h3>
 * Poweramp 的整首歌词最终只有两种形态：
 * <ul>
 * <li>{@code cached_lyrics.content} —— 插件或上次解析的结果；</li>
 * <li>{@code lrc_files} 里的路径 —— 同名 LRC 文件。</li>
 * </ul>
 * 前者由 {@link PowerampHookSource} 直接查库；后者交给宿主自己的
 * {@code LrcParser} 解析，本类在解析流上截获原始文本。这样既不依赖歌词界面，
 * 也不需要触碰 Poweramp 的行/词模型。
 *
 * <h3>为什么调用宿主解析器而不是自己读文件</h3>
 * Poweramp 通过它自己的存储通道读取 {@code primary/...} 相对路径；模块直接
 * {@code File} 读取会被 scoped storage 拒绝（实测 EACCES）。
 *
 * @author HChenX
 */
public final class PowerampAnchors {
    private static final String TAG = "PowerampAnchors";

    private static final String ENGINE_CACHE_KEY = "poweramp_lrc_engine_v1";

    /** LrcParser 打开文件失败时的日志串，跨版本稳定。 */
    private static final String ENGINE_ANCHOR = "Failed to open or read LRC path=";

    private static final String TAG_AND_META = "com.maxmpz.audioplayer.scanner.TagAndMeta";
    private static final String LYRICS_ACTIVITY = "com.maxmpz.audioplayer.dialogs.LyricsActivity";
    private static final String REST_PROVIDER = "com.maxmpz.audioplayer.rest.RestProvider";

    /** 单份歌词的捕获上限；超过它多半不是歌词文本。 */
    private static final int MAX_LYRIC_BYTES = 1_000_000;

    private static final Set<String> sSeen = ConcurrentHashMap.newKeySet();

    private static volatile boolean sInstalled;

    /**
     * 宿主自己的可写数据库连接
     *
     * <p>切换曲目时没有歌词界面参与，唯一的完整歌词来源就是数据库缓存。
     * 保存宿主已打开的连接，避免自行打开 WAL 数据库带来的锁问题。
     */
    @Nullable
    private static volatile SQLiteDatabase sDatabase;

    @Nullable
    private static volatile Engine sEngine;

    /**
     * 复用的存储访问器实例
     *
     * <p>宿主的 {@code sl1} 会缓存 ContentProviderClient；每次解析都新建一个
     * 会持续泄漏客户端。解析本身在单线程执行器上串行发生，复用一个实例即可。
     */
    @Nullable
    private static volatile Object sStorage;

    /**
     * 当前线程是否正在执行本模块主动发起的宿主解析
     *
     * <p>宿主歌词界面也会走同一个解析器，且可能在切歌前预加载下一首。
     * 只有本模块为**当前曲目**发起的调用才允许无标题投递，否则会把
     * 预加载的下一首歌词算到当前曲目头上。
     */
    private static final ThreadLocal<Boolean> sHostParse = new ThreadLocal<>();

    private PowerampAnchors() {
    }

    /** 取到候选歌词文本时的回调。 */
    public interface LyricsListener {
        /**
         * @param source     命中点，仅用于诊断
         * @param text       原始歌词文本；也可能是本地 LRC 文件路径
         * @param title      宿主给出的曲名；未知时为空
         * @param artist     宿主给出的歌手；未知时为空
         * @param album      宿主给出的专辑；未知时为空
         * @param durationMs 宿主给出的时长；未知时为 0
         * @param extra      宿主显式给出的曲目标识或路径；可能为空
         */
        void onLyricText(@NonNull String source,
                         @NonNull String text,
                         @NonNull String title,
                         @NonNull String artist,
                         @NonNull String album,
                         long durationMs,
                         @NonNull String extra);
    }

    /**
     * 定位并挂载全部点位。幂等；单个点位失败不影响其它点位。
     */
    public static void install(@NonNull LyricsListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        hookRestProvider();
        hookLrcStorage(listener);
        hookTagAndMeta(listener);
        hookLyricsActivity(listener);
    }

    // ---------------------------------------------------------------- 数据库连接

    private static void hookRestProvider() {
        Class<?> provider = CoreTool.findClassIfExists(REST_PROVIDER);
        if (provider == null) {
            AndroidLog.logW(TAG, REST_PROVIDER + " not found");
            return;
        }
        try {
            Method method = provider.getDeclaredMethod("getWritableDatabase");
            method.setAccessible(true);
            CoreTool.hook(method, new AbsHook() {
                @Override
                public void after() {
                    safe(() -> {
                        if (getResult() instanceof SQLiteDatabase database) {
                            sDatabase = database;
                        }
                    });
                }
            });
            AndroidLog.logI(TAG, "Hooked RestProvider.getWritableDatabase");
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Hook getWritableDatabase failed: " + t);
        }
    }

    /** 宿主当前打开的数据库连接；未就绪时为 null。 */
    @Nullable
    public static SQLiteDatabase getDatabase() {
        return sDatabase;
    }

    // ---------------------------------------------------------------- LRC 解析器

    /**
     * 用宿主自己的 LRC 解析器读取一个歌词路径。
     *
     * <p>调用链与 Poweramp 内部完全一致，因此 {@link #hookLrcParser} 挂上的
     * 解析流钩子会同步拿到原始文本。
     *
     * @param path Poweramp 数据库里的相对路径，例如 {@code primary/Music/x.lrc}
     * @return 是否成功进入宿主解析器
     */
    public static boolean parseLrcViaHost(@NonNull String path) {
        Engine engine = engine();
        if (engine == null) {
            AndroidLog.logW(TAG, "LRC engine unavailable");
            return false;
        }
        Context context = com.hchen.superlyric.publisher.AbsPublisher.getAppContext();
        if (context == null) {
            AndroidLog.logW(TAG, "No app context for host lyric parse");
            return false;
        }

        try {
            Object parser = engine.parserClass.getDeclaredConstructor().newInstance();
            Object storage = sStorage;
            if (storage == null) {
                storage = engine.storageClass.getDeclaredConstructor().newInstance();
                sStorage = storage;
            }
            sHostParse.set(Boolean.TRUE);
            // 字符集由本模块在流上按原始字节判断；宿主自己的解析结果不使用，
            // 传 true 只为让它的解析路径正常走完
            engine.fileMethod.invoke(parser, context, storage, path, true, false, false);
            return true;
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Host lyric parse failed: " + t);
            return false;
        } finally {
            sHostParse.remove();
        }
    }

    /**
     * 在宿主打开 LRC 输入流时截获**原始字节**
     *
     * <p>不能在 `LrcParser` 的 `BufferedReader` 上取文本：宿主用
     * `lrc_files.is_utf8` 选择字符集，而该标志实测不可靠（UTF-8 文件被按
     * Latin-1 解码成乱码）。这里读原始字节后自己按 UTF-8 → GBK 回退解码，
     * 再把同一份字节包回流里，宿主行为不变。
     */
    private static void hookLrcStorage(@NonNull LyricsListener listener) {
        Engine engine = engine();
        if (engine == null) return;

        try {
            engine.openMethod.setAccessible(true);
            CoreTool.hook(engine.openMethod, new AbsHook() {
                @Override
                public void after() {
                    safe(() -> {
                        // 只处理本模块为当前曲目主动发起的解析；宿主歌词界面的
                        // 预加载可能属于下一首，不能算到当前曲目头上
                        if (!Boolean.TRUE.equals(sHostParse.get())) return;
                        String path = asString(getArg(1));
                        if (!path.toLowerCase(Locale.US).endsWith(".lrc")) return;

                        Object value = getResult();
                        if (!(value instanceof InputStream stream)) return;

                        byte[] bytes = readAll(stream);
                        if (bytes == null || bytes.length == 0) return;

                        // 宿主接下来还要读这个流：用同一份字节重建
                        setResult(new ByteArrayInputStream(bytes));

                        String text = decodeLyric(bytes);
                        if (!text.isEmpty()) {
                            listener.onLyricText("lrc.own", text, "", "", "", 0, "");
                        }
                    });
                }
            });
            AndroidLog.logI(TAG, "Hooked Poweramp LRC storage");
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Hook LRC storage failed: " + t);
        }
    }

    /** 读尽输入流；超过上限返回 null。原流会被关闭。 */
    @Nullable
    private static byte[] readAll(@NonNull InputStream stream) {
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream(8192)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_LYRIC_BYTES) {
                    AndroidLog.logW(TAG, "Lyric stream exceeds " + MAX_LYRIC_BYTES + " bytes");
                    return null;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to read lyric stream: " + t);
            return null;
        }
    }

    /**
     * UTF-8 严格解码，失败再退回 GBK。
     *
     * <p>不能用 `new String(bytes, UTF_8)`：它会把非法序列替换成 U+FFFD，
     * 于是乱码也能"成功"解码，永远走不到 GBK 分支。
     */
    @NonNull
    private static String decodeLyric(@NonNull byte[] bytes) {
        int offset = 0;
        int length = bytes.length;
        if (length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            offset = 3;
        } else if (length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return new String(bytes, offset + 2, length - 2, StandardCharsets.UTF_16LE);
        } else if (length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return new String(bytes, offset + 2, length - 2, StandardCharsets.UTF_16BE);
        }

        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length - offset))
                .toString();
        } catch (CharacterCodingException ignored) {
            try {
                return Charset.forName("GBK").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes, offset, length - offset))
                    .toString();
            } catch (CharacterCodingException e) {
                return new String(bytes, offset, length - offset, StandardCharsets.UTF_8);
            }
        }
    }

    @Nullable
    private static Engine engine() {
        Engine cached = sEngine;
        if (cached != null) return cached;

        synchronized (PowerampAnchors.class) {
            if (sEngine != null) return sEngine;

            try {
                Class<?> parserClass = DexkitCache.findMember(
                    ENGINE_CACHE_KEY,
                    new IDexkit<ClassData>() {
                        @Override
                        public ClassData dexkit(DexKitBridge bridge) {
                            ClassDataList classes = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().usingEqStrings(ENGINE_ANCHOR)));
                            return classes == null || classes.isEmpty() ? null : classes.get(0);
                        }
                    }
                );
                if (parserClass == null) {
                    AndroidLog.logW(TAG, "DexKit could not locate LrcParser");
                    return null;
                }

                Method fileMethod = null;
                for (Method method : parserClass.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 6
                        && params[0] == Context.class
                        && params[2] == String.class
                        && params[3] == boolean.class
                        && params[4] == boolean.class
                        && params[5] == boolean.class
                        && method.getReturnType() != void.class) {
                        fileMethod = method;
                    }
                }
                if (fileMethod == null) {
                    AndroidLog.logW(TAG, "LrcParser file entry not found");
                    return null;
                }

                Class<?> storageClass = fileMethod.getParameterTypes()[1];
                Method openMethod = null;
                for (Method method : storageClass.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 2
                        && params[0] == Context.class
                        && params[1] == String.class
                        && method.getReturnType() == InputStream.class) {
                        openMethod = method;
                        break;
                    }
                }
                if (openMethod == null) {
                    AndroidLog.logW(TAG, "Lyric storage opener not found");
                    return null;
                }

                fileMethod.setAccessible(true);
                openMethod.setAccessible(true);
                sEngine = new Engine(parserClass, storageClass, fileMethod, openMethod);
                return sEngine;
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Failed to locate LrcParser: " + t);
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- 标签歌词

    private static void hookTagAndMeta(@NonNull LyricsListener listener) {
        Class<?> tagClass = CoreTool.findClassIfExists(TAG_AND_META);
        if (tagClass == null) {
            AndroidLog.logW(TAG, TAG_AND_META + " not found");
            return;
        }

        // 扫描/读取标签时宿主会给出整首内嵌歌词（USLT/SYLT）
        try {
            Method setTags = tagClass.getDeclaredMethod("setTags",
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class,
                int.class, int.class, int[].class, String[].class);
            setTags.setAccessible(true);
            CoreTool.hook(setTags, new AbsHook() {
                @Override
                public void after() {
                    safe(() -> emitTagLyrics("tag", getThisObject(), listener));
                }
            });
            AndroidLog.logI(TAG, "Hooked TagAndMeta.setTags");
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Hook setTags failed: " + t);
        }

        // 歌词界面打开时，宿主的内部解析结果也从这里经过
        try {
            Method unsync = tagClass.getDeclaredMethod("getUnsyncLyrics");
            unsync.setAccessible(true);
            CoreTool.hook(unsync, new AbsHook() {
                @Override
                public void after() {
                    safe(() -> {
                        Object tag = getThisObject();
                        String text = asString(getResult());
                        if (!text.isEmpty()) {
                            emitText("unsync", text, tag, listener);
                        }
                    });
                }
            });
            AndroidLog.logI(TAG, "Hooked TagAndMeta.getUnsyncLyrics");
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Hook getUnsyncLyrics failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 歌词界面兜底

    private static void hookLyricsActivity(@NonNull LyricsListener listener) {
        Class<?> activity = CoreTool.findClassIfExists(LYRICS_ACTIVITY);
        Class<?> tagClass = CoreTool.findClassIfExists(TAG_AND_META);
        if (activity == null || tagClass == null) return;

        for (Method method : activity.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            // y(TagAndMeta, long, ..., String, ..., ...)
            if (params.length != 6
                || params[0] != tagClass
                || params[1] != long.class
                || params[3] != String.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                CoreTool.hook(method, new AbsHook() {
                    @Override
                    public void before() {
                        safe(() -> {
                            Object tag = getArg(0);
                            emitTagLyrics("activity", tag, listener);
                            String extra = asString(getArg(3));
                            if (!extra.isEmpty()) {
                                listener.onLyricText("activity.extra", extra,
                                    fieldString(tag, "title"), fieldString(tag, "artist"),
                                    fieldString(tag, "album"), fieldLong(tag, "durationMS"), extra);
                            }
                        });
                    }
                });
                AndroidLog.logI(TAG, "Hooked LyricsActivity lyric entry");
                return;
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Hook LyricsActivity failed: " + t);
            }
        }
    }

    private static void emitTagLyrics(@NonNull String source, @Nullable Object tag,
                                      @NonNull LyricsListener listener) {
        if (tag == null) return;
        String text = fieldString(tag, "lyrics");
        if (text.isEmpty()) text = buildLrcFromSync(tag);
        if (!text.isEmpty()) emitText(source, text, tag, listener);
    }

    private static void emitText(@NonNull String source, @NonNull String text,
                                 @NonNull Object tag, @NonNull LyricsListener listener) {
        String title = fieldString(tag, "title");
        String artist = fieldString(tag, "artist");
        // 全库扫描时这个集合会覆盖所有带标签歌词的曲目；设上限避免无界增长
        if (sSeen.size() > 512) sSeen.clear();
        if (!sSeen.add(source + "|" + title + "|" + text.hashCode())) return;
        listener.onLyricText(source, text, title, artist,
            fieldString(tag, "album"), fieldLong(tag, "durationMS"), "");
    }

    /** 把 SYLT 的两个平行数组还原成标准 LRC，复用内核已有的 LRC 解析器。 */
    @NonNull
    private static String buildLrcFromSync(@Nullable Object tag) {
        Object linesValue = field(tag, "syncLyricsLines");
        Object timesValue = field(tag, "syncLyricsTimestamps");
        if (!(linesValue instanceof String[] lines) || !(timesValue instanceof int[] times)) {
            return "";
        }
        int count = Math.min(lines.length, times.length);
        if (count == 0) return "";

        StringBuilder builder = new StringBuilder(count * 32);
        for (int index = 0; index < count; index++) {
            String line = lines[index];
            if (line == null || line.isEmpty()) continue;
            int centis = Math.max(0, times[index]) / 10;
            builder.append('[')
                .append(String.format(Locale.US, "%02d:%02d.%02d",
                    centis / 6000, (centis / 100) % 60, centis % 100))
                .append(']').append(line).append('\n');
        }
        return builder.toString();
    }

    // ---------------------------------------------------------------- 工具

    /** 任何探测异常都必须留在本模块内，绝不能传进宿主进程。 */
    private static void safe(@NonNull Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "probe failure: " + t);
        }
    }

    @Nullable
    private static Object field(@Nullable Object target, @NonNull String name) {
        if (target == null) return null;
        try {
            Field field = target.getClass().getField(name);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @NonNull
    private static String fieldString(@Nullable Object target, @NonNull String name) {
        Object value = field(target, name);
        return value instanceof String text ? text : "";
    }

    private static long fieldLong(@Nullable Object target, @NonNull String name) {
        Object value = field(target, name);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @NonNull
    private static String asString(@Nullable Object value) {
        return value instanceof String text ? text.trim() : "";
    }


    private static final class Engine {
        final Class<?> parserClass;
        final Class<?> storageClass;
        final Method fileMethod;
        final Method openMethod;

        Engine(Class<?> parserClass, Class<?> storageClass,
               Method fileMethod, Method openMethod) {
            this.parserClass = parserClass;
            this.storageClass = storageClass;
            this.fileMethod = fileMethod;
            this.openMethod = openMethod;
        }
    }
}
