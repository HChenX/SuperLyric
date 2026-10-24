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
package com.hchen.superlyric.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * 桌面图标开关。
 *
 * <p>桌面入口是 {@code LauncherAlias}，LSPosed 入口仍是 {@code MainActivity} 的
 * {@code MODULE_SETTINGS} filter。隐藏时只禁用 alias，不禁用 Activity，因此
 * LSPosed 仍能显式启动设置页。
 */
public final class LauncherIconController {

    private static final String TAG = "LauncherIconController";
    private static final String ALIAS_CLASS = "com.hchen.superlyric.ui.LauncherAlias";

    private LauncherIconController() {
    }

    /**
     * 桌面图标当前是否被隐藏。
     */
    public static boolean isHidden(@NonNull Context context) {
        ComponentName alias = new ComponentName(context, ALIAS_CLASS);
        return context.getPackageManager().getComponentEnabledSetting(alias)
            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    /**
     * 切换桌面图标。
     *
     * <p>{@link PackageManager#DONT_KILL_APP} 让组件状态立即生效但不杀当前进程；
     * 桌面通过 package-changed 广播刷新图标，不需要重启应用。
     *
     * @return 状态是否已写成期望值
     */
    public static boolean setHidden(@NonNull Context context, boolean hidden) {
        ComponentName alias = new ComponentName(context, ALIAS_CLASS);
        int state = hidden
            ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        try {
            context.getPackageManager().setComponentEnabledSetting(
                alias,
                state,
                PackageManager.DONT_KILL_APP
            );
            boolean actual = isHidden(context);
            if (actual != hidden) {
                Log.w(TAG, "Launcher alias state did not stick: expected hidden=" + hidden);
            }
            return actual == hidden;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to change launcher alias state", t);
            return false;
        }
    }
}
