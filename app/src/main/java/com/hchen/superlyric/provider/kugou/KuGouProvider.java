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
package com.hchen.superlyric.provider.kugou;

import android.media.MediaMetadata;

import androidx.annotation.NonNull;

import com.hchen.processor.HookThis;
import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

/**
 * 酷狗音乐歌词提供者。
 *
 * <h3>取词路径</h3>
 * <ul>
 *   <li><b>Hook 路径（准确优先）</b>：单个锚点 {@code @twin:GlobalVariate}。
 *       宿主写入 {@code LyricData}（key=41）即"歌词已就绪"，同时从 key=207 读出音频 Hash；
 *       随后直接读取宿主自己下载的本地 {@code .krc} 文件。
 *       报文由模块自行调用 {@code KrcCipher} + 库的 KRC 解析器解开，不经宿主任何加工。</li>
 *   <li><b>API 路径（兜底）</b>：酷狗开放 KRC 接口，支持 Hash 直查与歌名检索。</li>
 * </ul>
 *
 * <h3>标识一致性</h3>
 * 覆写 [buildIdentity] 以音频 Hash 作为稳定标识，使 Hook 上报与 `MediaSession`
 * 上报的标识落在同一个命名空间里（{@code kugou:<hash>}），从而相互可比较。
 * 否则 Hook 捕获会因"标识表示法不同"被当成过期数据丢弃。
 *
 * <p>本类必须用 Java 编写：{@code @HookThis} 由 Java 注解处理器消费，
 * 而 Kotlin 类不会经过该处理器，写 Kotlin 会导致整个 Provider 不被注册。
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.kugou.android")
public class KuGouProvider extends MusicProvider {
    private static final String TAG = "KuGouProvider";
    private static final String NAMESPACE = "kugou";

    @Override
    protected String getProviderTag() {
        return TAG;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        return new KuGouHookSource();
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new KuGouApiSource();
    }

    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String hash = hookStableId();
        if (hash != null && !hash.isEmpty()) {
            return new TrackIdentity(
                TAG,
                TrackIdentity.stableId(NAMESPACE, hash),
                readString(metadata, MediaMetadata.METADATA_KEY_TITLE),
                readString(metadata, MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            );
        }
        return super.buildIdentity(metadata);
    }

    private static String readString(@NonNull MediaMetadata metadata, @NonNull String key) {
        String value = metadata.getString(key);
        return value != null ? value : "";
    }
}
