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
package com.hchen.superlyric.data.apps;

import com.hchen.hooktool.data.AppData;

/**
 * 支持网络歌词获取的应用数据。
 *
 * <p>仅作为类型标记使用：出现在 {@code networkApps} 列表即代表该应用处于网络获取模式，
 * 与 {@code hookApps}（普通 {@link AppData}）按所属列表区分，无需额外状态字段。
 *
 * @author 焕晨HChen
 */
public class NetworkAppData extends AppData {
}
