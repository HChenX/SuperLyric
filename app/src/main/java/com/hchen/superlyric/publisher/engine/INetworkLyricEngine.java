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

import com.hchen.superlyric.publisher.model.TrackContext;
import com.hchen.superlyricapi.SuperLyricData;

import java.util.concurrent.CompletableFuture;

/**
 * 在线歌词接口拉取引擎契约（作为 Hook 失败后的降级兜底方案）。
 *
 * @author 焕晨HChen
 */
public interface INetworkLyricEngine {
    /**
     * 引擎初始化（如网络嗅探、鉴权头捕获等）。
     */
    void initEngine();

    /**
     * 切歌回调。
     */
    void onTrackChanged(@NonNull TrackContext context);

    /**
     * 异步发起网络请求获取整首歌词。
     *
     * @param context 当前音轨上下文
     * @return 包含全量歌词的异步结果 Future
     */
    @NonNull
    CompletableFuture<SuperLyricData> fetchFullLyric(@NonNull TrackContext context);

    /**
     * 取消未完成的网络任务。
     */
    void cancel();
}
