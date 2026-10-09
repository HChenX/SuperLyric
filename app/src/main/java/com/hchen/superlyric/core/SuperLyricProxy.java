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
import java.util.Optional;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 代理并发布 Super Lyric 系统级服务到 system_server。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "system", onSystemStarting = true)
public final class SuperLyricProxy extends AbsModule {
    private static SuperLyricService sSuperLyricService;

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
                                playStateListener.register();
                                logI(tag, "Super lyric system service initialized successfully.");
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

        Objects.requireNonNull(servicesMethod, "Failed to load super lyric service, [ActivityManagerService#getCommonServicesLocked()] not found.");
        hook(servicesMethod,
            new AbsHook() {
                @Override
                public void after() {
                    if (sSuperLyricService == null) return;

                    boolean isolated = (boolean) getArg(0);
                    if (isolated) return;

                    @SuppressWarnings("unchecked")
                    Map<String, IBinder> mAppBindArgs = (Map<String, IBinder>) getResult();
                    if (!mAppBindArgs.containsKey("super_lyric")) {
                        mAppBindArgs.put("super_lyric", sSuperLyricService);
                        logI(tag, "Exposed super_lyric binder service to common services.");
                    }
                }
            }
        );

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

        Objects.requireNonNull(appDiedLockedMethod, "Failed to load super lyric service, [ActivityManagerService#appDiedLocked()] not found.");
        hook(appDiedLockedMethod,
            new AbsHook() {
                @Override
                public void after() {
                    if (sSuperLyricService == null) return;

                    Object mProcLock = getField(getThisObject(), "mProcLock");
                    if (mProcLock == null) return;

                    synchronized (mProcLock) {
                        Object app = getArg(0);
                        ApplicationInfo info = (ApplicationInfo) getField(app, "info");
                        if (info == null) return;

                        int pid = (int) getArg(1);
                        boolean isKilled = (boolean) Optional.ofNullable(getField(app, "mKilled")).orElse(true);
                        if (isKilled) {
                            sSuperLyricService.onProcessDied(info.packageName, pid);
                        }
                    }
                }
            }
        );
    }
}
