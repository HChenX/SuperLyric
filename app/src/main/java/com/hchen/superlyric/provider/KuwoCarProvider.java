/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
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
package com.hchen.superlyric.provider;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.AndroidLog;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.publisher.UnifiedLyricProvider;
import com.hchen.superlyric.publisher.engine.IHookLyricEngine;
import com.hchen.superlyric.publisher.model.ProviderCapability;
import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyricapi.SuperLyricData;

import java.lang.reflect.Method;

/**
 * 酷我音乐（车机版）统一歌词提供者。
 * <p>
 * <b>逆向适配与架构设计说明（{@code cn.kuwo.kwmusiccar} 车机版 7.3.9.23）：</b>
 * <ul>
 *   <li><b>宿主自带官方发布通道：</b>
 *       与手机版不同，车机版宿主 <b>已内置</b>
 *       {@code cn.kuwo.kwmusiccar.floatlyric.SuperLyricApiPublisher}，该类直接引用
 *       {@code com.hchen.superlyricapi.SuperLyricHelper}：内部通过
 *       {@code ensureRegistered()} 完成发布者注册与会话监听开关
 *       （具备 {@code sRegisteredPrefChecked} 幂等保护），并通过
 *       {@code startLoop(Context)} 在主线程 {@code Handler} 上维持心跳循环
 *       （具备 {@code sHandler != null} 幂等保护，重复调用无副作用）；</li>
 *   <li><b>主动触发而非重复 Hook：</b>
 *       因此车机版采取「主动注册 + 触发托管」策略——模块不重复实现歌词拦截通道，
 *       而是在宿主 {@code Application} 创建完成后反射调用宿主的
 *       {@code ensureRegistered()} 与 {@code startLoop(Context)}，
 *       确保官方 API 发布器被尽早、稳定地激活，从而复用宿主最强的原生歌词管线；</li>
 *   <li><b>元数据护航：</b>
 *       基类 {@link UnifiedLyricProvider} 统一挂载 {@code MediaSession} 元数据与播放状态 Hook，
 *       为系统 SuperLyricService 提供可靠的曲目上下文与进度推演；</li>
 *   <li><b>零破坏保证：</b>
 *       全程仅做反射调用，不修改宿主任何字段、不替换任何方法实现、不阻断原有调用链；
 *       即便宿主类缺失或版本变更导致反射失败，也仅输出告警日志，绝不影响宿主运行。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "cn.kuwo.kwmusiccar")
public class KuwoCarProvider extends UnifiedLyricProvider {
    private static final String TAG = "KuwoCarProvider";

    /**
     * 车机版复用宿主内置的官方歌词发布通道，整首歌词由宿主直接投递，
     * 模块自身不安装 Hook 切点、亦无网络兜底引擎。
     */
    @NonNull
    @Override
    public ProviderCapability capability() {
        return ProviderCapability.FULL_HOOK_ONLY;
    }

    /**
     * 车机版内置的 SuperLyric 官方 API 发布器。
     */
    private static final String CLS_CAR_PUBLISHER = "cn.kuwo.kwmusiccar.floatlyric.SuperLyricApiPublisher";

    private volatile boolean mActivated = false;

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        // 宿主 Application 创建完成即代表主 Looper 已就绪，可安全触发官方发布器的心跳循环。
        activateCarPublisher(context);
    }

    @Nullable
    @Override
    protected IHookLyricEngine createHookEngine() {
        return new IHookLyricEngine() {
            @Override
            public void initHooks() {
                // 车机版复用宿主内置发布通道，此处无须安装任何切点 Hook。
            }

            @Override
            public void onTrackChanged(@NonNull TrackContext context) {
                // 整首歌词由宿主官方发布器投递，模块不介入；此处保留空实现以符合引擎契约。
            }

            @Nullable
            @Override
            public SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) {
                // 整首歌词由宿主官方 SuperLyricApiPublisher 直接投递至系统服务，模块不重复上报。
                return null;
            }
        };
    }

    /**
     * 主动激活车机版内置的 SuperLyric 官方 API 发布器。
     * <p>
     * 调用链：{@code ensureRegistered()} 保障发布者注册与会话监听开启 →
     * {@code startLoop(Context)} 启动主线程心跳循环（宿主内部幂等）。
     *
     * @param context 宿主 Application 上下文（其 ClassLoader 即宿主 APK ClassLoader）
     */
    private void activateCarPublisher(@NonNull Context context) {
        if (mActivated) {
            return;
        }
        mActivated = true;
        try {
            Class<?> publisherClass = Class.forName(CLS_CAR_PUBLISHER, true, context.getClassLoader());

            Method ensureRegistered = publisherClass.getMethod("ensureRegistered");
            ensureRegistered.invoke(null);
            AndroidLog.logI(TAG, "Triggered car publisher ensureRegistered()");

            Context appContext = context.getApplicationContext();
            Method startLoop = publisherClass.getMethod("startLoop", Context.class);
            startLoop.invoke(null, appContext != null ? appContext : context);
            AndroidLog.logI(TAG, "Triggered car publisher startLoop(Context)");
        } catch (ClassNotFoundException e) {
            AndroidLog.logW(TAG, "Car publisher class not found, skip activation: " + CLS_CAR_PUBLISHER);
        } catch (Throwable t) {
            AndroidLog.logE(TAG, "Failed to activate car publisher", t);
        }
    }
}
