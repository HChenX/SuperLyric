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

import com.hchen.processor.HookThis;

/**
 * 荣耀音乐（复用网易云歌词网络路径）。
 * <p>
 * 与网易云共享同一网络路径（MediaSession + eapi + 42ms 位置插值推进）：
 * 发布当前行 + 翻译/音译（跟随 App 内设置）+ 逐字；无内部状态栏 hook 可兜底，
 * 只走网络路径（请求失败保持空白，等下次切歌重新尝试）。
 * 防热更新（Tinker 禁用 + wrapper bypass）与锁屏权限处理由共享基类统一安装。
 *
 * @author 彼岸喵Higanoneko & 焕晨HChen
 */
@HookThis(targetPackage = "com.hihonor.cloudmusic")
public final class Hihonor extends NeteaseNetworkPublisher {
    @Override
    protected String lyricCacheProvider() {
        return "Hihonor";
    }
}
