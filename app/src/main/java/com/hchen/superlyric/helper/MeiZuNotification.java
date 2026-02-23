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

import android.app.Notification;

/**
 * 魅族状态栏歌词基本参数
 *
 * @author 焕晨HChen
 */
public final class MeiZuNotification extends Notification {
    public static final int FLAG_ALWAYS_SHOW_TICKER = 0x01000000;
    public static final int FLAG_ONLY_UPDATE_TICKER = 0x02000000;
}
