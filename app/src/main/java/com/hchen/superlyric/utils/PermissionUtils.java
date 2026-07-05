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

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * 应用列表权限工具类
 * <p>
 * 用于扫描本机已安装的音乐软件，需要获取应用列表权限。
 * 在 MIUI / HyperOS 等系统上，{@link #GET_INSTALLED_APPS} 是一个运行时权限，
 * 未授予时 {@code getInstalledPackages()} 仅返回受限的应用列表；
 * 在未定义该权限的系统上，则依赖清单中已声明的 {@code QUERY_ALL_PACKAGES}（安装时授予）。
 */
public class PermissionUtils {
    /**
     * MIUI / HyperOS 的应用列表运行时权限
     */
    public static final String GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS";

    /**
     * 判断当前系统是否定义了 {@link #GET_INSTALLED_APPS} 运行时权限。
     * <p>
     * 只有定义了该权限的系统（如 MIUI / HyperOS）才需要动态申请，
     * 其余系统依赖 {@code QUERY_ALL_PACKAGES} 即可。
     *
     * @param context 上下文
     * @return 系统已定义该权限返回 true
     */
    public static boolean isAppListPermissionDefined(@NonNull Context context) {
        try {
            context.getPackageManager().getPermissionInfo(GET_INSTALLED_APPS, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 判断是否已具备扫描应用列表的权限。
     * <p>
     * 系统定义了 {@link #GET_INSTALLED_APPS} 时以其授予状态为准；
     * 未定义时视为已具备（由 {@code QUERY_ALL_PACKAGES} 保证）。
     *
     * @param context 上下文
     * @return 已具备应用列表权限返回 true
     */
    public static boolean hasAppListPermission(@NonNull Context context) {
        if (!isAppListPermissionDefined(context)) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED;
    }
}
