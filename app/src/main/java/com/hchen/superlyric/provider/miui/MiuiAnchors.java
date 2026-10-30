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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小米音乐的 Hook 锚点：取回宿主当前曲目对象里的 QQ 音乐 songID。
 *
 * <h3>为什么按类名定位</h3>
 * 小米音乐是 QQ 音乐引擎（QQLite）的定制版，{@code com.tencent.qqmusic.core.song.SongInfo}
 * 与 {@code com.tencent.qqmusiccommon.util.music.RemoteControlManager} 是宿主沿用的
 * <b>业务类名</b>，不是混淆产物；同一套类名在 4.42 与 4.46 两个大版本上都未变化。
 * 因此这里不做 DexKit 搜索，直接在类存在时挂载，类不存在时退化为日志与网络兜底。
 *
 * <h3>两条捕获路径</h3>
 * <ul>
 *   <li>{@code RemoteControlManager.updataMetaData(SongInfo, String)}：宿主每次向状态栏 /
 *       通知栏推送歌词时都会调用，参数里就是当前曲目对象；</li>
 *   <li>{@code MusicPlayerHelper.getPlaySong()}：宿主自己的“当前播放曲目”读取入口，
 *       由 {@link #readCurrentSong()} 主动调用，取词开始时即可拿到权威快照。</li>
 * </ul>
 *
 * <p>只读取 {@code SongInfo} 的四个 getter：{@code getId} / {@code getName} /
 * {@code getSinger} / {@code getDuration}。歌词本体不从这里取——宿主模型里的行/词结构
 * 随版本变动，而 songID 在协议层是稳定的。
 *
 * @author 焕晨HChen
 */
public final class MiuiAnchors {
    private static final String TAG = "MiuiAnchors";

    private static final String SONG_INFO_CLASS = "com.tencent.qqmusic.core.song.SongInfo";

    private static final String REMOTE_CONTROL_MANAGER =
        "com.tencent.qqmusiccommon.util.music.RemoteControlManager";

    /**
     * {@code MusicPlayerHelper} 的包名随宿主版本变化（4.42 → {@code util.dialog}，
     * 4.46 → {@code util.music}），全部尝试。
     */
    private static final String[] HELPER_CLASSES = {
        "com.tencent.qqmusiccommon.util.music.MusicPlayerHelper",
        "com.tencent.qqmusiccommon.util.dialog.MusicPlayerHelper",
    };

    private static final Map<Class<?>, Accessors> ACCESSORS = new ConcurrentHashMap<>();

    private static volatile boolean sInstalled;

    private MiuiAnchors() {
    }

    /** 宿主曲目对象的最小快照。 */
    public static final class SongSnapshot {
        public final long id;
        @NonNull
        public final String title;
        @NonNull
        public final String artist;
        public final long durationMs;

        SongSnapshot(long id, @NonNull String title, @NonNull String artist, long durationMs) {
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

    /** 捕获到宿主当前曲目时的回调。 */
    public interface SongInfoListener {
        void onSongInfo(@NonNull SongSnapshot snapshot);
    }

    /**
     * 定位并挂载 Hook。幂等；失败只记录日志，绝不抛出。
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
     * <p>宿主把 {@code SongInfo} 的更新放在 {@code MediaSession} 元数据之前，因此取词开始时
     * 读到的就是新曲目；这正是本路径优于等待 Hook 回调的原因。
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

        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"updataMetaData".equals(method.getName())) continue;

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 2 || !isSongInfo(parameterTypes[0])) continue;

            try {
                method.setAccessible(true);
                CoreTool.hook(method, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            SongSnapshot snapshot = snapshotOf(getArg(0));
                            if (snapshot != null) listener.onSongInfo(snapshot);
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in updataMetaData hook: " + t.getMessage());
                        }
                    }
                });
                hooked++;
                AndroidLog.logD(TAG, "Hooked " + method.toGenericString());
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Failed to hook updataMetaData: " + t.getMessage());
            }
        }
        return hooked;
    }

    private static int hookMusicPlayerHelper(@NonNull SongInfoListener listener) {
        int hooked = 0;
        for (String className : HELPER_CLASSES) {
            Class<?> clazz = CoreTool.findClassIfExists(className);
            if (clazz == null) continue;

            Method getPlaySong = findNoArgMethod(clazz, "getPlaySong");
            if (getPlaySong == null) continue;

            try {
                CoreTool.hook(getPlaySong, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            SongSnapshot snapshot = snapshotOf(getResult());
                            if (snapshot != null) listener.onSongInfo(snapshot);
                        } catch (Throwable t) {
                            AndroidLog.logW(TAG, "Error in getPlaySong hook: " + t.getMessage());
                        }
                    }
                });
                hooked++;
                AndroidLog.logD(TAG, "Hooked " + getPlaySong.toGenericString());
            } catch (Throwable t) {
                AndroidLog.logW(TAG, "Failed to hook getPlaySong: " + t.getMessage());
            }
        }
        return hooked;
    }

    /**
     * 从宿主曲目对象读取快照。
     *
     * <p>任何字段缺失都按“空”处理：曲名或歌手为空只影响身份比对（比对会拒绝它），
     * 不能因为一个 getter 改名就把整个 Hook 路径判死。
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
        try {
            Method method = clazz.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            // 混淆或签名变化时按名字再找一遍无参版本
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || method.getParameterCount() != 0) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static boolean isSongInfo(@Nullable Class<?> type) {
        if (type == null) return false;
        String name = type.getName();
        return SONG_INFO_CLASS.equals(name) || name.endsWith(".SongInfo");
    }

    private static long invokeLong(@Nullable Method method, @NonNull Object target) {
        if (method == null) return 0L;
        try {
            Object value = method.invoke(target);
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    @NonNull
    private static String invokeString(@Nullable Method method, @NonNull Object target) {
        if (method == null) return "";
        try {
            Object value = method.invoke(target);
            return value instanceof String ? ((String) value).trim() : "";
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
