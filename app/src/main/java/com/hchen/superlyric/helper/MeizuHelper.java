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
import static com.hchen.superlyric.hook.AbsPublisher.sendLyric;
import static com.hchen.superlyric.hook.AbsPublisher.sendStop;

import android.app.Notification;
import android.app.Service;
import android.text.TextUtils;

import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.hook.AbsHook;

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
        if (hasClass("androidx.media3.common.util.Util")) {
            hookMethod("androidx.media3.common.util.Util",
                "setForegroundServiceNotification",
                Service.class, int.class, Notification.class, int.class, String.class,
                createNotificationHook()
            );
        }
        if (hasClass("androidx.core.app.NotificationManagerCompat")) {
            hookMethod("androidx.core.app.NotificationManagerCompat",
                "notify",
                String.class, int.class, Notification.class,
                createNotificationHook()
            );
        }
        if (hasClass("android.app.NotificationManager")) {
            hookMethod("android.app.NotificationManager",
                "notify",
                String.class, int.class, Notification.class,
                createNotificationHook()
            );
        }
    }

    private static AbsHook createNotificationHook() {
        return new AbsHook() {
            @Override
            public void before() {
                Notification notification = (Notification) getArg(2);
                if (notification == null) return;

                boolean isLyric = (notification.flags & MeiZuNotification.FLAG_ALWAYS_SHOW_TICKER) != 0 ||
                    (notification.flags & MeiZuNotification.FLAG_ONLY_UPDATE_TICKER) != 0;
                if (isLyric) {
                    if (notification.tickerText != null) {
                        sendLyric(notification.tickerText.toString());
                    } else {
                        sendStop();
                    }
                }
            }
        };
    }
}
