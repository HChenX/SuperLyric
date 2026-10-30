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
import com.hchen.superlyric.kernel.orchestrate.LyricOrchestrator;
import com.hchen.superlyric.provider.base.HookLyricSource;
import com.hchen.superlyric.provider.base.MusicProvider;

import java.util.Locale;

/**
 * 酷我音乐歌词提供者基类。
 *
 * <p>手机版与车机版共用取词和身份逻辑，仅目标包名不同。取词只走 Hook：锚点位于宿主把
 * 明文 LRCX 交给歌词引擎的入口，逐字信息在 {@code <v1,v2>} 标签中。酷我没有可用的公开
 * 歌词接口，因此不挂跨源 API 兜底。
 *
 * <p>只发布全量歌词，进度由系统时钟负责。{@link #buildIdentity} 以宿主 {@code rid}
 * 作为稳定标识，避免 Hook 捕获与元数据身份不一致。
 *
 * @author 焕晨HChen
 */
abstract class KuwoBaseProvider extends MusicProvider {

    /**
     * 稳定标识的命名空间；两个变体共用，rid 在平台内唯一
     */
    private static final String NAMESPACE = "kuwo";

    /**
     * Hook 取词的等待上限；见 {@link #orchestratorConfig}
     */
    private static final long HOOK_TIMEOUT_MS = 3_000L;

    /**
     * 以下三项用于补齐 Kotlin 全参构造；前两项保持基类默认值
     */
    private static final int DEFAULT_MAX_API_ATTEMPTS = 3;

    private static final long DEFAULT_RETRY_BASE_DELAY_MS = 500L;

    /**
     * 允许“标题后补”原地生效的窗口
     *
     * <p>比基类默认的 3s 宽：酷我曲目探针要等宿主取词入口被调用才拿到干净标题；
     * 取词入口调用较晚时可在 4.2s 后才拿到标题，超出默认窗口会被当成换歌，
     * 清屏后重新取词同一首。有声书换集是分钟级事件，10s 窗口不会吞掉真实换集。
     */
    private static final long DEFAULT_PROVISIONAL_GRACE_MS = 10_000L;

    private final String providerTag;

    protected KuwoBaseProvider(@NonNull String providerTag) {
        this.providerTag = providerTag;
    }

    /**
     * Hook 取词来源；同时用于读取宿主的真实曲目信息
     *
     * <p>{@code volatile} 必需：在 {@code onPackageReady} 线程写，在宿主
     * {@code MediaSession} 回调线程经 {@link #buildIdentity} 读。可见性失败时读到 null，
     * 会退化为使用宿主元数据；酷我会把当前歌词行写进 TITLE，元数据不可信。
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

    /**
     * 不挂 API 兜底源
     *
     * <p>Hook 侧拿到宿主本地已解密的 LRCX 明文，与当前播放严格同源，不需要联网或按标题
     * 检索，因此不存在匹配误差。
     *
     * <p>酷狗兜底是「按歌名 + 歌手搜索」的跨源检索：酷我的有声书 / TME AI 字幕在酷狗库中
     * 的标题、歌手对不上，检索器只能模糊命中其它内容，从而发布另一本书的歌词。
     *
     * <p>一旦挂上，hook 超时后它会被无条件启用（编排器 {@code planStartApi} 不判断能否匹配），
     * 正确的整首歌词会被顶掉，两套歌词共用同一 rid 与播放位置互相覆盖。
     *
     * <p>因此返回 null：等待 hook 窗口已放宽到 3 秒，避免错误结果。
     */
    @Nullable
    @Override
    protected LyricSource createApiSource() {
        return null;
    }

    /**
     * Hook 等待窗口放宽到 3 秒
     *
     * <p>默认 800ms 对酷我不够：宿主把解密后的 LRCX 交给渲染层的耗时在 0.5~1.2s 之间波动，
     * 内容越大越慢。
     *
     * <p>窗口到点后编排器先按“暂时失败”降级；该 Provider 没有网络源，于是直接落到不可用。
     * 迟到的 Hook 结果仍会被采纳：超时只说明本次未等到，不是 Hook 失败。
     * 放大到 3 秒是为了让多数曲目一次等到，减少“先无歌词、随后补上”的闪烁；
     * 无歌词曲目的降级因此更慢。
     */
    @NonNull
    @Override
    protected LyricOrchestrator.Config orchestratorConfig() {
        // 必须传全四个参数：Kotlin 默认参数不会为 Java 生成重载，只有全参构造可用。
        // 第二、三项保持基类默认值，标题宽限使用本类常量
        return new LyricOrchestrator.Config(
            HOOK_TIMEOUT_MS,
            DEFAULT_MAX_API_ATTEMPTS,
            DEFAULT_RETRY_BASE_DELAY_MS,
            DEFAULT_PROVISIONAL_GRACE_MS
        );
    }

    /**
     * 从元数据构建音轨身份
     *
     * <p>标识必须是 rid + 标题的组合，不能只用 rid：酷我的 {@code Music.rid} 在有声书上
     * 是专辑级 ID，同一部书的所有集共用同一个值（{@code MEDIA_ID} 同样如此）。
     * 只用 rid 时，从「第481集」切到「第489集」会被判成同一首，新一集歌词被当作
     * 重复数据丢弃或错配，表现为播放几分钟后歌词与内容不符。
     *
     * <p>标题取宿主 {@code Music} 对象上的干净字段而非元数据：酷我会把当前歌词行写进
     * {@code METADATA_KEY_TITLE}，元数据标题不可用于判等。
     */
    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        // 当前曲目的 rid 只能取元数据自己的 MEDIA_ID，不能取 Hook 侧最近一次取词的 rid。
        //
        // 酷我会在播放列表中预取后续曲目：预取时曲目探针拿到尚未播放的 Music，而元数据回调
        // 仍属于当前曲目。若用探针的 rid 拼身份，每次元数据回调都会得到下一首的身份，
        // 编排器据此判定切歌，清屏、取词并把下一首歌词发布到当前曲目。例如《远山少年》播放中，
        // 身份会被翻成 kuwo:1697454#盛夏的果实，随后整首播放《盛夏的果实》的歌词。
        // 该问题与“元数据标题是歌词行”无关，由预取时序造成。
        //
        // 探针的 rid 仍用于把 Hook 歌词正文绑定到所属曲目（见 KuwoHookSource）；
        // 预取的歌词因此可按 rid 暂存，待该曲目开始播放时补投。
        String mediaId = usableRid(readString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
        String captured = usableRid(hookStableId());
        String rid = !mediaId.isEmpty() ? mediaId : captured;
        if (!rid.isEmpty()) {
            KuwoHookSource hook = kuwoHookSource;
            // 组装稳定标识的标题只能取宿主 Music 对象上的干净字段，不能取元数据里的 TITLE。
            //
            // 酷我每唱一行都会把该行写入 METADATA_KEY_TITLE，而 Hook 侧的 stableIdOf
            // 使用宿主标题。使用元数据标题会得到 kuwo:<rid>#<当前歌词行>，与 Hook 上报的
            // kuwo:<rid>（或 kuwo:<rid>#<宿主题名>）不一致，已获取的歌词会被判为其它曲目而丢弃。
            String hostTitle = hook != null ? hook.trackTitle(rid) : "";
            String hostArtist = hook != null ? hook.trackArtist(rid) : "";
            String mergedTitle = hostTitle.isEmpty() ? title : hostTitle;
            String mergedArtist = hostArtist.isEmpty() ? artist : hostArtist;
            // 标题为空时退化为只用 rid（歌曲场景仍可靠）
            return new TrackIdentity(
                providerTag, stableIdOf(rid, hostTitle), mergedTitle, mergedArtist, duration);
        }

        return super.buildIdentity(metadata);
    }

    /**
     * 过滤占位标识
     *
     * <p>宿主可能把 {@code 0} / {@code -1} / {@code null} 等常量写入 {@code MEDIA_ID}。
     * 把它们当作真实标识会让所有曲目身份相等，编排器按同一首处理、永不重新取词。
     */
    @NonNull
    private static String usableRid(@Nullable String raw) {
        String trimmed = raw != null ? raw.trim() : "";
        switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "", "0", "-1", "null", "unknown", "none":
                return "";
            default:
                return trimmed;
        }
    }

    /**
     * 组装稳定标识：rid 与标题的组合
     *
     * <p>标题参与哈希，同一部有声书的不同集会得到不同标识；歌曲场景标题稳定，
     * 组合标识与只用 rid 等效。
     */
    @NonNull
    private static String stableIdOf(@NonNull String rid, @NonNull String title) {
        String trimmedTitle = title.trim();
        if (trimmedTitle.isEmpty()) {
            return TrackIdentity.stableId(NAMESPACE, rid);
        }
        // 标题直接拼在 rid 后：stableId 只是 opaque 字符串，比较时整体判等
        return TrackIdentity.stableId(NAMESPACE, rid + "#" + trimmedTitle);
    }

    @NonNull
    private static String readString(@NonNull MediaMetadata metadata, @NonNull String key) {
        String value = metadata.getString(key);
        return value != null ? value : "";
    }
}
