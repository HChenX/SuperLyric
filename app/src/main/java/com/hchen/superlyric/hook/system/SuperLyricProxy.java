/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.hook.system;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.hchen.auto.AutoHook;
import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.service.PlayStateListener;
import com.hchen.superlyric.service.SuperLyricService;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 代理 Super Lyric 服务
 *
 * @author 焕晨HChen
 */
@AutoHook(targetPackage = "system", onSystemStarting = true)
public final class SuperLyricProxy extends AbsModule {
    private static SuperLyricService mSuperLyricService;

    @Override
    protected void onLoaded(@NonNull StageEnum stage, @NonNull Object param) {
        hookAllMethod("com.android.server.am.ActivityManagerService",
            "systemReady",
            new AbsHook() {
                @Override
                public void after() {
                    try {
                        if (mSuperLyricService == null) {
                            Context mContext = (Context) getField(getThisObject(), "mContext");
                            if (mContext != null) {
                                mSuperLyricService = new SuperLyricService(getThisObject());
                                new PlayStateListener(mContext, mSuperLyricService).register();

                                logI(TAG, "Super lyric service is all ready. enjoy it.");
                            }
                        }
                    } catch (Throwable e) {
                        logE(TAG, "Failed to load super lyric service.", e);
                    }
                }
            }
        );

        Method servicesMethod = findMethodIfExists("com.android.server.am.ActivityManagerService",
            "getCommonServicesLocked",
            boolean.class /* isolated */, boolean.class /* instant */
        );
        if (servicesMethod == null) {
            findMethodIfExists(
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
                    if (mSuperLyricService == null) {
                        return;
                    }

                    boolean isolated = (boolean) getArg(0);
                    if (isolated) {
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, IBinder> mAppBindArgs = (Map<String, IBinder>) getResult();
                    if (!mAppBindArgs.containsKey("super_lyric")) {
                        mAppBindArgs.put("super_lyric", mSuperLyricService);
                        logI(TAG, "Release super lyric service: " + mAppBindArgs.get("super_lyric"));
                    }
                }
            }
        );

        hookMethod("com.android.server.am.ActivityManagerService",
            "appDiedLocked",
            "com.android.server.am.ProcessRecord" /* app */, int.class /* pid */, "android.app.IApplicationThread" /* thread */,
            boolean.class /* fromBinderDied */, String.class /* reason */,
            new AbsHook() {
                @Override
                public void after() {
                    if (mSuperLyricService == null) {
                        return;
                    }

                    Object mProcLock = getField(getThisObject(), "mProcLock");
                    if (mProcLock == null) {
                        return;
                    }

                    synchronized (mProcLock) {
                        Object app = getArg(0);
                        ApplicationInfo info = (ApplicationInfo) getField(app, "info");
                        if (info == null) {
                            return;
                        }

                        String processName = (String) getField(app, "processName");
                        if (TextUtils.equals(info.packageName, processName)) { // 主进程
                            boolean isKilled = (boolean) Optional.ofNullable(getField(app, "mKilled")).orElse(true);
                            if (isKilled) {
                                if (SuperLyricService.mPublishers.contains(info.packageName)) {
                                    mSuperLyricService.onPackageDied(info.packageName);
                                    logI(TAG, "App: " + info.packageName + " is died.");
                                }
                            }
                        }
                    }
                }
            }
        );
    }
}
