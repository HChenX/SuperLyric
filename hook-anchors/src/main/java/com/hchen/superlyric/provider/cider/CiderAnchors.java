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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.anchors.AnchorBus;
import com.hchen.superlyric.provider.cider.CiderRawStore.Request;
import com.hchen.superlyric.provider.cider.CiderRawStore.Source;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 截获各来源解析前的 TTML、LRC 与原始响应，异步请求按曲目及来源隔离。
 *
 * <p>最终歌词设置入口只读取来源标记，确认自动模式实际选中了哪条原文；
 * 不读取宿主的歌词行、逐字切片或时序模型。
 */
public final class CiderAnchors {
    private static final String TAG = "CiderAnchors";
    private static final AnchorBus<Capture> BUS = new AnchorBus<>();
    private static final CopyOnWriteArrayList<Runnable> SELECTION_LISTENERS = new CopyOnWriteArrayList<>();
    private static final CiderRawStore RAW = new CiderRawStore();
    private static volatile Access sAccess;
    private static boolean sInstalled;

    private CiderAnchors() {
    }

    public record Capture(String catalogId, List<String> texts, boolean instrumental, String album) {
        public Capture {
            texts = List.copyOf(texts);
        }
    }

    public static AutoCloseable subscribe(@NonNull Consumer<Capture> listener) {
        return BUS.subscribe(listener);
    }

    public static AutoCloseable subscribeSelection(@NonNull Runnable listener) {
        SELECTION_LISTENERS.add(listener);
        return () -> SELECTION_LISTENERS.remove(listener);
    }

    public static synchronized void install() {
        if (sInstalled) return;
        try {
            Class<?> queue = locateClass("cider_queue_class_v1",
                ClassMatcher.create().usingEqStrings("QueueItem(track="));
            Class<?> lyrics = locateClass("cider_lyrics_class_v1",
                ClassMatcher.create().usingEqStrings("Lyrics(lines="));
            Class<?> provider = locateClass("cider_provider_class_v2",
                ClassMatcher.create().usingEqStrings("APPLE", "MUSIXMATCH", "NETEASE", "USER"));
            Class<?> manager = locateClass("cider_lyric_manager_class_v1", ClassMatcher.create()
                .usingEqStrings("audioLocale", "zxx", "lyrics_translation_enabled"));
            Class<?> playback = locateClass("cider_playback_state_class_v1", ClassMatcher.create()
                .addMethod(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.STATIC)
                    .returnType(queue).paramTypes())
                .addMethod(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.STATIC)
                    .returnType(void.class).paramTypes(queue))
                .addMethod(MethodMatcher.create().modifiers(Modifier.PUBLIC | Modifier.STATIC)
                    .returnType(void.class).paramTypes(long.class, queue)));
            Method current = locateMethod("cider_current_queue_method_v2", staticMethod(playback, queue));
            Method load = locateMethod("cider_load_lyrics_method_v2", staticMethod(manager, void.class, queue, provider));
            Method setter = locateMethod("cider_selected_source_method_v2", staticMethod(manager, void.class, lyrics));

            MethodMatcher selectionMatcher = MethodMatcher.create()
                .modifiers(Modifier.PUBLIC | Modifier.STATIC).returnType(void.class)
                .paramTypes(queue, provider).usingEqStrings("lyrics_provider");
            Class<?> selectionOwner = locateClass("cider_selection_owner_class_v2",
                ClassMatcher.create().addMethod(selectionMatcher));
            Method selection = locateMethod("cider_selection_method_v2",
                selectionMatcher.declaredClass(selectionOwner));
            Flow catalog = new Flow(manager, "d");
            Field selectedPreference = locateField("cider_selected_preference_field_v2",
                FieldMatcher.create().modifiers(Modifier.STATIC).type(catalog.type())
                    .addReadMethod(new MethodMatcher(selection)));

            Access access = new Access(current, load, requiredField(queue, "b", String.class),
                requiredField(queue, "e", String.class),
                new Flow(manager, "c"), catalog, new Flow(manager, "e"), new Flow(manager, "i"),
                new Flow(selectedPreference), requiredField(lyrics, "e", provider));

            Class<?> appleApi = locateClass("cider_apple_api_class_v2",
                ClassMatcher.create().usingEqStrings("getLyrics failed for "));
            Method apple = locateMethod("cider_apple_raw_method_v2", MethodMatcher.create()
                .declaredClass(appleApi).returnType(Object.class).paramCount(4)
                .usingEqStrings("getLyrics failed for ", "syllable-lyrics"));
            Class<?> continuation = apple.getParameterTypes()[0];
            Constructor<?> appleState = continuationConstructor("cider_apple_raw_state_v2", apple, continuation);

            Class<?> riseApi = locateClass("cider_rise_api_class_v2", ClassMatcher.create()
                .usingEqStrings("https://exp-rise.cider.sh/api/v1/lyrics/mxm"));
            Method mxm = locateMethod("cider_mxm_raw_method_v2", MethodMatcher.create()
                .declaredClass(riseApi).returnType(Object.class)
                .paramTypes(String.class, String.class, String.class, String.class,
                    int.class, boolean.class, continuation)
                .usingEqStrings("https://exp-rise.cider.sh/api/v1/lyrics/mxm"));
            Method community = locateMethod("cider_community_raw_method_v2", MethodMatcher.create()
                .declaredClass(riseApi).returnType(Object.class).paramTypes(String.class, continuation)
                .usingEqStrings("fetchUserLyrics failed: "));
            Constructor<?> mxmState = continuationConstructor("cider_mxm_raw_state_v2", mxm, continuation);
            Constructor<?> communityState = continuationConstructor("cider_community_raw_state_v2", community, continuation);

            MethodMatcher neteaseSearch = MethodMatcher.create().declaredClass(riseApi)
                .usingEqStrings("https://music.163.com/api/search/get");
            MethodMatcher neteaseEntry = MethodMatcher.create().returnType(Object.class)
                .paramTypes(queue, continuation).addInvoke(neteaseSearch);
            Class<?> neteaseOwner = locateClass("cider_netease_owner_class_v2",
                ClassMatcher.create().addMethod(neteaseEntry));
            Method netease = locateMethod("cider_netease_request_method_v2",
                neteaseEntry.declaredClass(neteaseOwner));
            Constructor<?> neteaseState = continuationConstructor("cider_netease_raw_state_v2", netease, continuation);
            Class<?> lrcParser = locateClass("cider_lrc_parser_class_v2", ClassMatcher.create()
                .usingEqStrings("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)"));
            Method lrc = locateMethod("cider_netease_lrc_method_v2",
                staticMethod(lrcParser, lyrics, String.class, provider));
            Class<?> userResult = locateClass("cider_community_raw_result_class_v2",
                ClassMatcher.create().usingEqStrings("UserLyricsResult(ttml="));
            Class<?> mxmResult = locateClass("cider_mxm_raw_result_class_v2",
                ClassMatcher.create().usingEqStrings("MxmResult(body="));
            RawTexts texts = new RawTexts(userResult, mxmResult);

            RAW.setSelection(sourceOf(access.selection.read()));
            sAccess = access;
            CoreTool.hook(apple, new RawRequestHook(Source.APPLE, 1, 0, null,
                result -> result instanceof String text ? List.of(text) : List.of()));
            CoreTool.hook(appleState, new ContinuationHook());
            CoreTool.hook(mxm, new RawRequestHook(Source.MUSIXMATCH, 0, 6, null, texts::mxm));
            CoreTool.hook(mxmState, new ContinuationHook());
            CoreTool.hook(community, new RawRequestHook(Source.COMMUNITY, 0, 1, null, texts::community));
            CoreTool.hook(communityState, new ContinuationHook());
            CoreTool.hook(netease, new RawRequestHook(Source.NETEASE, 0, 1, access.queueId, null));
            CoreTool.hook(neteaseState, new ContinuationHook());
            CoreTool.hook(lrc, new AbsHook() {
                @Override
                public void before() {
                    Request request = RAW.current();
                    if (request != null && request.source() == Source.NETEASE && getArg(0) instanceof String text) {
                        RAW.capture(request, List.of(text));
                        MainThread.HANDLER.post(() -> publishExplicit(request));
                    }
                }
            });
            CoreTool.hook(setter, new AbsHook() {
                @Override
                public void after() {
                    MainThread.HANDLER.post(CiderAnchors::publishSelected);
                }
            });
            CoreTool.hook(selection, new AbsHook() {
                @Override
                public void before() {
                    Source selected = sourceOf(getArg(1));
                    Object id = CoreTool.getField(access.queueId, getArg(0));
                    RAW.setSelection(id instanceof String catalogId ? catalogId : null, selected, true);
                }

                @Override
                public void after() {
                    // 宿主先更新偏好并启动取词，刷新会话才能读取已生效的来源。
                    if (getThrowable() == null) notifySelectionChanged();
                }
            });
            sInstalled = true;
            AndroidLog.logI(TAG, "Installed selected-source raw lyric acquisition");
        } catch (Throwable error) {
            AndroidLog.logW(TAG, "Raw lyric anchors unavailable: " + error.getMessage());
        }
    }

    public static void request(@Nullable String catalogId) {
        if (catalogId == null || catalogId.isBlank() || sAccess == null) return;
        MainThread.HANDLER.post(() -> {
            Access access = sAccess;
            if (access == null) return;
            try {
                Object queue = access.current.invoke(null);
                if (queue == null || !catalogId.equals(access.queueId.get(queue))) return;
                Object selected = access.selection.read();
                Source source = sourceOf(selected);
                if (RAW.setSelection(catalogId, source, false)) {
                    notifySelectionChanged();
                    return;
                }
                access.load.invoke(null, queue, selected);
                publishSelected();
            } catch (ReflectiveOperationException | IllegalArgumentException error) {
                AndroidLog.logW(TAG, "Raw lyric request failed: " + error.getMessage());
            }
        });
    }

    private static void publishSelected() {
        Access access = sAccess;
        if (access == null) return;
        try {
            Object id = access.catalog.read();
            Object queue = access.current.invoke(null);
            if (!(id instanceof String catalogId) || queue == null || !catalogId.equals(access.queueId.get(queue))) return;
            if (Boolean.TRUE.equals(access.loading.read())) return;
            if (Boolean.TRUE.equals(access.instrumental.read())) {
                BUS.emit(new Capture(catalogId, List.of(), true, (String) access.queueAlbum.get(queue)));
                return;
            }
            Source selected = sourceOf(access.selection.read());
            if (selected != null) {
                List<String> texts = RAW.selected(catalogId, selected);
                if (!texts.isEmpty()) BUS.emit(new Capture(catalogId, texts, false, (String) access.queueAlbum.get(queue)));
                return;
            }
            Object result = access.lyrics.read();
            if (result == null) return;
            // 仅用于确认自动模式最终选源，正文永远从原始载荷缓存获取。
            Source resolved = sourceOf(access.provider.get(result));
            List<String> texts = RAW.selected(catalogId, resolved);
            if (!texts.isEmpty()) BUS.emit(new Capture(catalogId, texts, false, (String) access.queueAlbum.get(queue)));
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            AndroidLog.logW(TAG, "Selected raw lyric unavailable: " + error.getMessage());
        }
    }

    private static void publishExplicit(Request request) {
        Access access = sAccess;
        if (access == null) return;
        try {
            Source selected = sourceOf(access.selection.read());
            if (selected != request.source()) return;
            Object id = access.catalog.read();
            Object queue = access.current.invoke(null);
            if (!request.catalogId().equals(id) || queue == null
                || !request.catalogId().equals(access.queueId.get(queue))) return;
            if (Boolean.TRUE.equals(access.instrumental.read())) return;
            List<String> texts = RAW.selected(request.catalogId(), selected);
            if (!texts.isEmpty()) BUS.emit(new Capture(request.catalogId(), texts, false, (String) access.queueAlbum.get(queue)));
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            AndroidLog.logW(TAG, "Explicit-source raw lyric unavailable: " + error.getMessage());
        }
    }

    private static void notifySelectionChanged() {
        for (Runnable listener : SELECTION_LISTENERS) {
            try {
                listener.run();
            } catch (Throwable error) {
                AndroidLog.logW(TAG, "Source-change listener failed: " + error.getMessage());
            }
        }
    }

    @Nullable
    static Source sourceOf(@Nullable Object provider) {
        if (provider == null) return null;
        if (!(provider instanceof Enum<?> value)) throw new IllegalArgumentException("Unexpected lyric source");
        return switch (value.name()) {
            case "APPLE" -> Source.APPLE;
            case "MUSIXMATCH" -> Source.MUSIXMATCH;
            case "NETEASE" -> Source.NETEASE;
            case "USER" -> Source.COMMUNITY;
            default -> throw new IllegalArgumentException("Unsupported Cider lyric source: " + value.name());
        };
    }

    private static final class RawRequestHook extends AbsHook {
        private final Source source;
        private final int idIndex;
        private final int continuationIndex;
        private final Field queueId;
        private final Function<Object, List<String>> payload;

        private RawRequestHook(Source source, int idIndex, int continuationIndex,
                               @Nullable Field queueId, @Nullable Function<Object, List<String>> payload) {
            this.source = source;
            this.idIndex = idIndex;
            this.continuationIndex = continuationIndex;
            this.queueId = queueId;
            this.payload = payload;
        }

        @Override
        public void before() {
            Object argument = getArg(idIndex);
            Object id = queueId != null && argument != null ? CoreTool.getField(queueId, argument) : argument;
            RAW.enter(id instanceof String text ? text : "", source, getArg(continuationIndex),
                source == Source.APPLE && Boolean.TRUE.equals(getArg(3)));
        }

        @Override
        public void after() {
            try {
                Request request = RAW.current();
                if (request != null && payload != null && getThrowable() == null) {
                    RAW.capture(request, payload.apply(getResult()));
                    MainThread.HANDLER.post(() -> publishExplicit(request));
                }
            } finally {
                RAW.exit();
            }
        }
    }

    private static final class ContinuationHook extends AbsHook {
        @Override
        public void after() {
            RAW.bind(getThisObject());
        }
    }

    private static final class MainThread {
        private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    }

    private record Access(Method current, Method load, Field queueId, Field queueAlbum, Flow lyrics, Flow catalog,
                          Flow loading, Flow instrumental, Flow selection, Field provider) {
    }

    private static final class Flow {
        private final Field field;
        private final Method getValue;

        private Flow(Class<?> owner, String name) throws ReflectiveOperationException {
            this(owner.getDeclaredField(name));
        }

        private Flow(Field field) throws ReflectiveOperationException {
            this.field = field;
            if (!Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException("Cider StateFlow");
            field.setAccessible(true);
            getValue = field.getType().getMethod("getValue");
            getValue.setAccessible(true);
        }

        private Class<?> type() {
            return field.getType();
        }

        private Object read() throws ReflectiveOperationException {
            return getValue.invoke(field.get(null));
        }
    }

    static final class RawTexts {
        private final Class<?> userClass;
        private final Class<?> mxmClass;
        private final Field userTtml;
        private final Field mxmBody;
        private final Field mxmSyllable;
        private final Field mxmLines;

        RawTexts(Class<?> userClass, Class<?> mxmClass) throws ReflectiveOperationException {
            this.userClass = userClass;
            this.mxmClass = mxmClass;
            userTtml = requiredField(userClass, "a", String.class);
            mxmBody = requiredField(mxmClass, "a", String.class);
            mxmSyllable = requiredField(mxmClass, "d", String.class);
            mxmLines = requiredField(mxmClass, "e", String.class);
        }

        List<String> community(@Nullable Object result) {
            return userClass.isInstance(result)
                ? strings(CoreTool.getField(userTtml, result)) : List.of();
        }

        List<String> mxm(@Nullable Object result) {
            return mxmClass.isInstance(result)
                ? strings(CoreTool.getField(mxmSyllable, result),
                    CoreTool.getField(mxmBody, result), CoreTool.getField(mxmLines, result)) : List.of();
        }

        private static List<String> strings(Object... values) {
            return Stream.of(values).filter(String.class::isInstance).map(String.class::cast)
                .filter(text -> !text.isBlank()).distinct().collect(Collectors.toList());
        }
    }

    private static MethodMatcher staticMethod(Class<?> owner, Class<?> result, Class<?>... params) {
        return MethodMatcher.create().declaredClass(owner).modifiers(Modifier.PUBLIC | Modifier.STATIC)
            .returnType(result).paramTypes(params).paramCount(params.length);
    }

    private static Class<?> locateClass(String key, ClassMatcher matcher) throws ReflectiveOperationException {
        Class<?> result = DexkitCache.findMember(key, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) {
                return bridge.findClass(FindClass.create().matcher(matcher)).singleOrNull();
            }
        });
        if (result == null) throw new ClassNotFoundException(key);
        return result;
    }

    private static Method locateMethod(String key, MethodMatcher matcher) throws ReflectiveOperationException {
        Method result = DexkitCache.findMember(key, new IDexkit<MethodData>() {
            @Override
            public MethodData dexkit(DexKitBridge bridge) {
                return bridge.findMethod(FindMethod.create().matcher(matcher)).singleOrNull();
            }
        });
        if (result == null) throw new NoSuchMethodException(key);
        result.setAccessible(true);
        return result;
    }

    private static Field locateField(String key, FieldMatcher matcher) throws ReflectiveOperationException {
        Field result = DexkitCache.findMember(key, new IDexkit<FieldData>() {
            @Override
            public FieldData dexkit(DexKitBridge bridge) {
                return bridge.findField(FindField.create().matcher(matcher)).singleOrNull();
            }
        });
        if (result == null) throw new NoSuchFieldException(key);
        result.setAccessible(true);
        return result;
    }

    private static Constructor<?> continuationConstructor(String key, Method method, Class<?> continuation)
        throws ReflectiveOperationException {
        Class<?> state = locateClass(key, ClassMatcher.create().superClass(continuation.getName())
            .addMethod(MethodMatcher.create().returnType(Object.class).paramTypes(Object.class)
                .addInvoke(new MethodMatcher(method))));
        Constructor<?> constructor = state.getDeclaredConstructor(method.getDeclaringClass(), continuation);
        constructor.setAccessible(true);
        return constructor;
    }

    private static Field requiredField(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type) throw new NoSuchFieldException(owner.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }
}
