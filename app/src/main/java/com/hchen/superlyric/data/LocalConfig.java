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
package com.hchen.superlyric.data;

import android.content.Context;

import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.log.AndroidLog;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.hooktool.utils.PrefsTool;

/**
 * 本地配置读取。
 *
 */
public final class LocalConfig {
    private static final String TAG = "ModuleConfig";

    public static int getLogLevelForXposed() {
        try {
            return getLogLevel(PrefsTool.prefs().getInt(PrefsKey.LOG_LEVEL, 0));
        } catch (Throwable e) {
            XposedLog.logE(TAG, e);
        }
        return ModuleConfig.LOG_I;
    }

    public static int getLogLevelForModule(Context context) {
        try {
            return getLogLevel(PrefsTool.prefs(context).getInt(PrefsKey.LOG_LEVEL, 0));
        } catch (Throwable e) {
            AndroidLog.logE(TAG, e);
        }
        return ModuleConfig.LOG_I;
    }

    /**
     * 将设置项映射为 {@link ModuleConfig} 的日志等级。
     *
     * <p>0 映射为 {@code LOG_I}，避免返回 0 后所有日志都被过滤。
     */
    private static int getLogLevel(int level) {
        return switch (level) {
            case 0 -> ModuleConfig.LOG_I;
            case 1 -> ModuleConfig.LOG_W;
            case 2 -> ModuleConfig.LOG_E;
            case 3 -> ModuleConfig.LOG_D;
            default -> ModuleConfig.LOG_I;
        };
    }
}
