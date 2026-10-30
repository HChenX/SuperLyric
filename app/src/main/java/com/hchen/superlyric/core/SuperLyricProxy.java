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
package com.hchen.superlyric.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;

import androidx.annotation.NonNull;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;

import java.lang.reflect.Method;
import java.util.Map;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 代理并发布 Super Lyric 系统级服务到 system_server
 *
 * <p>只挂三个 AMS 锚点：{@code systemReady} 之后创建服务，{@code getCommonServicesLocked}
 * 之后暴露 binder，{@code appDiedLocked} 之后回收进程状态。锚点缺失时只记日志，
 * 不中断 AMS 自身流程。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "system", onSystemStarting = true)
public final class SuperLyricProxy extends AbsModule {
    /**
     * 由 systemReady 的 after 写入，其它钩子线程读取：必须 volatile，否则可能读到 null
     */
    private static volatile SuperLyricService sSuperLyricService;

    /**
     * 与 {@link #sSuperLyricService} 共享会话与时钟；两个 IBinder 分别承载两代接口
     */
    private static volatile SuperLyricV4Service sSuperLyricV4Service;

    /**
     * {@code mProcLock} 缺失只提示一次，避免每次进程死亡都重复记录
     */
    private static volatile boolean sProcLockMissingLogged;

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
    }

    @Override
    protected void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam param) {
        hookAllMethod("com.android.server.am.ActivityManagerService",
            "systemReady",
            new AbsHook() {
                @Override
                public void after() {
                    try {
                        if (sSuperLyricService == null) {
                            Context mContext = (Context) getField(getThisObject(), "mContext");
                            if (mContext != null) {
                                sSuperLyricService = new SuperLyricService(getThisObject());
                                sSuperLyricV4Service = new SuperLyricV4Service(sSuperLyricService);
                                SystemPlayStateListener playStateListener = new SystemPlayStateListener(mContext, sSuperLyricService);
                                sSuperLyricService.setSystemPlayStateListener(playStateListener);
                                if (playStateListener.register()) {
                                    logI(tag, "Super lyric system service initialized successfully.");
                                } else {
                                    logE(tag, "Super lyric system service initialized, "
                                        + "but the system playback listener is unavailable.");
                                }
                            }
                        }
                    } catch (Throwable e) {
                        logE(tag, "Failed to initialize super lyric system service.", e);
                    }
                }
            }
        );

        Method servicesMethod = findMethodIfExists("com.android.server.am.ActivityManagerService",
            "getCommonServicesLocked",
            boolean.class /* isolated */, boolean.class /* instant */
        );
        if (servicesMethod == null) {
            servicesMethod = findMethodIfExists(
                "com.android.server.am.ActivityManagerService",
                "getCommonServicesLocked",
                boolean.class /* isolated */
            );
        }

        if (servicesMethod == null) {
            logE(tag, "ActivityManagerService#getCommonServicesLocked() not found; binder service not exposed.");
        } else {
            hook(servicesMethod,
                new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            if (sSuperLyricService == null) return;

                            boolean isolated = (boolean) getArg(0);
                            if (isolated) return;

                            @SuppressWarnings("unchecked")
                            Map<String, IBinder> mAppBindArgs = (Map<String, IBinder>) getResult();
                            if (mAppBindArgs == null) {
                                logE(tag, "getCommonServicesLocked returned null; binder not exposed.");
                                return;
                            }
                            if (!mAppBindArgs.containsKey("super_lyric")) {
                                mAppBindArgs.put("super_lyric", sSuperLyricService);
                                logI(tag, "Exposed super_lyric binder service to common services.");
                            }
                            if (sSuperLyricV4Service != null
                                && !mAppBindArgs.containsKey("super_lyric.v4")) {
                                mAppBindArgs.put("super_lyric.v4", sSuperLyricV4Service);
                                logI(tag, "Exposed super_lyric.v4 binder service to common services.");
                            }
                        } catch (Throwable t) {
                            // 这是 AMS 的 after 钩子；异常穿回去会打断 getCommonServicesLocked 的调用方。
                            logE(tag, "Failed to expose super_lyric binder service.", t);
                        }
                    }
                }
            );
        }

        Method appDiedLockedMethod = findMethodIfExists("com.android.server.am.ActivityManagerService",
            "appDiedLocked",
            "com.android.server.am.ProcessRecord" /* app */, int.class /* pid */, "android.app.IApplicationThread" /* thread */,
            boolean.class /* fromBinderDied */, String.class /* reason */
        );
        if (appDiedLockedMethod == null) {
            appDiedLockedMethod = findMethodIfExists(
                "com.android.server.am.ActivityManagerService",
                "appDiedLocked",
                "com.android.server.am.ProcessRecord" /* app */, int.class /* pid */, "android.app.IApplicationThread" /* thread */,
                boolean.class /* fromBinderDied */, String.class /* reason */, int.class, int.class
            );
        }

        if (appDiedLockedMethod == null) {
            logE(tag, "ActivityManagerService#appDiedLocked() not found; process-death reclaim disabled.");
        } else {
            hook(appDiedLockedMethod,
                new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            if (sSuperLyricService == null) return;

                            Object app = getArg(0);
                            ApplicationInfo info = (ApplicationInfo) getField(app, "info");
                            if (info == null) return;

                            int pid = (int) getArg(1);

                            // 不按 ProcessRecord#mKilled 过滤：AMS 只在自己下发
                            // Process.killProcessQuiet 时写该字段，内核 LMK 杀进程不经过 AMS，
                            // 此时该字段仍为 false，过滤会漏掉 LMK 这一种。
                            //
                            // 也不在这里判断死亡进程是否为播放进程：这里看不到该信息。兜底的是
                            // SuperLyricService#onProcessDied 的两级守卫：pid 必须是注册过的
                            // 发布者（子进程也会注册），且同包其它注册 pid 已全部退出；无关子进程
                            // 的死亡在第 2 级返回，不触碰任何状态。
                            Object procLock = getField(getThisObject(), "mProcLock");
                            if (procLock == null) {
                                if (!sProcLockMissingLogged) {
                                    sProcLockMissingLogged = true;
                                    logW(tag, "AMS#mProcLock not found; ProcessRecord fields are read "
                                        + "unsynchronized. Process-death reclaim still works.");
                                }
                                sSuperLyricService.onProcessDied(info.packageName, pid);
                                return;
                            }

                            synchronized (procLock) {
                                sSuperLyricService.onProcessDied(info.packageName, pid);
                            }
                        } catch (Throwable t) {
                            // 进程死亡回调同样不能把异常抛回 AMS；一次失败最多漏掉一次回收。
                            logE(tag, "Failed to handle appDiedLocked for super lyric.", t);
                        }
                    }
                }
            );
        }
    }
}
