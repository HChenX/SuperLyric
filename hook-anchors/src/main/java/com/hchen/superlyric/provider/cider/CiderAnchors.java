/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
package com.hchen.superlyric.provider.cider;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.anchors.AnchorBus;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Cider 的整首歌词锚点。
 *
 * <p>先用未混淆的模型描述串定位类，再验证字段和入口签名；只观察宿主最终歌词
 * setter，避免把 suspend 的暂停标记或中间歌词挂到当前曲目。Cider 只在播放器界面
 * 加载歌词，因此还要在取词会话建立后主动调用宿主的正常加载入口。
 */
public final class CiderAnchors {
    private static final String TAG = "CiderAnchors";
    private static final AnchorBus<Capture> BUS = new AnchorBus<>();
    private static final AtomicLong CAPTURE_GENERATION = new AtomicLong();
    private static final long CAPTURE_WAIT_MS = 2_500L;
    private static final long CAPTURE_POLL_MS = 100L;
    private static volatile Access sAccess;
    private static boolean sInstalled;

    private CiderAnchors() {
    }

    public record Word(String text, long startMs, long endMs) {
    }

    public record Line(String text, @Nullable String translation, long startMs, long endMs,
                       List<Word> words, boolean empty, @Nullable Line background) {
        public Line {
            words = List.copyOf(words);
        }
    }

    public record Capture(String catalogId, String title, String artist, String album,
                          long durationMs, String timingType, List<Line> lines, boolean instrumental) {
        public Capture {
            lines = List.copyOf(lines);
        }
    }

    public static AutoCloseable subscribe(@NonNull Consumer<Capture> listener) {
        return BUS.subscribe(listener);
    }

    public static synchronized void install() {
        if (sInstalled) return;
        try {
            Class<?> lyrics = locateClass("cider_lyrics_class_v1", "Lyrics(lines=");
            Class<?> line = locateClass("cider_line_class_v1", "LyricLine(startMs=");
            Class<?> word = locateClass("cider_word_class_v1", "LyricWord(startMs=");
            Class<?> queue = locateClass("cider_queue_class_v1", "QueueItem(track=");
            Class<?> manager = locateClass("cider_lyric_manager_class_v1",
                "audioLocale", "zxx", "lyrics_translation_enabled");
            if (lyrics == null || line == null || word == null || queue == null || manager == null) {
                AndroidLog.logW(TAG, "Native lyric classes not located");
                return;
            }
            Class<?> playback = locatePlaybackClass(queue);
            if (playback == null) {
                AndroidLog.logW(TAG, "Current playback state not located");
                return;
            }

            ModelReader reader = new ModelReader(queue, lyrics, line, word);
            Method setter = staticMethod(manager, void.class, lyrics);
            Method load = loadMethod(manager, queue);
            Method current = staticMethod(playback, queue);
            Flow lyricsFlow = new Flow(manager, "c");
            Flow catalogFlow = new Flow(manager, "d");
            Flow instrumentalFlow = new Flow(manager, "i");
            JobAccess job = new JobAccess(manager);
            Access access = new Access(reader, current, load, lyricsFlow, catalogFlow, instrumentalFlow, job);
            CoreTool.hook(setter, new AbsHook() {
                @Override
                public void after() {
                    scheduleCapture(null);
                }
            });
            sAccess = access;
            sInstalled = true;
            AndroidLog.logI(TAG, "Installed native full-song lyric setter: " + manager.getName());
        } catch (Throwable error) {
            AndroidLog.logW(TAG, "Native lyric anchors not ready: " + error.getMessage());
        }
    }

    /**
     * 主线程调用宿主加载入口；执行时仍必须是请求中的曲目，防止异步 post 越过切歌。
     */
    public static void request(@Nullable String catalogId) {
        if (catalogId == null || catalogId.isBlank() || sAccess == null) return;
        MainThread.HANDLER.post(() -> requestOnMain(catalogId));
    }

    private static void requestOnMain(@NonNull String catalogId) {
        Access access = sAccess;
        if (access == null) return;
        try {
            Object queue = access.currentQueue.invoke(null);
            if (!access.reader.belongsTo(queue, catalogId)) return;
            access.loadLyrics.invoke(null, queue, null);
            // 同曲目已加载时入口会直接返回；缓存和早于会话的结果仍需重放完整快照。
            scheduleCapture(catalogId);
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            AndroidLog.logW(TAG, "Native lyric request failed: " + error.getMessage());
        }
    }

    private static void scheduleCapture(@Nullable String catalogId) {
        Access access = sAccess;
        if (access == null) return;
        long generation = CAPTURE_GENERATION.incrementAndGet();
        MainThread.HANDLER.post(new SnapshotTask(access, generation, catalogId));
    }

    private static final class MainThread {
        private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    /**
     * 宿主先写原词，再等待翻译后写第二次。延后到当前 Job 完成才交付完整快照；
     * 翻译失败或超时仍保留可用原词。观察只读，绝不取消或修改宿主 Job。
     */
    private static final class SnapshotTask implements Runnable {
        private final Access access;
        private final long generation;
        private final String requestedId;
        private SnapshotGate gate;

        private SnapshotTask(Access access, long generation, @Nullable String requestedId) {
            this.access = access;
            this.generation = generation;
            this.requestedId = requestedId;
        }

        @Override
        public void run() {
            if (generation != CAPTURE_GENERATION.get()) return;
            try {
                Object id = access.catalogFlow.read();
                Object queue = access.currentQueue.invoke(null);
                if (!(id instanceof String catalogId) || !access.reader.belongsTo(queue, catalogId)) return;
                if (requestedId != null && !requestedId.equals(catalogId)) return;
                // audioLocale=zxx 的宿主 flag 是明确纯音乐；其他 null 只是加载或失败。
                Object flag = access.instrumentalFlow.read();
                if (!(flag instanceof Boolean instrumental)) {
                    throw new IllegalArgumentException("Unexpected Cider instrumental flag");
                }
                Object lyrics = access.lyricsFlow.read();
                if (lyrics == null && !instrumental) return;
                long nowMs = SystemClock.uptimeMillis();
                if (gate == null) gate = new SnapshotGate(generation, catalogId, nowMs);
                Boolean completed;
                try {
                    completed = instrumental || access.job.completed();
                } catch (ReflectiveOperationException | IllegalArgumentException error) {
                    AndroidLog.logW(TAG, "Native lyric completion unavailable: " + error.getMessage());
                    completed = null;
                }
                SnapshotGate.Decision decision = gate.decide(CAPTURE_GENERATION.get(), catalogId,
                    access.reader.catalogIdOf(queue), completed, nowMs);
                if (decision == SnapshotGate.Decision.WAIT) {
                    MainThread.HANDLER.postDelayed(this, CAPTURE_POLL_MS);
                } else if (decision == SnapshotGate.Decision.DELIVER) {
                    Capture capture = access.reader.read(queue, lyrics, instrumental);
                    if (capture.instrumental() || !capture.lines().isEmpty()) BUS.emit(capture);
                }
            } catch (ReflectiveOperationException | IllegalArgumentException
                     | ClassCastException | ConcurrentModificationException error) {
                AndroidLog.logW(TAG, "Native lyric snapshot unavailable: " + error.getMessage());
            }
        }
    }

    static final class SnapshotGate {
        enum Decision { WAIT, DELIVER, DROP }

        private final long generation;
        private final String catalogId;
        private final long startedAtMs;

        SnapshotGate(long generation, String catalogId, long startedAtMs) {
            this.generation = generation;
            this.catalogId = catalogId;
            this.startedAtMs = startedAtMs;
        }

        Decision decide(long currentGeneration, @Nullable String currentId, @Nullable String queueId,
                        @Nullable Boolean completed, long nowMs) {
            if (generation != currentGeneration || !catalogId.equals(currentId) || !catalogId.equals(queueId)) {
                return Decision.DROP;
            }
            return Boolean.FALSE.equals(completed) && nowMs - startedAtMs < CAPTURE_WAIT_MS
                ? Decision.WAIT : Decision.DELIVER;
        }
    }

    @Nullable
    private static Class<?> locateClass(String key, String... anchors) {
        return DexkitCache.findMember(key, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingEqStrings(anchors))).singleOrNull();
            }
        });
    }

    @Nullable
    private static Class<?> locatePlaybackClass(Class<?> queue) {
        return DexkitCache.findMember("cider_playback_state_class_v1", new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                // 另一个站点播放状态也有 QueueItem getter/setter；进度入口排除该候选。
                ClassMatcher matcher = ClassMatcher.create()
                    .addMethod(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.STATIC)
                        .returnType(queue).paramCount(0))
                    .addMethod(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.STATIC)
                        .returnType(void.class).paramTypes(queue))
                    .addMethod(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.STATIC)
                        .returnType(void.class).paramTypes(long.class, queue));
                return bridge.findClass(FindClass.create().matcher(matcher)).singleOrNull();
            }
        });
    }

    private static Method staticMethod(Class<?> owner, Class<?> result, Class<?>... parameters)
        throws NoSuchMethodException {
        Method found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
            if (method.getReturnType() != result || !Arrays.equals(method.getParameterTypes(), parameters)) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous method in " + owner.getName());
            found = method;
        }
        if (found == null) throw new NoSuchMethodException(owner.getName() + Arrays.toString(parameters));
        found.setAccessible(true);
        return found;
    }

    private static Method loadMethod(Class<?> manager, Class<?> queue) throws NoSuchMethodException {
        Method found = null;
        for (Method method : manager.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                || params.length != 2 || params[0] != queue || !params[1].isEnum()) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous lyric loader in " + manager.getName());
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("Lyric loader in " + manager.getName());
        found.setAccessible(true);
        return found;
    }

    private record Access(ModelReader reader, Method currentQueue, Method loadLyrics,
                          Flow lyricsFlow, Flow catalogFlow, Flow instrumentalFlow, JobAccess job) {
    }

    private static final class JobAccess {
        private final Field field;
        private final Method isCompleted;

        private JobAccess(Class<?> manager) throws ReflectiveOperationException {
            field = manager.getDeclaredField("b");
            if (!Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException("Cider lyric Job");
            field.setAccessible(true);
            // 宿主 Job.isCompleted 被混淆为 U；只调用签名完全一致的公开方法。
            isCompleted = field.getType().getMethod("U");
            if (isCompleted.getReturnType() != boolean.class || Modifier.isStatic(isCompleted.getModifiers())) {
                throw new NoSuchMethodException("Cider lyric Job.isCompleted");
            }
            isCompleted.setAccessible(true);
        }

        private boolean completed() throws ReflectiveOperationException {
            Object job = field.get(null);
            return job == null || Boolean.TRUE.equals(isCompleted.invoke(job));
        }
    }

    private static final class Flow {
        private final Field field;
        private final Method getValue;

        private Flow(Class<?> owner, String name) throws ReflectiveOperationException {
            field = owner.getDeclaredField(name);
            if (!Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(owner.getName() + "." + name);
            field.setAccessible(true);
            getValue = field.getType().getMethod("getValue");
            getValue.setAccessible(true);
        }

        private Object read() throws ReflectiveOperationException {
            return getValue.invoke(field.get(null));
        }
    }

    /** 字段名来自 APK 的原始 DEX；JADX 输出中的 f2018c 等名称只是反编译别名。 */
    static final class ModelReader {
        private final Class<?> queueClass;
        private final Class<?> lyricsClass;
        private final Class<?> lineClass;
        private final Class<?> wordClass;
        private final Field catalogId;
        private final Field title;
        private final Field artist;
        private final Field album;
        private final Field duration;
        private final Field lines;
        private final Field timing;
        private final Field lineStart;
        private final Field lineEnd;
        private final Field lineText;
        private final Field lineWords;
        private final Field translation;
        private final Field empty;
        private final Field background;
        private final Field wordStart;
        private final Field wordEnd;
        private final Field wordText;

        ModelReader(Class<?> queueClass, Class<?> lyricsClass, Class<?> lineClass, Class<?> wordClass)
            throws ReflectiveOperationException {
            this.queueClass = queueClass;
            this.lyricsClass = lyricsClass;
            this.lineClass = lineClass;
            this.wordClass = wordClass;
            catalogId = field(queueClass, "b", String.class);
            title = field(queueClass, "c", String.class);
            artist = field(queueClass, "d", String.class);
            album = field(queueClass, "e", String.class);
            duration = field(queueClass, "g", long.class);
            lines = field(lyricsClass, "a", List.class);
            timing = lyricsClass.getDeclaredField("b");
            if (!timing.getType().isEnum() || Modifier.isStatic(timing.getModifiers())) {
                throw new NoSuchFieldException("Cider lyric timing enum");
            }
            timing.setAccessible(true);
            lineStart = field(lineClass, "a", long.class);
            lineEnd = field(lineClass, "b", long.class);
            lineText = field(lineClass, "c", String.class);
            lineWords = field(lineClass, "d", List.class);
            translation = field(lineClass, "e", String.class);
            empty = field(lineClass, "i", boolean.class);
            background = field(lineClass, "j", lineClass);
            wordStart = field(wordClass, "a", long.class);
            wordEnd = field(wordClass, "b", long.class);
            wordText = field(wordClass, "c", String.class);
        }

        boolean belongsTo(@Nullable Object queue, @Nullable String requestedId) throws IllegalAccessException {
            return requestedId != null && !requestedId.isBlank() && queueClass.isInstance(queue)
                && requestedId.equals(catalogId.get(queue));
        }

        @Nullable
        String catalogIdOf(@Nullable Object queue) throws IllegalAccessException {
            return queueClass.isInstance(queue) ? (String) catalogId.get(queue) : null;
        }

        Capture read(Object queue, @Nullable Object lyrics, boolean instrumental)
            throws ReflectiveOperationException {
            requireInstance(queueClass, queue);
            List<Line> copied = new ArrayList<>();
            String timingType = "None";
            if (!instrumental) {
                requireInstance(lyricsClass, lyrics);
                for (Object line : listValue(lines, lyrics)) copied.add(readLine(line, true));
                Object timingValue = timing.get(lyrics);
                if (!(timingValue instanceof Enum<?> nativeTiming)) {
                    throw new IllegalArgumentException("Missing Cider lyric timing");
                }
                timingType = nativeTiming.name();
            }
            return new Capture(textValue(catalogId, queue), textValue(title, queue),
                textValue(artist, queue), textValue(album, queue), duration.getLong(queue),
                timingType, copied, instrumental);
        }

        private Line readLine(Object nativeLine, boolean includeBackground) throws ReflectiveOperationException {
            requireInstance(lineClass, nativeLine);
            List<Word> copied = new ArrayList<>();
            for (Object word : listValue(lineWords, nativeLine)) {
                requireInstance(wordClass, word);
                copied.add(new Word(textValue(wordText, word), wordStart.getLong(word), wordEnd.getLong(word)));
            }
            Object nested = includeBackground ? background.get(nativeLine) : null;
            return new Line(textValue(lineText, nativeLine), (String) translation.get(nativeLine),
                lineStart.getLong(nativeLine), lineEnd.getLong(nativeLine), copied, empty.getBoolean(nativeLine),
                nested == null ? null : readLine(nested, false));
        }

        private static void requireInstance(Class<?> type, Object value) {
            if (!type.isInstance(value)) throw new IllegalArgumentException("Unexpected Cider model: " + type.getName());
        }

        private static List<?> listValue(Field field, Object model) throws IllegalAccessException {
            Object value = field.get(model);
            if (value instanceof List<?> list) return list;
            throw new IllegalArgumentException("Missing Cider lyric list: " + field.getName());
        }

        private static String textValue(Field field, Object model) throws IllegalAccessException {
            Object value = field.get(model);
            if (value instanceof String text) return text;
            throw new IllegalArgumentException("Missing Cider text: " + field.getName());
        }

        private static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            if (!type.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers())) {
                throw new NoSuchFieldException(owner.getName() + "." + name + " must be " + type.getName());
            }
            field.setAccessible(true);
            return field;
        }
    }
}
