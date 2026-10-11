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
package com.hchen.superlyric.provider.netease;

import android.content.Context;
import android.media.MediaMetadata;

import androidx.annotation.NonNull;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

import java.util.Objects;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 网易云音乐歌词提供者。
 *
 * <h3>为什么是纯网络取词</h3>
 * 网易云 9.6+ 的歌词模型混淆严重且与状态栏/界面强耦合，Hook 路径的收益远低于其脆弱性。
 * 因此本 Provider **不提供 Hook 来源**，只走官方接口：
 * 由 `MediaSession` 提供曲目标识，模块在后台拉取原生 YRC/LRC 并自行解析。
 *
 * <h3>易盾加固绕过</h3>
 * 网易云用易盾加固壳把真实 Application 包了一层。若不处理，
 * 模块在宿主进程内的初始化会落在壳的 Application 上，后续 Hook 全部失配。
 * 因此在 `Instrumentation#newApplication` 处把参数从壳类改回真实类。
 * 这是本 Provider 唯一需要的"非歌词"Hook，且只挂一个方法。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.netease.cloudmusic")
public class NeteaseProvider extends MusicProvider {
    private static final String TAG = "NeteaseProvider";
    private static final String NAMESPACE = "netease";

    private static final String WRAPPER_APPLICATION = "com.netease.nis.wrapper.MyApplication";
    private static final String REAL_APPLICATION = "com.netease.cloudmusic.CloudMusicApplication";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        // 加固绕过要尽早挂载，先于其余初始化
        bypassPackProtection();
        super.onPackageReady(param);
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new NeteaseApiSource();
    }

    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        String mediaId = readString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID).trim();
        if (!mediaId.isEmpty()) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, mediaId), title, artist, duration);
        }
        return super.buildIdentity(metadata);
    }

    /**
     * 将易盾壳的 Application 类名重定向为真实类名。
     */
    private void bypassPackProtection() {
        try {
            hookMethod("android.app.Instrumentation",
                "newApplication",
                ClassLoader.class, String.class, Context.class,
                new AbsHook() {
                    @Override
                    public void before() {
                        Object className = getArg(1);
                        if (Objects.equals(WRAPPER_APPLICATION, className)) {
                            setArg(1, REAL_APPLICATION);
                            AndroidLog.logI(TAG, "Bypassed NetEase wrapper to CloudMusicApplication");
                            return;
                        }
                        // 没命中也要留痕。本 Provider 的类注释写着：绕过失败意味着"后续 Hook 全部失配"，
                        // 而"宿主换了壳类名"与"这个 Hook 压根没跑到"必须在日志里能分开。
                        // newApplication 每个进程只走一次，不会刷屏
                        AndroidLog.logI(TAG, "Application class is " + className + "; no wrapper to bypass");
                    }
                }
            );
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to bypass NetEase wrapper: " + t.getMessage());
        }
    }

    private static String readString(@NonNull MediaMetadata metadata, @NonNull String key) {
        String value = metadata.getString(key);
        return value != null ? value : "";
    }
}
