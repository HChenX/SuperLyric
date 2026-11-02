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
import android.os.Binder;

import androidx.annotation.NonNull;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * 代理并发布 Super Lyric 系统级服务到 system_server
 *
 * <p>只挂三个 AMS 锚点：{@code systemReady} 之后创建服务；{@code attachApplication}
 * 记录本次绑定的调用方 UID，{@code getCommonServicesLocked} 按该 UID 裁剪服务 map；
 * {@code appDiedLocked} 之后回收进程状态。锚点缺失时只记日志，不下发服务。
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
     * 当前 Binder 线程正在为哪个 UID 生成 AMS 公共服务 map
     *
     * <p>attachApplication 与 getCommonServicesLocked 在同一线程同步执行；ThreadLocal
     * 避免并发绑定之间串用 UID。不得改成全局变量。
     */
    private static final ThreadLocal<Integer> sBindUid = new ThreadLocal<>();

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
                    Context context = null;
                    try {
                        context = (Context) getField(getThisObject(), "mContext");
                        if (context != null) AccessPolicy.initialize(context);
                    } catch (Throwable e) {
                        logE(tag, "Failed to initialize access policy.", e);
                    }

                    try {
                        if (sSuperLyricService == null && context != null) {
                            sSuperLyricService = new SuperLyricService(getThisObject());
                            sSuperLyricV4Service = new SuperLyricV4Service(sSuperLyricService);
                            SystemPlayStateListener playStateListener = new SystemPlayStateListener(context, sSuperLyricService);
                            sSuperLyricService.setSystemPlayStateListener(playStateListener);
                            if (playStateListener.register()) {
                                logI(tag, "Super lyric system service initialized successfully.");
                            } else {
                                logE(tag, "Super lyric system service initialized, "
                                    + "but the system playback listener is unavailable.");
                            }
                        }
                    } catch (Throwable e) {
                        logE(tag, "Failed to initialize super lyric system service.", e);
                    }
                }
            }
        );

        hookAttachApplication();
        hookCommonServices();
        hookAppDiedLocked();
    }

    /**
     * 记录每次应用绑定的 UID，供同一线程随后执行的 getCommonServicesLocked 使用。
     */
    private void hookAttachApplication() {
        try {
            Class<?> amsClass = findAmsClass();
            if (amsClass == null) return;

            int count = 0;
            for (Method method : amsClass.getDeclaredMethods()) {
                if (!"attachApplication".equals(method.getName())) continue;

                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 0
                    || !"android.app.IApplicationThread".equals(parameters[0].getName())) {
                    continue;
                }

                final Method target = method;
                hook(target, new AbsHook() {
                    @Override
                    public void before() {
                        try {
                            int uid = Binder.getCallingUid();
                            sBindUid.set(uid);
                            AccessPolicy.invalidateUid(uid);
                        } catch (Throwable t) {
                            logE(tag, "Failed to record bind UID for "
                                + target.getName() + ".", t);
                        }
                    }

                    @Override
                    public void after() {
                        sBindUid.remove();
                    }
                });
                count++;
            }

            if (count == 0) {
                logE(tag, "ActivityManagerService#attachApplication not found; "
                    + "access control will deny all app binds.");
            } else {
                logI(tag, "Hooked " + count + " attachApplication overload(s).");
            }
        } catch (Throwable t) {
            logE(tag, "Failed to hook attachApplication; access control will fail closed.", t);
        }
    }

    /**
     * 按当前绑定 UID 返回一份裁剪后的 map 副本，绝不修改 AMS 的共享 map。
     */
    private void hookCommonServices() {
        try {
            Class<?> amsClass = findAmsClass();
            if (amsClass == null) return;

            int count = 0;
            for (Method method : amsClass.getDeclaredMethods()) {
                if (!"getCommonServicesLocked".equals(method.getName())) continue;
                if (!Map.class.isAssignableFrom(method.getReturnType())) continue;

                final Method target = method;
                hook(target, new AbsHook() {
                    @Override
                    public void after() {
                        try {
                            Object raw = getResult();
                            if (!(raw instanceof Map)) {
                                logE(tag, "getCommonServicesLocked returned no map; "
                                    + "services not exposed.");
                                return;
                            }

                            Map<Object, Object> filtered = new HashMap<>();
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

                                logD(tag, "Bind services for uid " + uid + ": " + level);
                            }

                            setResult(filtered);
                        } catch (Throwable t) {
                            logE(tag, "Failed to filter common services; services not exposed.", t);
                        }
                    }

                    private boolean isIsolatedBind(Method method) {
                        if (method.getParameterTypes().length == 0) return false;
                        try {
                            Object value = getArg(0);
                            return !(value instanceof Boolean) || (Boolean) value;
                        } catch (Throwable t) {
                            return true;
                        }
                    }
                });
                count++;
            }

            if (count == 0) {
                logE(tag, "ActivityManagerService#getCommonServicesLocked not found; "
                    + "access control will deny all app binds.");
            } else {
                logI(tag, "Hooked " + count + " getCommonServicesLocked overload(s).");
            }
        } catch (Throwable t) {
            logE(tag, "Failed to hook getCommonServicesLocked; access control will fail closed.", t);
        }
    }

    private void hookAppDiedLocked() {
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
            return;
        }

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

    private Class<?> findAmsClass() {
        Class<?> amsClass = CoreTool.findClass("com.android.server.am.ActivityManagerService");
        if (amsClass == null) {
            logE(tag, "ActivityManagerService class not found; access control disabled.");
        }
        return amsClass;
    }
}
