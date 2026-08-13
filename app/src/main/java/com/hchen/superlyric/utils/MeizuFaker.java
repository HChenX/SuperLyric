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
package com.hchen.superlyric.utils;

import static com.hchen.hooktool.core.CoreTool.hookMethod;
import static com.hchen.hooktool.core.CoreTool.hookMethodIfExists;
import static com.hchen.hooktool.core.CoreTool.setStaticField;

import android.app.Notification;
import android.app.Service;
import android.text.TextUtils;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.superlyric.hook.AbsPublisher;

/**
 * 魅族设备模拟与状态栏 / 通知栏歌词 Hook。
 * <p>
 * 通过伪造 Build 信息与替换 Notification 类使宿主开启魅族状态栏歌词，
 * 并 hook 通知栏歌词（ticker）作为兜底发布通道。
 *
 * @author 焕晨HChen
 */
public final class MeizuFaker {
    private static final String TAG = "MeizuFaker";
    private static volatile boolean sNotificationLyricEnabled = true;
    private static volatile boolean sNotificationHookInstalled = false;
    private static final Object NOTIFICATION_HOOK_LOCK = new Object();

    public static void setNotificationLyricEnabled(boolean enabled) {
        sNotificationLyricEnabled = enabled;
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
                    if (TextUtils.equals("android.app.Notification", (String) getArg(0))) {
                        setResult(MeiZuNotification.class);
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
                    if (TextUtils.equals("android.app.Notification", (String) getArg(0))) {
                        setResult(MeiZuNotification.class);
                    }
                }
            }
        );
    }

    public static void hookNotificationLyric() {
        if (sNotificationHookInstalled) {
            return;
        }

        synchronized (NOTIFICATION_HOOK_LOCK) {
            if (sNotificationHookInstalled) {
                return;
            }

            hookMethodIfExists("androidx.media3.common.util.Util",
                "setForegroundServiceNotification",
                Service.class, int.class, Notification.class, int.class, String.class,
                createNotificationHook()
            );
            hookMethodIfExists("androidx.core.app.NotificationManagerCompat",
                "notify",
                String.class, int.class, Notification.class,
                createNotificationHook()
            );
            hookMethodIfExists("android.app.NotificationManager",
                "notify",
                String.class, int.class, Notification.class,
                createNotificationHook()
            );

            sNotificationHookInstalled = true;
        }
    }

    private static AbsHook createNotificationHook() {
        return new AbsHook() {
            @Override
            public void before() {
                if (!sNotificationLyricEnabled) {
                    return;
                }

                Notification notification = (Notification) getArg(2);
                if (notification != null) {
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
            }
        };
    }

    private static final class MeiZuNotification extends Notification {
        public static final int FLAG_ALWAYS_SHOW_TICKER = 0x01000000;
        public static final int FLAG_ONLY_UPDATE_TICKER = 0x02000000;
    }
}
