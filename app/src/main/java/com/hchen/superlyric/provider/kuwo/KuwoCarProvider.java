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
 * 酷我音乐（车机版）歌词提供者
 *
 * <p>与手机版共用 {@link KuwoBaseProvider} 的全部逻辑。两者歌词管线同构，
 * 混淆后的包名不同（车机版是 {@code cn.kuwo.mod.lyric} 单数）；{@link KuwoAnchors}
 * 用 {@code lrcx=} 字符串常量定位歌词模块类并取该类所在包，不依赖包名。
 *
 */
@HookThis(targetPackage = "cn.kuwo.kwmusiccar")
public final class KuwoCarProvider extends KuwoBaseProvider {
    public KuwoCarProvider() {
        super("KuwoCarProvider");
    }
}
