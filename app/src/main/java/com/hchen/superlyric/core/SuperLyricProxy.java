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
import java.util.Objects;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 代理并发布 Super Lyric 系统级服务到 system_server。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "system", onSystemStarting = true)
public final class SuperLyricProxy extends AbsModule {
    /** 由 systemReady 的 after 写入，其它钩子线程读取：必须 volatile，否则可能读到 null */
    private static volatile SuperLyricService sSuperLyricService;

    /** `mProcLock` 缺失只提示一次：每个进程死亡都打一遍会刷屏 */
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

                    // 刻意**不**按 ProcessRecord#mKilled 过滤。那个字段的注释是
                    // "True once we know the process has been killed"，但全类只有两处写它：
                    // ProcessRecord.killLocked()——即 AMS **自己**下发 Process.killProcessQuiet
                    // 的那条路径——以及同包内的 setter。内核 LMK 杀进程根本不经过 AMS，
                    // AMS 只是事后由 binder 死亡得知，那种情况下 mKilled 仍是 false。
                    // 加了这道过滤，恰好漏掉 LMK 这一种。
                    //
                    // 也**不需要**在这里筛"死的是不是播放进程"：这里看不到那个信息，
                    // 真正兜住的是 SuperLyricService#onProcessDied 的两级守卫——
                    // ① 该 pid 必须本来就是个注册过的发布者（子进程确实也会注册）；
                    // ② 同包其它注册 pid 必须都已经走光。
                    // 因此"无关子进程死了"在第 ② 级就返回，一个字节的状态都不会动。
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
