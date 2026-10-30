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
package com.hchen.superlyric.publisher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.superlyric.bridge.LyricDocAdapter;
import com.hchen.superlyric.kernel.model.LyricDoc;
import com.hchen.superlyric.utils.HotfixDisabler;
import com.hchen.superlyric.utils.LyricSanitizer;
import com.hchen.superlyricapi.Publisher;
import com.hchen.superlyricapi.SuperLyric;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.model.Lyric;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 宿主进程中的底层发布基类
 *
 * <p>统一管理宿主上下文、版本信息与系统 SuperLyricService 的发布链路。
 *
 * <p>按「音乐软件仅负责发布完整歌词，系统框架负责分发」的边界，宿主侧只发送：
 * <ul>
 *   <li>全量歌词：{@link #publishFullLyric}；</li>
 *   <li>清除：{@link #publishStop}。</li>
 * </ul>
 *
 * <p>进度推进由系统服务端的唯一时钟负责，宿主不发布任何进度包。
 *
 * @author 焕晨HChen
 */
public abstract class AbsPublisher extends AbsModule {
    private static final String TAG = "AbsPublisher";
    private static final Object sPublishLock = new Object();

    private static volatile Context sAppContext;
    private static volatile String sPackageName;
    private static volatile long sVersionCode = -1L;
    private static volatile String sVersionName = "unknown";
    private static volatile boolean sContextFallbackLogged;

    @CallSuper
    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        HotfixDisabler.disableAllHotfixes();
    }

    @CallSuper
    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        sAppContext = context.getApplicationContext();
        ModuleData.setClassLoader(context.getClassLoader());
        sPackageName = context.getPackageName();
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(sPackageName, 0);
            sVersionName = packageInfo.versionName;
            sVersionCode = packageInfo.getLongVersionCode();
            logI(tag, "Loaded publisher for: " + sPackageName + " (v" + sVersionName + ", " + sVersionCode + ")");
        } catch (PackageManager.NameNotFoundException e) {
            logE(tag, "Failed to retrieve package info: " + sPackageName, e);
        }
    }

    @Nullable
    public static Context getAppContext() {
        if (sAppContext != null) {
            return sAppContext;
        }
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object app = activityThreadClass.getMethod("currentApplication").invoke(null);
            if (app instanceof Context ctx) {
                sAppContext = ctx.getApplicationContext();
                return sAppContext;
            }
        } catch (Throwable t) {
            if (!sContextFallbackLogged) {
                sContextFallbackLogged = true;
                AndroidLog.logW("AbsPublisher",
                    "Cannot resolve Application context via ActivityThread", t);
            }
        }
        return null;
    }

    public static String getPackageName() {
        return sPackageName;
    }

    public static long getVersionCode() {
        return sVersionCode;
    }

    public static String getVersionName() {
        return sVersionName;
    }

    /**
     * 发送全量歌词数据包（歌词解析完成或确认无歌词时调用）
     *
     * <p>清洗与模型转换都在发布前完成：4.0 的 {@link Lyric} 构造器会校验 id、时长与行。
     * 逐字时序的保护（越界切片会让消费端 {@code Canvas.drawTextRun} 崩溃）由
     * {@link LyricSanitizer} 唯一负责。
     */
    public static void publishFullLyric(@NonNull LyricDoc doc) {
        SuperLyricData raw = LyricDocAdapter.INSTANCE.toFullData(doc);
        SuperLyricData clean = LyricSanitizer.sanitizeData(raw);
        if (clean == null) {
            AndroidLog.logW(TAG, "Dropping lyric: sanitization failed");
            return;
        }

        Lyric lyric = LyricDocAdapter.INSTANCE.toLyric(LyricDocAdapter.INSTANCE.toDoc(clean));
        if (lyric == null) {
            AndroidLog.logW(TAG, "Dropping lyric: no publishable line");
            return;
        }
        if (lyric.getTitle().trim().isEmpty()) {
            // 4.0 的 Publisher 要求 title 非空白；在此拦截，避免 IllegalArgumentException
            // 从 host 进程抛给调用方。
            AndroidLog.logW(TAG, "Dropping lyric: title is blank (lyricId=" + lyric.getId() + ")");
            return;
        }

        synchronized (sPublishLock) {
            try {
                Publisher.PublishResult result = SuperLyric.publisher().publish(lyric);
                if (result != Publisher.PublishResult.OK) {
                    // REJECTED 表示服务端拒收，多为切歌竞态导致的内容与当前曲目不一致；
                    // 留痕用于区分"服务端未安装"与"服务端拒收"
                    AndroidLog.logW(TAG,
                        "Publish result: " + result + " (lyricId=" + lyric.getId() + ")");
                }
            } catch (Throwable t) {
                AndroidLog.logE(TAG, "Failed to publish full lyric", t);
            }
        }
    }

    /**
     * 清除当前歌词（切歌与停止时调用）
     */
    public static void publishStop() {
        synchronized (sPublishLock) {
            try {
                SuperLyric.publisher().clear();
            } catch (Throwable t) {
                AndroidLog.logE(TAG, "Failed to clear lyric", t);
            }
        }
    }
}
