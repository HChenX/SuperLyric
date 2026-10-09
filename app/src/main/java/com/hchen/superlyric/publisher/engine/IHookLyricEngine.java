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
package com.hchen.superlyric.publisher.engine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyricapi.SuperLyricData;

/**
 * 拦截宿主音乐软件内部已反序列化/解析完成的整首歌词数据引擎契约。
 *
 * @author 焕晨HChen
 */
public interface IHookLyricEngine {
    /**
     * 引擎初始化并安装相关切点 Hook。
     */
    void initHooks();

    /**
     * 切歌或需要重置状态时的回调。
     */
    void onTrackChanged(@NonNull TrackContext context);

    /**
     * 尝试从 Hook 拦截上下文中提取当前曲目的整首歌词数据。
     *
     * @param context 当前音轨上下文
     * @return 解析完成的 {@link SuperLyricData}（必须包含 setAllLyrics），若尚在解析中或未捕获返回 null
     * @throws Exception 若宿主明确抛出无歌词、格式错误、加解密崩溃等确定性失败异常
     */
    @Nullable
    SuperLyricData tryExtractFullLyric(@NonNull TrackContext context) throws Exception;
}
