/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.hook.music.offline;

import android.content.Context;
import android.media.MediaMetadata;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyric.patches.netease.NeteaseLogicHijacking;
import com.hchen.superlyric.utils.MeizuFaker;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 网易云音乐。
 * <p>
 * 数据来源：魅族状态栏歌词控制器 StatusBarLyricController 的歌词监听（m0 子类）。
 * 该监听在切歌 300ms 后才向 LrcLoaderManager 注册；若播放页 / 迷你栏等其他监听已先发起
 * 同一首歌的加载，本地缓存歌词早已分发完毕，迟到的监听只会收到末尾的 Lyric_Version_Not_Update，
 * m0 对该类型既不更新句子也不注册计时器，状态栏歌词就会停在上一首。因此额外监听加载结果：
 * 错过歌词内容时借控制器自身的重载入口重新加载，切歌 / 无歌词时清空上一首的残留。
 * 另外控制器加载时关闭了翻译合并，这里仅对它的请求重新打开，翻译槽位跟随 App 内歌词设置。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.netease.cloudmusic")
public final class Netease extends AbsPublisher {
    /**
     * 控制器重载指令 MUSIC_INFO：与网易云亮屏时自身的重载路径一致。
     */
    private static final int RELOAD_COMMAND = 51;
    /**
     * 同一音轨最多补发的重载次数，避免与其他监听反复竞争时无限重试。
     */
    private static final int MAX_RELOAD_PER_TRACK = 2;
    /**
     * 切歌后等待监听拿到加载结果的时长，超时仍无结果则补发一次重载。
     */
    private static final long TRACK_CHECK_DELAY_MS = 3000L;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Method mMusicInfoMethod;
    private Object mMusicInfoProvider;
    @Nullable
    private Field mControllerField;
    @Nullable
    private Method mReloadMethod;

    // 以下状态仅在主线程读写：歌词与加载回调均由网易云在主线程分发，切歌事件经 mMainHandler 切回主线程
    private long mPublishedMusicId = 0L; // 当前已发布歌词所属音轨，0 = 未发布
    private long mResolvedMusicId = 0L; // 监听已拿到有效结果（歌词 / 确认无歌词）的音轨
    private long mTrackMusicId = 0L; // MediaSession 上报的当前音轨
    private long mReloadMusicId = 0L;
    private int mReloadCount = 0;
    @Nullable
    private Runnable mTrackCheck;

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        super.onPackageReady(param);
        NeteaseLogicHijacking.bypassPackProtection();
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        MeizuFaker.shallowLayerDeviceMock();

        try {
            mMusicInfoMethod = DexkitCache.findMember("music_info", new IDexkit<MethodData>() {
                @NonNull
                @Override
                public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create()
                                .modifiers(Modifier.FINAL)
                                .usingEqStrings("getPlayingMusicInfo")
                                .superClass("java.lang.Object")
                            )
                            .usingEqStrings("getPlayingMusicInfo")
                        )
                    ).single();
                }
            });
            mMusicInfoProvider = getStaticField(
                Arrays.stream(mMusicInfoMethod.getDeclaringClass().getDeclaredFields())
                    .filter(new Predicate<Field>() {
                        @Override
                        public boolean test(Field field) {
                            return Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers());
                        }
                    }).findFirst().orElseThrow()
            );

            Class<?> statusBarLyricController = DexkitCache.findMember("status_bar_lyric", new IDexkit<ClassData>() {
                @NonNull
                @Override
                public ClassData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .usingEqStrings("StatusBarLyricController")
                        )
                    ).single();
                }
            });
            Class<?> lyricListenerClass = null;
            Method lyricMethod = null;
            for (Field declaredField : statusBarLyricController.getDeclaredFields()) {
                try {
                    lyricMethod = declaredField.getType().getDeclaredMethod("onLyricText", String.class, String.class);
                    lyricListenerClass = declaredField.getType();
                    break;
                } catch (NoSuchMethodException ignore) {
                }
            }

            Objects.requireNonNull(lyricMethod);
            hook(lyricMethod,
                new AbsHook() {
                    @Override
                    public void before() {
                        String name = null;
                        String artists = null;
                        String album = null;

                        if (mMusicInfoProvider != null) {
                            Object musicInfo = callMethod(mMusicInfoMethod, mMusicInfoProvider);
                            if (musicInfo != null) {
                                name = (String) callMethod(musicInfo, "getName");
                                artists = (String) callMethod(musicInfo, "getArtistsName");
                                album = (String) callMethod(musicInfo, "getAlbumName");
                            }
                        }

                        List<?> mSentences = (List<?>) getField(getThisObject(), "mSentences");
                        int mCurLyricIndex = (int) getField(getThisObject(), "mCurLyricIndex");

                        // 快速切歌 / 清空瞬间索引可能越界：跳过本轮，状态栏自行负责清空显示
                        if (mSentences == null || mCurLyricIndex < 0 || mCurLyricIndex >= mSentences.size()) {
                            logD(tag, "Offline lyric state not ready, skip: index=" + mCurLyricIndex);
                            return;
                        }

                        Object mSentence = mSentences.get(mCurLyricIndex);
                        String lyric = (String) callMethod(mSentence, "getContent");
                        // 第二参数为网易云按 App 内歌词设置选出的翻译 / 音译（翻译优先、音译优先或仅原文）
                        String translate = (String) getArg(1);
                        if (lyric == null || lyric.isEmpty()) {
                            logD(tag, "Offline lyric empty, skip");
                            return;
                        }
                        int endTime = (int) callMethod(mSentence, "getEndTime");
                        int startTime = (int) callMethod(mSentence, "getStartTime");

                        SuperLyricData data = new SuperLyricData()
                            .setTitle(name)
                            .setArtist(artists)
                            .setAlbum(album)
                            .setLyric(
                                new SuperLyricLine(
                                    lyric,
                                    startTime,
                                    endTime
                                )
                            );
                        if (translate != null && !translate.isBlank()) {
                            data.setTranslation(new SuperLyricLine(translate));
                        }
                        sendLyric(data);
                        long musicId = listenerMusicId(getThisObject());
                        mPublishedMusicId = musicId;
                        mResolvedMusicId = musicId;
                    }
                }
            );

            hookControllerTranslation(statusBarLyricController);
            hookLyricLoadState(statusBarLyricController, lyricListenerClass);
            NeteaseLogicHijacking.hookLockScreenPermission();
        } catch (Throwable throwable) {
            logW(tag, "Status bar lyric hook setup failed, notification lyric fallback will be used", throwable);
            MeizuFaker.hookNotificationLyric();
        }
    }

    /**
     * 控制器发起加载时关闭了翻译合并（请求构建器 o(false)），加载出的句子只有原文与音译。
     * 仅把控制器发起的请求改为合并翻译，其他调用方不受影响；是否展示翻译仍由网易云按 App 内设置决定。
     */
    private void hookControllerTranslation(@NonNull Class<?> controllerClass) {
        try {
            Method translationSwitch = DexkitCache.findMember("netease$lyric_translation", new IDexkit<MethodData>() {
                @NonNull
                @Override
                public MethodData dexkit(@NonNull DexKitBridge bridge) throws ReflectiveOperationException {
                    // 歌词加载请求构建器的合并翻译开关：mode = z ? mode | 2 : mode & 13
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create()
                                .usingStrings("checkIfExist true or in loading")
                            )
                            .paramTypes(boolean.class)
                            .usingNumbers(2, 13)
                        )
                    ).single();
                }
            });
            String controllerName = controllerClass.getName();
            hook(translationSwitch,
                new AbsHook() {
                    @Override
                    public void before() {
                        if (Boolean.FALSE.equals(getArg(0)) && isCalledFrom(controllerName)) {
                            setArg(0, true);
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logW(tag, "Status bar lyric translation hook failed, translation will be unavailable", t);
        }
    }

    private static boolean isCalledFrom(@NonNull String className) {
        String innerPrefix = className + "$";
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String name = element.getClassName();
            if (name.equals(className) || name.startsWith(innerPrefix)) return true;
        }
        return false;
    }

    /**
     * 监听状态栏歌词监听的加载结果与切歌事件：错过歌词内容时重新加载，切歌 / 无歌词时清空残留。
     * <p>
     * 失败只影响兜底能力，不回退到通知栏歌词，避免与逐行发布重复。
     */
    private void hookLyricLoadState(@NonNull Class<?> controllerClass, @NonNull Class<?> lyricListenerClass) {
        try {
            mControllerField = findControllerInstanceField(controllerClass);
            mReloadMethod = findReloadMethod(controllerClass);
            if (mControllerField == null || mReloadMethod == null) {
                logW(tag, "Status bar lyric reload entry not found, missed lyric loads can't be recovered");
            }

            Class<?> lyricInfoClass = findClass("com.netease.cloudmusic.meta.LyricInfo");
            // onLrcLoaded / onError 由基类 m0 声明，hook 会作用于所有歌词监听，需按实例类型过滤
            hook(lyricListenerClass.getMethod("onLrcLoaded", lyricInfoClass),
                new AbsHook() {
                    @Override
                    public void after() {
                        Object lyricInfo = getArg(0);
                        if (lyricInfo != null && lyricListenerClass.isInstance(getThisObject())) {
                            onLyricLoaded(lyricInfo);
                        }
                    }
                }
            );
            hook(lyricListenerClass.getMethod("onError", long.class),
                new AbsHook() {
                    @Override
                    public void after() {
                        if (!lyricListenerClass.isInstance(getThisObject())) return;
                        if (getArg(0) instanceof Long musicId && musicId == currentMusicId()) {
                            requestReload(musicId, "lyric load error");
                        }
                    }
                }
            );

            hookMethod("android.media.session.MediaSession",
                "setMetadata",
                "android.media.MediaMetadata",
                new AbsHook() {
                    @Override
                    public void after() {
                        if (getArg(0) instanceof MediaMetadata metadata) {
                            long musicId = parseMusicId(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
                            if (musicId > 0L) {
                                mMainHandler.post(() -> onTrackChanged(musicId));
                            }
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logW(tag, "Status bar lyric load state hook failed", t);
        }
    }

    private void onLyricLoaded(@NonNull Object lyricInfo) {
        long musicId = toLong(callMethod(lyricInfo, "getMusicId"));
        // m0 只处理当前播放音轨的结果，其余一律忽略
        if (musicId <= 0L || musicId != currentMusicId()) return;

        Object type = callMethod(lyricInfo, "getLyricInfoType");
        String typeName = type instanceof Enum<?> e ? e.name() : String.valueOf(type);
        switch (typeName) {
            case "Lyric_Loaded_Or_Update", "Lyric_In_Local" -> {
                mResolvedMusicId = musicId;
                // 不滚动歌词没有时间轴，m0 不会逐行回调，需清掉上一首残留
                if (Boolean.TRUE.equals(callMethod(lyricInfo, "isUnScrolling"))) {
                    clearStaleLyric(musicId);
                }
            }
            case "Lyric_Version_Not_Update" -> {
                // 迟到的监听只收到「版本未更新」，句子与计时器仍停留在上一首
                if (mResolvedMusicId != musicId) {
                    requestReload(musicId, "only received Lyric_Version_Not_Update");
                }
            }
            case "Lyric_No_Lyrics", "Lyric_Not_Collected", "Lyric_Local_Miss" -> {
                mResolvedMusicId = musicId;
                clearStaleLyric(musicId);
            }
            case "Lyric_Error" -> requestReload(musicId, "Lyric_Error");
            default -> {
            }
        }
    }

    private void onTrackChanged(long musicId) {
        if (musicId == mTrackMusicId) return;
        mTrackMusicId = musicId;
        // 重载次数按每次播放计：切回之前播过的歌时重新给足次数
        mReloadMusicId = musicId;
        mReloadCount = 0;
        // 切歌立即清空上一首歌词，新歌首句到来前不再残留旧的歌曲信息
        clearStaleLyric(musicId);

        if (mTrackCheck != null) mMainHandler.removeCallbacks(mTrackCheck);
        Runnable check = new Runnable() {
            @Override
            public void run() {
                if (mTrackCheck == this) mTrackCheck = null;
                if (mTrackMusicId != musicId || mResolvedMusicId == musicId) return;
                if (currentMusicId() != musicId) return;
                // 息屏期间网易云不处理切歌、加载请求被吞等情况：监听始终没拿到结果
                requestReload(musicId, "no lyric result " + TRACK_CHECK_DELAY_MS + "ms after track change");
            }
        };
        mTrackCheck = check;
        mMainHandler.postDelayed(check, TRACK_CHECK_DELAY_MS);
    }

    /**
     * 已发布的歌词不属于该音轨时清空显示。
     */
    private void clearStaleLyric(long musicId) {
        if (mPublishedMusicId == 0L || mPublishedMusicId == musicId) return;
        mPublishedMusicId = 0L;
        sendStop();
    }

    /**
     * 借控制器自身的重载入口重新加载当前歌曲歌词（控制器 300ms 防抖后重新注册监听）。
     */
    private void requestReload(long musicId, @NonNull String reason) {
        if (mControllerField == null || mReloadMethod == null) return;
        if (mReloadMusicId != musicId) {
            mReloadMusicId = musicId;
            mReloadCount = 0;
        }
        if (mReloadCount >= MAX_RELOAD_PER_TRACK) {
            logD(tag, "Status bar lyric reload limit reached for " + musicId + ": " + reason);
            return;
        }
        mReloadCount++;
        try {
            Object controller = getStaticField(mControllerField);
            if (controller == null) return;
            logD(tag, "Reload status bar lyric for " + musicId
                + " (" + mReloadCount + "/" + MAX_RELOAD_PER_TRACK + "): " + reason);
            callMethod(mReloadMethod, controller, RELOAD_COMMAND);
        } catch (Throwable t) {
            logW(tag, "Failed to reload status bar lyric", t);
        }
    }

    private long currentMusicId() {
        try {
            if (mMusicInfoProvider == null) return 0L;
            Object musicInfo = callMethod(mMusicInfoMethod, mMusicInfoProvider);
            return musicInfo == null ? 0L : toLong(callMethod(musicInfo, "getFilterMusicId"));
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 歌词监听当前持有歌词的音轨（m0.mCurMusicId），读取失败时退回当前播放音轨。
     */
    private long listenerMusicId(@NonNull Object lyricListener) {
        long musicId = 0L;
        try {
            musicId = toLong(getField(lyricListener, "mCurMusicId"));
        } catch (Throwable ignore) {
        }
        return musicId > 0L ? musicId : currentMusicId();
    }

    /**
     * 控制器单例字段：类型为控制器自身的静态字段。
     */
    @Nullable
    private static Field findControllerInstanceField(@NonNull Class<?> controllerClass) {
        for (Field field : controllerClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == controllerClass) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /**
     * 控制器按播放指令重载歌词的入口：唯一的 public void (int) 实例方法，不唯一时放弃。
     */
    @Nullable
    private static Method findReloadMethod(@NonNull Class<?> controllerClass) {
        Method found = null;
        for (Method method : controllerClass.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)) continue;
            if (method.getReturnType() != void.class) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 1 || types[0] != int.class) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private static long parseMusicId(@Nullable String mediaId) {
        if (mediaId == null) return -1L;
        try {
            return Long.parseLong(mediaId.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static long toLong(@Nullable Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
