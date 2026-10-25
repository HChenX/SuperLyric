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

/**
 * 酷我音乐歌词提供者的公共基类
 *
 * <p>手机版与车机版共用这一套取词与身份逻辑，两者只差目标包名（见
 * {@link KuwoProvider} 与 {@link KuwoCarProvider}）。歌词管线虽然同构，
 * 但混淆后的包名与类名都不同，由锚点动态求出，这里不需要区分。
 *
 * <h3>取词路径</h3>
 * <p>只有 Hook 一条路：锚在宿主把明文 LRCX 交给自己的歌词引擎那一个瞬间，
 * 拿到的是<b>解密后、未被宿主行/词模型裁切</b>的全文，因此逐字时戳不会出现
 * 0ms 坍缩。酷我的逐字信息就藏在这份 LRCX 的 {@code <v1,v2>} 标签里。
 *
 * <p>不挂跨源 API 兜底，原因见 {@link #createApiSource()}：酷我没有可用的公开歌词接口，
 * 只能按歌名 + 歌手去别的库检索，而酷我的有声书 / TME AI 字幕在那里对不上，
 * 模糊命中会发布<b>另一本书</b>的歌词。宁可多等 Hook，也不要一个错的结果。
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

    /**
     * 稳定标识的命名空间；两个变体共用，rid 在平台内唯一
     */
    private static final String NAMESPACE = "kuwo";

    /** Hook 取词的等待上限；见 {@link #orchestratorConfig} */
    private static final long HOOK_TIMEOUT_MS = 3_000L;

    /** 以下三项只是凑全 Kotlin 全参构造；前两项保持基类默认值 */
    private static final int DEFAULT_MAX_API_ATTEMPTS = 3;

    private static final long DEFAULT_RETRY_BASE_DELAY_MS = 500L;

    /**
     * 允许"标题后补"原地生效的窗口
     *
     * <p>比基类默认的 3s 宽：酷我曲目探针要等宿主取词入口被调用才拿到干净标题，
     * 实测有一次晚了 4.2s，超出默认窗口就被当成换歌，清屏后重新取词同一首。
     * 有声书换集是分钟级事件，10s 的窗口吞不掉真实换集。
     */
    private static final long DEFAULT_PROVISIONAL_GRACE_MS = 10_000L;

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

    /**
     * 刻意不挂 API 兜底源。
     *
     * <p>Hook 侧拿到的是宿主<b>本地已解密</b>的 LRCX 明文，与当前播放严格同源，
     * 不需要联网、不需要按标题检索，因此不存在匹配误差。
     *
     * <p>而酷狗兜底是「按歌名 + 歌手搜索」的跨源检索：酷我的有声书 / TME AI 字幕
     * 在酷狗库里的标题、歌手对不上，检索器只能模糊命中别的内容，于是发布<b>另一本书</b>
     * 的歌词 —— 表现为歌词和当前播放完全不对。
     *
     * <p>而且它一旦挂上，就会在 hook 超时后被无条件启用（编排器 {@code planStartApi}
     * 不看能否匹配上），hook 那边明明正确的整首歌词被顶掉，两套歌词共用同一个 rid 与
     * 播放位置互相抢替 —— 表现为歌词和当前播放完全不对。
     *
     * <p>所以这里返回 null：宁可多等 hook（窗口已放宽到 3 秒），也不要一个错的结果。
     */
    @Nullable
    @Override
    protected LyricSource createApiSource() {
        return null;
    }

    /**
     * Hook 等待窗口放宽到 3 秒
     *
     * <p>默认 800ms 对酷我不够用：实测宿主把解密后的 LRCX 交给渲染层的耗时在
     * 0.5~1.2s 之间波动，内容越大越慢。
     *
     * <p>窗口到点后编排器先按"暂时失败"降级（本 Provider 没有网络源，于是直接落到不可用），
     * 而<b>迟到</b>的 Hook 结果仍会被采纳 —— 超时只说明这一次没等到，不是 Hook 失败了。
     * 放大到 3 秒是为了让大多数曲目一次就等到，少一次"先无歌词、随后补上"的闪烁；
     * 代价是无歌词曲目的降级慢一些。
     */
    @NonNull
    @Override
    protected LyricOrchestrator.Config orchestratorConfig() {
        // 必须传全四个参数：Kotlin 的默认参数不会为 Java 生成重载，
        // 只有全参构造可用。后三项保持基类默认值不变
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
     * <p><b>标识必须是 rid + 标题的组合</b>，不能只用 rid：实测酷我的 {@code Music.rid}
     * 在有声书上是<b>专辑级</b> ID，同一部书的所有集共用同一个值（{@code MEDIA_ID} 同样如此）。
     * 只用 rid 时，从「第481集」切到「第489集」会被判成同一首歌，新一集的歌词被当成
     * 重复数据丢弃或错配 —— 表现为播放几分钟后歌词和内容对不上。
     *
     * <p>标题取宿主 {@code Music} 对象上的干净字段而不是元数据：酷我会把<b>当前歌词行</b>
     * 写进 {@code METADATA_KEY_TITLE}（实测每次回调都变），元数据里的标题不可用于判等。
     */
    @NonNull
    @Override
    protected TrackIdentity buildIdentity(@NonNull MediaMetadata metadata) {
        String title = readString(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = readString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        // 当前曲目的 rid 只能取元数据自己的 MEDIA_ID，不能取 Hook 侧最近一次取词的 rid。
        //
        // 酷我会在播放列表里预取后面几首的歌词：预取时曲目探针拿到的是**还没开始播**那一首
        // 的 Music，而元数据回调仍然是当前这一首。若用探针的 rid 拼身份，每次元数据回调都会
        // 得到一个属于下一首的身份，编排器据此判定"切歌"——清屏、取词、把下一首的歌词发布到
        // 当前曲目上。真机实测：远山少年播放中，身份被翻成 kuwo:1697454#盛夏的果实，
        // 随后整首都在放盛夏的果实。这条与"元数据标题是歌词行"无关，是预取时序造成的。
        //
        // 探针的 rid 仍然有用：它把 Hook 拿到的歌词正文与它自己那一首绑定（见 KuwoHookSource），
        // 预取的歌词因此能按 rid 暂存，等那一首真正开始时直接补投。
        String mediaId = usableRid(readString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
        String captured = usableRid(hookStableId());
        String rid = !mediaId.isEmpty() ? mediaId : captured;
        if (!rid.isEmpty()) {
            KuwoHookSource hook = kuwoHookSource;
            // 组装稳定标识的标题只能取宿主 Music 对象上的干净字段，绝不能取元数据里的 TITLE。
            //
            // 酷我每唱一行就把那行歌词写进 METADATA_KEY_TITLE，而 Hook 侧的 stableIdOf
            // 用的是宿主标题。一旦这里用元数据标题拼标识，就会得到 kuwo:<rid>#<当前歌词行>，
            // 与 Hook 上报的 kuwo:<rid>（或 kuwo:<rid>#<宿主题名>）不是同一个字符串，
            // 于是 Hook 已经拿到的歌词被判为"别的曲目"而丢弃 —— 表现为歌词时有时无。
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
     * <p>宿主可能把 {@code 0} / {@code -1} / {@code null} 这类常量写进 {@code MEDIA_ID}。
     * 把它们当成真实标识会让所有曲目的身份相等，编排器按"同一首"处理、永不重新取词。
     */
    @NonNull
    private static String usableRid(@Nullable String raw) {
        String trimmed = raw != null ? raw.trim() : "";
        switch (trimmed.toLowerCase(java.util.Locale.ROOT)) {
            case "", "0", "-1", "null", "unknown", "none":
                return "";
            default:
                return trimmed;
        }
    }

    /**
     * 组装稳定标识：rid 与标题的笛卡尔积
     *
     * <p>标题参与哈希，因此同一部有声书的不同集会得到不同标识；而歌曲场景下标题稳定，
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
