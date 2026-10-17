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

import android.media.MediaMetadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyric.kernel.identity.TrackIdentity;
import com.hchen.superlyric.kernel.orchestrate.LyricSource;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;
import com.hchen.superlyric.provider.kugou.KuGouApiSource;

/**
 * 酷我音乐歌词提供者的公共基类。
 *
 * <p>手机版与车机版共用这一套取词与身份逻辑，两者只差包名（见
 * {@link KuwoProvider} 与 {@link KuwoCarProvider}）。歌词管线虽然同构，
 * 但混淆后的类名不同，锚点已按候选包名逐个尝试，无需在此区分。
 *
 * <h3>取词路径</h3>
 * <ul>
 *   <li><b>Hook 路径（准确优先）</b>：锚在宿主把明文 LRCX 交给自己的歌词引擎那一个瞬间，
 *       拿到的是<b>解密后、未被宿主行/词模型裁切</b>的全文，因此逐字时戳不会出现
 *       0ms 坍缩。酷我的逐字信息就藏在这份 LRCX 的 {@code <v1,v2>} 标签里。</li>
 *   <li><b>API 路径（兜底）</b>：复用酷狗开放接口做跨源检索。酷我没有可用的公开歌词接口，
 *       跨源是唯一选择；采纳门槛已收紧以避免翻唱错配。</li>
 * </ul>
 *
 * <h3>为什么没有进度通道</h3>
 * 宿主的进度属于播放状态，按 L3 由框架侧唯一时钟负责。本 Provider 只发布<b>全量</b>歌词，
 * 逐字高亮与当前行切换由下游按播放位置自行推导。
 *
 * <h3>标识一致性</h3>
 * 覆写 {@link #buildIdentity} 以宿主的 {@code rid} 作为稳定标识（前缀 {@code kuwo:}）。
 * 不覆写就会让 Hook 上报的 rid 与元数据上报的标题哈希永不相等，
 * Hook 捕获被当成过期数据丢弃，歌词加载事件整条丢失。
 *
 * @author 焕晨HChen
 */
abstract class KuwoBaseProvider extends MusicProvider {

    /** 稳定标识的命名空间；两个变体共用，rid 在平台内唯一 */
    private static final String NAMESPACE = "kuwo";

    private final String providerTag;

    protected KuwoBaseProvider(@NonNull String providerTag) {
        this.providerTag = providerTag;
    }

    /**
     * Hook 取词来源；同时用于读取宿主的真实曲目信息。
     *
     * <p>{@code volatile} 是必需的：在 {@code onPackageReady} 的线程上写，在宿主
     * {@code MediaSession} 回调线程上经 {@link #buildIdentity} 读。可见性失败时读到 null，
     * 会静默退化为使用<b>宿主元数据</b>——而酷我会把当前歌词行写进 TITLE，元数据不可信。
     */
    @Nullable
    private volatile KuwoHookSource kuwoHookSource;

    @Override
    protected String getProviderTag() {
        return providerTag;
    }

    @NonNull
    @Override
    protected HookLyricSource createHookSource() {
        kuwoHookSource = new KuwoHookSource();
        return kuwoHookSource;
    }

    @NonNull
    @Override
    protected LyricSource createApiSource() {
        return new KuGouApiSource();
    }

    /**
     * 从元数据构建音轨身份
     *
     * <p>优先用 Hook 侧捕获的 {@code rid}：酷我把当前歌词行写进
     * {@code METADATA_KEY_TITLE}（实测每次回调都变），标题既不能用于判等、
     * 也不能作为降级标识。宿主的 {@code Music.rid} 与歌词回调同一时序到达，是唯一可靠标识。
     *
     * <p>曲名与歌手同样优先取宿主 {@code Music} 对象上的干净字段，
     * 只在拿不到时才退回元数据。
     */
    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        String captured = hookStableId();
        if (captured != null && !captured.isEmpty()) {
            KuwoHookSource hook = kuwoHookSource;
            if (hook != null) {
                String hostTitle = hook.trackTitle(captured);
                String hostArtist = hook.trackArtist(captured);
                if (!hostTitle.isEmpty()) title = hostTitle;
                if (!hostArtist.isEmpty()) artist = hostArtist;
            }
            return new TrackIdentity(
                providerTag, TrackIdentity.stableId(NAMESPACE, captured), title, artist, duration);
        }

        String mediaId = readString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID).trim();
        if (!mediaId.isEmpty()) {
            return new TrackIdentity(
                providerTag, TrackIdentity.stableId(NAMESPACE, mediaId), title, artist, duration);
        }

        return super.buildIdentity(metadata);
    }

    @NonNull
    private static String readString(@NonNull MediaMetadata metadata, @NonNull String key) {
        String value = metadata.getString(key);
        return value != null ? value : "";
    }
}
