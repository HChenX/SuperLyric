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
import static com.hchen.hooktool.core.CoreTool.returnResult;

/**
 * 蓝牙状态模拟：向宿主伪造蓝牙已开启，以触发蓝牙场景的歌词发布。
 *
 * @author 焕晨HChen
 */
public final class BluetoothFaker {
    /**
     * 模拟蓝牙为开启状态。
     */
    public static void fakeBluetoothA2dpEnabled() {
        hookMethod("android.media.AudioManager",
            "isBluetoothA2dpOn",
            returnResult(true)
        );
        hookMethod("android.bluetooth.BluetoothAdapter",
            "isEnabled",
            returnResult(true)
        );
    }
}
