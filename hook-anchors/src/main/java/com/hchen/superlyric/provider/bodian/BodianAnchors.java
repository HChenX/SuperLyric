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
package com.hchen.superlyric.provider.bodian;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.dexkitcache.DexkitCache;
import com.hchen.dexkitcache.IDexkit;
import com.hchen.superlyric.anchors.AnchorBus;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;

import java.lang.reflect.Constructor;

/**
 * 波点音乐 Hook 锚点：在 LyricsRunner4Flutter 构造入口截获本地歌词文件路径。
 * <p>
 * 该路径是宿主下载好的原始载荷，绕开会做重叠裁切的宿主行/词模型。只挂这一个构造器。
 */
public final class BodianAnchors {
    private static final String TAG = "BodianAnchors";

    private static final String CACHE_KEY = "bodian_lyrics_runner_class_v1";

    /**
     * 锚点：Flutter 桥接层的类名
     * <p>
     * DexKit 按 {@code ClassMatcher.usingEqStrings(ANCHOR)} 匹配；命中多个类时优先取
     * 非内部类，否则取第一个。
     */
    private static final String ANCHOR = "LyricsRunner4Flutter";

    private static volatile boolean sInstalled;
    private static final AnchorBus<LyricFileEvent> BUS = new AnchorBus<>();

    private BodianAnchors() {
    }

    /**
     * 截获到歌词文件路径或确认没有歌词时的回调
     */
    public interface LyricFileListener {
        /**
         * @param filePath 本地歌词文件路径；为空表示宿主本次没有歌词
         */
        void onLyricFile(@NonNull String filePath,
                         @NonNull String title,
                         @NonNull String artist,
                         @NonNull String album,
                         @NonNull String rid);
    }

    public record LyricFileEvent(@NonNull String filePath, @NonNull String title,
                                 @NonNull String artist, @NonNull String album, @NonNull String rid) {
    }

    public static AutoCloseable subscribe(@NonNull LyricFileListener listener) {
        return BUS.subscribe(event -> listener.onLyricFile(
            event.filePath(), event.title(), event.artist(), event.album(), event.rid()));
    }

    /**
     * 定位并挂载 Hook；幂等，失败只记录日志
     */
    public static void install(@NonNull LyricFileListener listener) {
        subscribe(listener);
        if (sInstalled) return;
        sInstalled = true;

        try {
            Class<?> runnerClass = locateRunnerClass();
            if (runnerClass == null) {
                AndroidLog.logW(TAG, "DexKit could not locate LyricsRunner4Flutter");
                return;
            }

            Constructor<?> target = null;
            for (Constructor<?> constructor : runnerClass.getDeclaredConstructors()) {
                // 按签名选择，不能只数参数个数：该类可能另有 3 参合成或重载构造器，
                // 先命中时第三个参数不是文件路径（转换后为空串），上层会把空路径
                // 当成宿主无歌词并下发纯音乐占位。判据：第 1 参是曲目对象
                // （非基本类型、非 String），第 3 参是 String 文件路径
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length != 3) continue;
                if (parameterTypes[0].isPrimitive() || parameterTypes[0] == String.class) continue;
                if (parameterTypes[2] != String.class) continue;
                target = constructor;
                break;
            }
            if (target == null) {
                AndroidLog.logW(TAG, "No matching (Object, *, String) constructor found on LyricsRunner4Flutter");
                return;
            }

            target.setAccessible(true);
            CoreTool.hook(target, new AbsHook() {
                @Override
                public void after() {
                    Object music = getArg(0);
                    BUS.emit(new LyricFileEvent(
                        getArg(2) instanceof String path ? path.trim() : "",
                        CoreTool.callMethodIfExists(music, "getName") instanceof String name ? name.trim() : "",
                        CoreTool.callMethodIfExists(music, "getArtist") instanceof String artist ? artist.trim() : "",
                        CoreTool.callMethodIfExists(music, "getAlbum") instanceof String album ? album.trim() : "",
                        CoreTool.getFieldIfExists(music, "rid") instanceof String rid ? rid.trim() : ""
                    ));
                }
            });
            AndroidLog.logI(TAG, "Hooked LyricsRunner4Flutter constructor");
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to install Bodian anchors", t);
        }
    }

    @Nullable
    private static Class<?> locateRunnerClass() {
        return DexkitCache.findMember(CACHE_KEY, new IDexkit<ClassData>() {
            @Override
            public ClassData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassDataList classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingEqStrings(ANCHOR)
                    )
                );
                ClassData nonInner = classes.firstOrNull(data -> !data.getName().contains("$"));
                return nonInner != null ? nonInner : classes.firstOrNull();
            }
        });
    }
}
