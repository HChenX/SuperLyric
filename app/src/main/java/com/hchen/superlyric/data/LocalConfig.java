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

import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.log.XposedLog;
import com.hchen.hooktool.utils.PrefsTool;

public class LocalConfig {
    private static final String TAG = "ModuleConfig";

    public static int getLogLevel() {
        try {
            int logLevel = PrefsTool.prefs().getInt(PrefsKey.LOG_LEVEL, 0);
            return switch (logLevel) {
                case 0 -> ModuleConfig.LOG_I;
                case 1 -> ModuleConfig.LOG_W;
                case 2 -> ModuleConfig.LOG_E;
                case 3 -> ModuleConfig.LOG_D;
                default -> throw new IllegalStateException("Unexpected value: " + logLevel);
            };
        } catch (Throwable e) {
            XposedLog.logE(TAG, e);
        }
        return 0;
    }
}
