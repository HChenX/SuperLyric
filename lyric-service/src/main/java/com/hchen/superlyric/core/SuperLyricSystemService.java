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
import android.util.ArrayMap;

import androidx.annotation.NonNull;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 在 system_server 中创建运行时与两代 Binder 服务，并安装 AMS 钩子。
 *
 * 锚点缺失时只记日志，不下发服务。
 */
public final class SuperLyricSystemService extends CoreTool {
    private static final String TAG = "SuperLyricSystemService";

    private static volatile LyricRuntime sRuntime;
    private static volatile SuperLyricService sSuperLyricService;
    private static volatile SuperLyricV4Service sSuperLyricV4Service;

    /** attachApplicationLocked 与 getCommonServicesLocked 在同一线程同步执行。 */
    private static final ThreadLocal<Integer> sBindUid = new ThreadLocal<>();

    private static volatile boolean sProcLockMissingLogged;
    private static volatile boolean sUidMissingLogged;

    public void install(@NonNull XposedModuleInterface.SystemServerStartingParam param) {
        hookAllMethod("com.android.server.am.ActivityManagerService",
            "systemReady",
            new AbsHook() {
                @Override
                public void after() {
                    Context context = null;
                    try {
                        context = (Context) getField(getThisObject(), "mContext");
                        if (context != null) AccessPolicy.initialize(context);
                    } catch (Throwable e) {
                        logE(TAG, "Failed to initialize access policy.", e);
                    }

                    try {
                        if (sRuntime == null && context != null) {
                            sRuntime = new LyricRuntime(getThisObject());
                            sSuperLyricService = new SuperLyricService(sRuntime);
                            sSuperLyricV4Service = new SuperLyricV4Service(sRuntime);
                            SystemPlayStateListener playStateListener = new SystemPlayStateListener(context, sRuntime);
                            sRuntime.setSystemPlayStateListener(playStateListener);
                            if (playStateListener.register()) {
                                logI(TAG, "Super lyric system service initialized successfully.");
                            } else {
                                logE(TAG, "Super lyric system service initialized, "
                                    + "but the system playback listener is unavailable.");
                            }
                        }
                    } catch (Throwable e) {
                        logE(TAG, "Failed to initialize super lyric system service.", e);
                    }
                }
            }
        );

        attachApplicationLocked();
        hookCommonServices();
        hookAppDiedLocked();
    }

    private void attachApplicationLocked() {
        try {
            hookAllMethod("com.android.server.am.ActivityManagerService",
                "attachApplicationLocked",
                new AbsHook() {
                    @Override
                    public void before() {
                        // 这是 AMS 内部方法，Binder.getCallingUid() 永远是 system_server(1000)；
                        // 目标进程 UID 必须取参数表里的 uid（thread, pid, uid, ...）。
                        int uid = ((Number) getArg(2)).intValue();
                        sBindUid.set(uid);
                        AccessPolicy.invalidateUid(uid);
                    }

                    @Override
                    public void after() {
                        sBindUid.remove();
                    }
                }
            );
        } catch (Throwable t) {
            logE(TAG, "Failed to hook attachApplicationLocked; access control will fail closed.", t);
        }
    }

    private void hookCommonServices() {
        try {
            Method target = null;
            for (Method method : findAllMethod("com.android.server.am.ActivityManagerService",
                "getCommonServicesLocked")) {
                if (Map.class.isAssignableFrom(method.getReturnType())) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                logE(TAG, "ActivityManagerService#getCommonServicesLocked not found; "
                    + "access control will deny all app binds.");
                return;
            }

            hook(target, new AbsHook() {
                @Override
                public void after() {
                    Object raw = getResult();
                    if (!(raw instanceof Map)) {
                        logE(TAG, "getCommonServicesLocked returned no map; "
                            + "services not exposed.");
                        return;
                    }

                    Method target = (Method) getExecutable();
                    Map<Object, Object> filtered = newResultMap(target);
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                        filtered.put(entry.getKey(), entry.getValue());
                    }
                    filtered.remove(AccessPolicy.LEGACY_SERVICE);
                    filtered.remove(AccessPolicy.V4_SERVICE);

                    Integer uid = sBindUid.get();
                    if (uid != null && !isIsolatedBind(target)) {
                        AccessPolicy.Level level = AccessPolicy.levelForUid(uid);
                        SuperLyricService legacy = sSuperLyricService;
                        SuperLyricV4Service v4 = sSuperLyricV4Service;

                        if (level.allowsLegacy() && legacy != null) {
                            filtered.put(AccessPolicy.LEGACY_SERVICE, legacy);
                        }
                        if (level.allowsV4() && v4 != null) {
                            filtered.put(AccessPolicy.V4_SERVICE, v4);
                        }

                        logD(TAG, "Bind services for uid " + uid + ": " + level);
                    } else if (uid == null && !sUidMissingLogged) {
                        sUidMissingLogged = true;
                        logW(TAG, "attachApplicationLocked uid was missing when getCommonServicesLocked ran; "
                            + "services are not exposed. Further occurrences are not logged.");
                    }

                    setResult(filtered);
                }

                private boolean isIsolatedBind(Method method) {
                    if (method.getParameterTypes().length == 0) return false;
                    Object value = getArg(0);
                    return !(value instanceof Boolean) || (Boolean) value;
                }
            });
            logI(TAG, "Hooked getCommonServicesLocked.");
        } catch (Throwable t) {
            logE(TAG, "Failed to hook getCommonServicesLocked; access control will fail closed.", t);
        }
    }

    private void hookAppDiedLocked() {
        // Android 11-13 为四参签名；14 起多了 reason，部分版本再加两个 int。
        Method appDiedLockedMethod = findMethodIfExists("com.android.server.am.ActivityManagerService",
            "appDiedLocked",
            "com.android.server.am.ProcessRecord" /* app */, int.class /* pid */, "android.app.IApplicationThread" /* thread */,
            boolean.class /* fromBinderDied */
        );
        if (appDiedLockedMethod == null) {
            appDiedLockedMethod = findMethodIfExists("com.android.server.am.ActivityManagerService",
                "appDiedLocked",
                "com.android.server.am.ProcessRecord" /* app */, int.class /* pid */, "android.app.IApplicationThread" /* thread */,
                boolean.class /* fromBinderDied */, String.class /* reason */
            );
        }
        if (appDiedLockedMethod == null) {
            appDiedLockedMethod = findMethodIfExists(
                "com.android.server.am.ActivityManagerService",
                "appDiedLocked",
                "com.android.server.am.ProcessRecord" /* app */, int.class /* pid */, "android.app.IApplicationThread" /* thread */,
                boolean.class /* fromBinderDied */, String.class /* reason */, int.class, int.class
            );
        }

        if (appDiedLockedMethod == null) {
            logE(TAG, "ActivityManagerService#appDiedLocked() not found; process-death reclaim disabled.");
            return;
        }

        hook(appDiedLockedMethod,
            new AbsHook() {
                @Override
                public void after() {
                    if (sRuntime == null) return;

                    Object app = getArg(0);
                    ApplicationInfo info = (ApplicationInfo) getField(app, "info");
                    if (info == null) return;

                    int pid = (int) getArg(1);

                    // 不按 ProcessRecord#mKilled 过滤：LMK 不经过 AMS 写该字段。
                    // 是否属于当前播放进程由 LyricRuntime#onProcessDied 的两级守卫判断。
                    Object procLock = getField(getThisObject(), "mProcLock");
                    if (procLock == null) {
                        if (!sProcLockMissingLogged) {
                            sProcLockMissingLogged = true;
                            logW(TAG, "AMS#mProcLock not found; ProcessRecord fields are read "
                                + "unsynchronized. Process-death reclaim still works.");
                        }
                        sRuntime.onProcessDied(info.packageName, pid);
                        return;
                    }

                    synchronized (procLock) {
                        sRuntime.onProcessDied(info.packageName, pid);
                    }
                }
            }
        );
    }

    /** HookTool 校验返回值必须是声明类型的实例；AMS 可能声明为 ArrayMap。 */
    private static Map<Object, Object> newResultMap(Method method) {
        Class<?> returnType = method.getReturnType();
        Map<Object, Object> result;
        if (returnType.isAssignableFrom(ArrayMap.class)) {
            result = new ArrayMap<>();
        } else if (returnType.isAssignableFrom(HashMap.class)) {
            result = new HashMap<>();
        } else {
            throw new IllegalStateException("Unsupported services map type: "
                + returnType.getName());
        }
        return result;
    }
}