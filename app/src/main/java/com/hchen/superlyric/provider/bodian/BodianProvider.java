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
package com.hchen.superlyric.provider.bodian;

import android.media.MediaMetadata;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.provider.kugou.KuGouApiSource;

/**
 * 波点音乐歌词提供者。
 * <p>
 * Hook 路径截获 LyricsRunner4Flutter 构造参数中的本地歌词文件路径；API 路径复用酷狗接口
 * 做跨源检索。身份以 MEDIA_ID 为准，rid 只在其为空时兜底。
 */
@HookThis(targetPackage = "cn.wenyu.bodian")
public class BodianProvider extends MusicProvider {
    private static final String TAG = "BodianProvider";
    private static final String NAMESPACE = "kuwo";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        return new BodianHookSource();
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new KuGouApiSource();
    }

    /**
     * 从元数据构建音轨身份
     *
     * <p>标识取 {@code MEDIA_ID} 而不是 Hook 的 rid：两者是独立更新源，宿主预加载下一首时
     * rid 会先翻，而元数据仍属于当前曲目。混用会产出「上一首的 rid + 本首的曲名」这类
     * 交叉组合，使本首元数据到达时被 {@code matches} 判为同一首歌、整首不再重新解析，
     * 最终持续显示上一首歌词。同一次 {@code setMetadata} 中的标识与曲名不会错位。
     *
     * <p>Hook 的 rid 仍作为兜底，且描述歌词文件所属的曲目，用于校验投递对象。
     */
    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String id = metadataString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id.isEmpty()) {
            String rid = hookStableId();
            id = rid != null ? rid : "";
        }
        return trackIdentity(metadata, TrackIdentity.stableId(NAMESPACE, id));
    }
}
