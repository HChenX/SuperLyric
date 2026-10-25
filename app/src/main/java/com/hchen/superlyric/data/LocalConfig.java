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
 * 本地配置读取：日志级别等设置项的取用。
 *
 * @author 焕晨HChen
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
     * 把设置项里的档位映射为 {@link ModuleConfig} 的日志等级
     *
     * <p>返回值<b>必须</b>落在 {@code LOG_E..LOG_D} 内：判定日志是否输出的是
     * {@code getLogLevel() >= level}，而 {@code LOG_E == 1}。返回 {@code 0} 会让一切日志
     * 都不输出——表现为"日志等级设成最低"与"诊断通道整个失效"无法分辨，
     * 正是本项目最怕的静默类故障
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
