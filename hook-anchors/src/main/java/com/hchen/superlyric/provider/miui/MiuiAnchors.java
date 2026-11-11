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
package com.hchen.superlyric.provider.miui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小米音乐 Hook 锚点，取回宿主当前曲目的 QQ 音乐 songID。
 *
 * <p>小米音乐沿用 QQ 音乐引擎的业务类名，不依赖混淆名。优先挂
 * {@code RemoteControlManager.updataMetaData} 与 {@code MusicPlayerHelper.getPlaySong}；
 * 类不存在时由网络源兜底。
 *
 * <p>只读取 {@code SongInfo} 的 id、名称、歌手和时长；歌词由接口获取，不读取宿主内部
 * 的行/词模型。
 */
public final class MiuiAnchors {
    private static final String TAG = "MiuiAnchors";

    private static final String SONG_INFO_CLASS = "com.tencent.qqmusic.core.song.SongInfo";

    private static final String REMOTE_CONTROL_MANAGER =
        "com.tencent.qqmusiccommon.util.music.RemoteControlManager";

    /**
     * {@code MusicPlayerHelper} 的包名随宿主版本变化，两个位置都尝试。
     */
    private static final String[] HELPER_CLASSES = {
        "com.tencent.qqmusiccommon.util.music.MusicPlayerHelper",
        "com.tencent.qqmusiccommon.util.dialog.MusicPlayerHelper",
    };

    private static final Map<Class<?>, Accessors> ACCESSORS = new ConcurrentHashMap<>();

    private static volatile boolean sInstalled;

    private MiuiAnchors() {
    }

    /**
     * 宿主曲目对象的最小快照。
     */
    public static final class SongSnapshot {
        public final long id;
        @NonNull
        public final String title;
        @NonNull
        public final String artist;
        public final long durationMs;

        public SongSnapshot(long id, @NonNull String title, @NonNull String artist, long durationMs) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
        }

        @NonNull
        @Override
        public String toString() {
            return "SongSnapshot(id=" + id + ", title=" + title + ", artist=" + artist
                + ", duration=" + durationMs + ")";
        }
    }

    /**
     * 捕获到宿主当前曲目时的回调。
     */
    public interface SongInfoListener {
        void onSongInfo(@NonNull SongSnapshot snapshot);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志，不抛出异常。
     *
     * @param listener 宿主报告当前曲目时回调
     */
    public static void install(@NonNull SongInfoListener listener) {
        if (sInstalled) return;
        sInstalled = true;

        try {
            int hooked = 0;
            hooked += hookRemoteControlManager(listener);
            hooked += hookMusicPlayerHelper(listener);

            if (hooked == 0) {
                // 与“挂上了但没触发”必须能区分：这一行缺失时，日志里完全看不出模块有没有生效
                AndroidLog.logW(TAG, "No capture hook installed; only the direct snapshot read is left");
            } else {
                AndroidLog.logI(TAG, "Installed " + hooked + " capture hook(s)");
            }
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install Miui anchors", t);
        }
    }

    /**
     * 直接读取宿主当前的曲目对象。
     *
     * <p>{@code SongInfo} 先于 {@code MediaSession} 元数据更新，取词开始时读到的通常是
     * 当前曲目。
     *
     * @return 当前曲目；宿主类不可用或没有播放曲目时返回 null
     */
    @Nullable
    public static SongSnapshot readCurrentSong() {
        for (String className : HELPER_CLASSES) {
            try {
                Class<?> helperClass = CoreTool.findClassIfExists(className);
                if (helperClass == null) continue;

                Method getInstance = findNoArgMethod(helperClass, "getInstance");
                Method getPlaySong = findNoArgMethod(helperClass, "getPlaySong");
                if (getInstance == null || getPlaySong == null) continue;

                Object helper = getInstance.invoke(null);
                if (helper == null) continue;

                SongSnapshot snapshot = snapshotOf(getPlaySong.invoke(helper));
                if (snapshot != null) return snapshot;
            } catch (Throwable t) {
                AndroidLog.logD(TAG, "readCurrentSong via " + className + " failed: " + t.getMessage());
            }
        }
        return null;
    }

    private static int hookRemoteControlManager(@NonNull SongInfoListener listener) {
        Class<?> clazz = CoreTool.findClassIfExists(REMOTE_CONTROL_MANAGER);
        if (clazz == null) {
            AndroidLog.logW(TAG, REMOTE_CONTROL_MANAGER + " not found");
            return 0;
        }

        List<Method> targets = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"updataMetaData".equals(method.getName())) continue;

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 2 || !isSongInfo(parameterTypes[0])) continue;

            method.setAccessible(true);
            targets.add(method);
        }
        if (targets.isEmpty()) return 0;

        CoreTool.hookAll(targets.toArray(new Method[0]), new AbsHook() {
            @Override
            public void after() {
                SongSnapshot snapshot = snapshotOf(getArg(0));
                if (snapshot != null) listener.onSongInfo(snapshot);
            }
        });
        return targets.size();
    }

    private static int hookMusicPlayerHelper(@NonNull SongInfoListener listener) {
        List<Method> targets = new ArrayList<>();
        for (String className : HELPER_CLASSES) {
            Class<?> clazz = CoreTool.findClassIfExists(className);
            if (clazz == null) continue;

            Method getPlaySong = findNoArgMethod(clazz, "getPlaySong");
            if (getPlaySong == null) continue;

            targets.add(getPlaySong);
        }
        if (targets.isEmpty()) return 0;

        CoreTool.hookAll(targets.toArray(new Method[0]), new AbsHook() {
            @Override
            public void after() {
                SongSnapshot snapshot = snapshotOf(getResult());
                if (snapshot != null) listener.onSongInfo(snapshot);
            }
        });
        return targets.size();
    }

    /**
     * 从宿主曲目对象读取快照。
     *
     * <p>字段缺失按空值处理；id 无效时返回 null。曲名或歌手为空由身份比对拒绝。
     */
    @Nullable
    private static SongSnapshot snapshotOf(@Nullable Object songInfo) {
        if (songInfo == null) return null;

        Accessors accessors = accessorsOf(songInfo.getClass());
        if (accessors.id == null) return null;

        long id = invokeLong(accessors.id, songInfo);
        if (id <= 0L) return null;

        return new SongSnapshot(
            id,
            invokeString(accessors.name, songInfo),
            invokeString(accessors.singer, songInfo),
            invokeLong(accessors.duration, songInfo)
        );
    }

    @NonNull
    private static Accessors accessorsOf(@NonNull Class<?> songInfoClass) {
        Accessors cached = ACCESSORS.get(songInfoClass);
        if (cached != null) return cached;

        Accessors created = new Accessors(
            findNoArgMethod(songInfoClass, "getId"),
            findNoArgMethod(songInfoClass, "getName"),
            findNoArgMethod(songInfoClass, "getSinger"),
            findNoArgMethod(songInfoClass, "getDuration")
        );
        ACCESSORS.put(songInfoClass, created);
        return created;
    }

    @Nullable
    private static Method findNoArgMethod(@NonNull Class<?> clazz, @NonNull String name) {
        Method method = CoreTool.findMethodIfExists(clazz, name);
        if (method == null) return null;
        method.setAccessible(true);
        return method;
    }

    private static boolean isSongInfo(@NonNull Class<?> type) {
        String name = type.getName();
        return SONG_INFO_CLASS.equals(name) || name.endsWith(".SongInfo");
    }

    private static long invokeLong(@Nullable Method method, @NonNull Object target) {
        if (method == null) return 0L;
        try {
            return CoreTool.callMethod(method, target) instanceof Number number ? number.longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    @NonNull
    private static String invokeString(@Nullable Method method, @NonNull Object target) {
        if (method == null) return "";
        try {
            return CoreTool.callMethod(method, target) instanceof String text ? text.trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static final class Accessors {
        @Nullable
        final Method id;
        @Nullable
        final Method name;
        @Nullable
        final Method singer;
        @Nullable
        final Method duration;

        Accessors(@Nullable Method id, @Nullable Method name, @Nullable Method singer,
                  @Nullable Method duration) {
            this.id = id;
            this.name = name;
            this.singer = singer;
            this.duration = duration;
        }
    }
}
