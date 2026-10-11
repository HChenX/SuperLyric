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
 *
 * <h3>取词路径</h3>
 * <ul>
 *   <li><b>Hook 路径（准确优先）</b>：单锚点截获 {@code LyricsRunner4Flutter} 构造参数中的
 *       本地歌词文件路径，模块自行解析。既拿不到被宿主裁切过的逐字数据，也就从根本上
 *       规避了 {@code VerbatimLyricsParserImpl.e()} 造成的 0ms 坍缩。</li>
 *   <li><b>API 路径（兜底）</b>：复用酷狗开放接口做跨源检索。波点自身没有可用的公开歌词接口，
 *       跨源是唯一选择；采纳门槛已收紧以避免翻唱错配。</li>
 * </ul>
 *
 * <h3>标识一致性</h3>
 * 覆写 [buildIdentity] 以宿主的 {@code rid} 作为稳定标识（前缀 {@code kuwo:}）。
 * 不覆写就会让 Hook 上报的数字 rid 与元数据上报的标题哈希永不相等，
 * Hook 捕获被当成过期数据丢弃，歌词加载事件整条丢失。
 *
 * @author 焕晨HChen
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
     * <p>标识取 {@code MEDIA_ID} 而不是 Hook 的 rid：Hook 回调与元数据是**两个独立更新**
     * 的来源，宿主预加载下一首时 rid 会先翻，而元数据还停在本首。混用二者会产出
     * 「上一首的 rid + 本首的曲名」这类交叉组合，于是本首元数据到达时被 {@code matches}
     * 判为同一首歌、整首都不再重新解析，表现为**上一首的歌词一直跑**。
     * 同一次 {@code setMetadata} 里的标识与曲名则永远不会互相错位。
     *
     * <p>Hook 的 rid 仍作为兜底，且它描述的是**歌词文件所属的曲目**，用于校验投递对象。
     */
    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        String id = readString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id.isEmpty()) {
            String rid = hookStableId();
            id = rid != null ? rid : "";
        }
        if (!id.isEmpty()) {
            return new TrackIdentity(
                TAG, TrackIdentity.stableId(NAMESPACE, id), title, artist, duration);
        }
        return super.buildIdentity(metadata);
    }

    private static String readString(@NonNull MediaMetadata metadata, @NonNull String key) {
        String value = metadata.getString(key);
        return value != null ? value : "";
    }
}
