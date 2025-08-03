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

 * Copyright (C) 2023-2025 HChenX
 */
package com.hchen.superlyric.helper;

import static com.hchen.hooktool.core.CoreTool.existsClass;
import static com.hchen.hooktool.core.CoreTool.hookMethod;
import static com.hchen.hooktool.core.CoreTool.setStaticField;
import static com.hchen.superlyric.hook.LyricRelease.sendLyric;
import static com.hchen.superlyric.hook.LyricRelease.sendStop;
import static com.hchen.superlyricapi.SuperLyricTool.drawableToBase64;

import android.app.AndroidAppHelper;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.graphics.drawable.Icon;

import com.hchen.hooktool.HCData;
import com.hchen.hooktool.hook.IHook;

/**
 * 模拟魅族设备
 * <p>
 * 用于开启魅族状态栏功能
 *
 * @author 焕晨HChen
 */
public class MeizuHelper {
    private static final String TAG = "MeizuHelper";

    /**
     * 浅层模拟魅族设备
     */
    public static void shallowLayerDeviceMock() {
        setStaticField("android.os.Build", "DISPLAY", "Flyme");

        hookMethod(Class.class, "forName", String.class,
            new IHook() {
                @Override
                public void before() {
                    try {
                        if ("android.app.Notification".equals(getArg(0))) {
                            setResult(MeiZuNotification.class);
                            return;
                        }
                        Class<?> clazz = (Class<?>) callThisStaticMethod(
                            "forName",
                            getArg(0),
                            true, HCData.getClassLoader()
                        );
                        setResult(clazz);
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
            new IHook() {
                @Override
                public void before() {
                    try {
                        if ("android.app.Notification".equals(getArg(0))) {
                            setResult(MeiZuNotification.class);
                            return;
                        }
                        Class<?> clazz = (Class<?>) callThisStaticMethod(
                            "forName",
                            getArg(0),
                            true, HCData.getClassLoader()
                        );
                        setResult(clazz);
                    } catch (Throwable ignore) {
                    }
                }
            }
        );
    }

    public static void hookNotificationLyric() {
        if (existsClass("androidx.media3.common.util.Util")) {
            hookMethod("androidx.media3.common.util.Util",
                "setForegroundServiceNotification",
                Service.class, int.class, Notification.class, int.class, String.class,
                createNotificationHook()
            );
        }
        if (existsClass("androidx.core.app.NotificationManagerCompat")) {
            hookMethod("androidx.core.app.NotificationManagerCompat",
                "notify",
                String.class, int.class, Notification.class,
                createNotificationHook()
            );
        }
        if (existsClass("android.app.NotificationManager")) {
            hookMethod("android.app.NotificationManager",
                "notify",
                String.class, int.class, Notification.class,
                createNotificationHook()
            );
        }
    }

    private static IHook createNotificationHook() {
        return new IHook() {
            @Override
            public void before() {
                Notification notification = (Notification) getArg(2);
                if (notification == null) return;

                boolean isLyric = ((notification.flags & MeiZuNotification.FLAG_ALWAYS_SHOW_TICKER) != 0 ||
                    (notification.flags & MeiZuNotification.FLAG_ONLY_UPDATE_TICKER) != 0);
                if (isLyric) {
                    if (notification.tickerText != null) {
                        Context context = AndroidAppHelper.currentApplication();

                        String base64Icon = null;
                        int iconId = notification.extras.getInt("ticker_icon", 0);
                        Icon smallIcon = notification.getSmallIcon();
                        int smallIconId = notification.icon;
                        if (iconId != 0) base64Icon = drawableToBase64(context.getDrawable(iconId));
                        else if (smallIconId != 0)
                            base64Icon = drawableToBase64(Icon.createWithResource(context, smallIconId).loadDrawable(context));
                        else if (smallIcon != null)
                            base64Icon = drawableToBase64(smallIcon.loadDrawable(context));

                        if (base64Icon != null)
                            sendLyric(notification.tickerText.toString(), base64Icon);
                        else sendLyric(notification.tickerText.toString());
                    } else {
                        sendStop();
                    }
                }
            }
        };
    }
}
