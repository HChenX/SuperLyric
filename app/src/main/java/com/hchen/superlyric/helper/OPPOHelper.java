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

import static com.hchen.hooktool.core.CoreTool.setStaticField;

/**
 * 模拟 OPPO 设备
 *
 * @author 焕晨HChen
 */
public final class OPPOHelper {
    public static void mockDevice() {
        setStaticField("android.os.Build", "BRAND", "oppo");
        setStaticField("android.os.Build", "MANUFACTURER", "Oppo");
        setStaticField("android.os.Build", "DISPLAY", "Color");
    }
}
