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
 * 网易云音乐歌词提供者
 *
 * <p>该 Provider 不提供 Hook 来源，只走官方接口：由 {@code MediaSession} 提供曲目标识，
 * 模块在后台拉取原生 YRC/LRC 并自行解析。宿主 9.6+ 的歌词模型混淆严重且与状态栏、
 * 界面强耦合，Hook 路径的收益低于其脆弱性。
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
        String mediaId = metadataString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID).trim();
        return trackIdentity(metadata, TrackIdentity.stableId(NAMESPACE, mediaId));
    }

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
                        // 未命中同样记录：绕过失败意味着后续 Hook 全部失配，
                        // 日志必须能区分“宿主换了壳类名”与“钩子没有执行”。
                        // newApplication 每个进程只走一次，不会刷屏
                        AndroidLog.logI(TAG, "Application class is " + className + "; no wrapper to bypass");
                    }
                }
            );
        } catch (Throwable t) {
            AndroidLog.logW(TAG, "Failed to bypass NetEase wrapper: " + t.getMessage());
        }
    }

}
