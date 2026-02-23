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
package com.hchen.superlyric.hook.music;

import com.hchen.collect.Collect;
import com.hchen.superlyric.helper.MeizuHelper;
import com.hchen.superlyric.hook.LyricRelease;

/**
 * MobileMusic
 */
@Collect(targetPackage = "cmccwm.mobilemusic")
public class MobileMusic extends LyricRelease {
    @Override
    protected void init() {
        MeizuHelper.depthDeviceMock();
        MeizuHelper.hookNotificationLyric();
    }
}
