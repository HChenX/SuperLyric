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
package com.hchen.superlyric.provider.kuwo;

import com.hchen.processor.HookThis;

/**
 * 酷我音乐（手机版）歌词提供者。
 *
 * @author 焕晨HChen
 * @see KuwoBaseProvider 取词与身份逻辑
 */
@HookThis(targetPackage = "cn.kuwo.player")
public final class KuwoProvider extends KuwoBaseProvider {
    public KuwoProvider() {
        super("KuwoProvider");
    }
}
