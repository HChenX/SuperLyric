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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.provider.base

import com.hchen.superlyric.kernel.orchestrate.LyricSource

/**
 * 宿主侧取词来源
 *
 * 与 [LyricSource] 的唯一区别是多了 [install]：Hook 点位只需在包加载时安装一次，
 * 而取词可以被多次发起
 *
 * [install] 由 Provider 在 `onPackageReady` 中调用一次。此时 DexKit 缓存已就绪，
 * 但宿主的关键类可能尚未加载，因此定位与挂载都必须容忍失败 —— 失败只记日志、绝不抛出，
 * 以免影响宿主稳定性
 *
 * @author 焕晨HChen
 */
interface HookLyricSource : LyricSource {

    /** 安装 Hook 点位；只允许调用一次，实现必须自行捕获全部异常 */
    fun install()

    /**
     * 当前已知的曲目稳定标识（哈希 / songMID 等）；未知时返回 null
     *
     * Provider 用它构造 [com.hchen.superlyric.kernel.identity.TrackIdentity]，
     * 使 Hook 上报的标识与 `MediaSession` 上报的标识能用同一套归一化规则比较
     */
    fun currentStableId(): String? = null
}
