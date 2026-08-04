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
package com.hchen.superlyric.helper;

import static com.hchen.hooktool.core.CoreTool.hasClass;
import static com.hchen.hooktool.core.CoreTool.hookMethod;
import static com.hchen.hooktool.core.CoreTool.setStaticField;

import android.app.Notification;
import android.app.Service;
import android.text.TextUtils;

import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.superlyric.hook.AbsPublisher;

/**
 * 模拟魅族设备
 * <p>
 * 用于开启魅族状态栏功能
 *
 * @author 焕晨HChen
 */
public final class MeizuHelper {
    private static final String TAG = "MeizuHelper";

    /**
     * 通知栏歌词发布开关：默认开启（保持其他提供者现状行为不变）；
     * Netease 双轨互斥时由状态机按歌曲粒度关闭/开启，避免与网络路径双发。
     */
    private static volatile boolean mNotificationLyricEnabled = true;

    /** 通知栏歌词 hook 安装守卫：同一进程内只安装一次，重复安装会双发。 */
    private static volatile boolean mNotificationHookInstalled = false;
    private static final Object NOTIFICATION_HOOK_LOCK = new Object();

    public static void setNotificationLyricEnabled(boolean enabled) {
        mNotificationLyricEnabled = enabled;
    }

    /**
     * 浅层模拟魅族设备
     */
    public static void shallowLayerDeviceMock() {
        setStaticField("android.os.Build", "DISPLAY", "Flyme");

        hookMethod(Class.class, "forName", String.class,
            new AbsHook() {
                @Override
                public void before() {
                    try {
                        if (TextUtils.equals("android.app.Notification", (String) getArg(0))) {
                            setResult(MeiZuNotification.class);
                            return;
                        }
                        setResult(ModuleData.getClassLoader().loadClass((String) getArg(0)));
                    } catch (Throwable ignore) {
                    }
                }
            }
        );
    }

    /**
     * 深度模拟魅族
     */
    public static void depthDeviceMock() {
        setStaticField("android.os.Build", "BRAND", "meizu");
        setStaticField("android.os.Build", "MANUFACTURER", "Meizu");
        setStaticField("android.os.Build", "DEVICE", "m1892");
        setStaticField("android.os.Build", "DISPLAY", "Flyme");
        setStaticField("android.os.Build", "PRODUCT", "meizu_16thPlus_CN");
        setStaticField("android.os.Build", "MODEL", "meizu 16th Plus");

        hookMethod(Class.class, "forName", String.class,
            new AbsHook() {
                @Override
                public void before() {
                    try {
                        if (TextUtils.equals("android.app.Notification", (String) getArg(0))) {
                            setResult(MeiZuNotification.class);
                            return;
                        }
                        setResult(ModuleData.getClassLoader().loadClass((String) getArg(0)));
                    } catch (Throwable ignore) {
                    }
                }
            }
        );
    }

    public static void hookNotificationLyric() {
        if (mNotificationHookInstalled) return;
        synchronized (NOTIFICATION_HOOK_LOCK) {
            if (mNotificationHookInstalled) return;

            if (hasClass("androidx.media3.common.util.Util")) {
                try {
                    hookMethod("androidx.media3.common.util.Util",
                        "setForegroundServiceNotification",
                        Service.class, int.class, Notification.class, int.class, String.class,
                        createNotificationHook()
                    );
                } catch (Throwable t) {
                    XposedLog.logW(TAG, "Hook Util.setForegroundServiceNotification failed", t);
                }
            }
            if (hasClass("androidx.core.app.NotificationManagerCompat")) {
                try {
                    hookMethod("androidx.core.app.NotificationManagerCompat",
                        "notify",
                        String.class, int.class, Notification.class,
                        createNotificationHook()
                    );
                } catch (Throwable t) {
                    XposedLog.logW(TAG, "Hook NotificationManagerCompat.notify failed", t);
                }
            }
            if (hasClass("android.app.NotificationManager")) {
                try {
                    hookMethod("android.app.NotificationManager",
                        "notify",
                        String.class, int.class, Notification.class,
                        createNotificationHook()
                    );
                } catch (Throwable t) {
                    XposedLog.logW(TAG, "Hook NotificationManager.notify failed", t);
                }
            }
            mNotificationHookInstalled = true;
        }
    }

    private static AbsHook createNotificationHook() {
        return new AbsHook() {
            @Override
            public void before() {
                if (!mNotificationLyricEnabled) return;

                Notification notification = (Notification) getArg(2);
                if (notification == null) return;

                boolean isLyric = (notification.flags & MeiZuNotification.FLAG_ALWAYS_SHOW_TICKER) != 0 ||
                    (notification.flags & MeiZuNotification.FLAG_ONLY_UPDATE_TICKER) != 0;
                if (isLyric) {
                    if (notification.tickerText != null) {
                        AbsPublisher.sendLyric(notification.tickerText.toString());
                    } else {
                        AbsPublisher.sendStop();
                    }
                }
            }
        };
    }
}
