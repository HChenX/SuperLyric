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
package com.hchen.superlyric.provider.spotify

import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.kernel.identity.TrackIdentity
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.model.LyricWord
import com.hchen.superlyric.kernel.orchestrate.LyricSource
import com.hchen.superlyric.kernel.outcome.LyricOutcome
import com.hchen.superlyric.provider.base.FetchSlot
import com.hchen.superlyric.utils.LyricCacheStore
import com.hchen.superlyric.utils.LyricDiag

/**
 * Spotify 官方接口取词来源
 *
 * ## 流程
 * - 等 [SpotifyAnchors] 从宿主的 `okhttp3.Headers` 旁路捕获到会话头
 * - 以该会话头拉取 `color-lyrics/v2`（protobuf）
 * - 解析结果（含逐字与音译）映射为内核模型
 *
 * <p>本模块**不持有任何 Spotify 凭证**，只是复用宿主已经发出的请求头；
 * 会话头失效时返回可重试失败，由编排器退避重试。
 *
 * ## 结果语义
 * - `LyricNotFoundException` → [LyricOutcome.NoLyrics]——平台明确表示无歌词
 * - 鉴权失败 / 网络异常 / 会话头尚未就绪 → [LyricOutcome.TransientFailure]
 *
 * @author 焕晨HChen
 */
class SpotifyApiSource : LyricSource {

    override val sourceName: String = "SpotifyApi"

    private val requests = FetchSlot()

    override fun start(identity: TrackIdentity, session: LyricSource.Session) {
        val token = requests.open()
        requests.execute {
            val outcome = runCatching { resolve(identity, token) }
                .getOrElse { error ->
                    AndroidLog.logW(sourceName, "Fetch failed: ${error.message}")
                    LyricOutcome.TransientFailure(error)
                }
            if (requests.isCurrent(token)) session.deliver(outcome)
        }
    }

    override fun cancel() {
        requests.invalidate()
        SpotifyLyricAnalysis.cancelExcept(null)
    }

    private fun resolve(identity: TrackIdentity, token: Long): LyricOutcome {
        val trackId = TrackIdentity.normalizeStableId(identity.stableId)
            ?: return LyricOutcome.Unsupported("spotify: no track id")

        val cacheKey = "${LyricCacheStore.currentLocaleTag()}/$trackId"
        readCached(cacheKey, identity)?.let { return it }

        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (!requests.isCurrent(token)) {
                // 已切歌或被取消：再重试多少次也没人收。原来是 `return@repeat`，
                // 那只是跳过本轮、循环照转，白占着线程睡满整个退避窗口
                return LyricOutcome.TransientFailure(null)
            }

            val headers = SpotifyLyricAnalysis.currentHeaders()
            if (headers == null) {
                // 宿主尚未发出任何请求，会话头还没被捕获到
                sleep(HEADER_WAIT_MS)
                return@repeat
            }
            try {
                val data = SpotifyLyricAnalysis.fetchLyric(trackId, headers)
                val lines = SpotifyLyricAnalysis.parseLyrics(data).orEmpty()
                if (lines.isEmpty()) {
                    // 真正的"无歌词"已由 404 → LyricNotFoundException 处理掉了。
                    // 这里是拿到了响应体却读不出行——属"这次查不成"，绝不能当确定无歌词
                    return LyricOutcome.TransientFailure(null)
                }
                SpotifyLyricAnalysis.invalidate(headers)
                // 只缓存**已成功解析**的 protobuf 原始响应；命中后连会话头都不再需要，
                // 于是 VIP 过期 / 断网时仍能拿到已缓存曲目的歌词
                LyricCacheStore.putAsync(null, PROVIDER_NAME, cacheKey, data)

                val doc = toDoc(lines, identity)
                LyricDiag.dumpDoc(sourceName, doc)
                AndroidLog.logI(
                    sourceName,
                    "Fetched lyric for $trackId, lines=${doc.lines.size}, " +
                        "wordTimed=${doc.lines.any { it.hasWords }}, " +
                        "phonetic=${doc.lines.any { it.hasPhonetic }}"
                )
                return LyricOutcome.Success(doc)
            } catch (e: LyricNotFoundException) {
                return LyricOutcome.NoLyrics("spotify: platform reports no lyric")
            } catch (e: AuthenticationException) {
                // 会话头可能尚未捕获到；短暂等待后重试
                lastError = e
                sleep(HEADER_WAIT_MS)
            } catch (e: Throwable) {
                lastError = e
                AndroidLog.logW(sourceName, "Attempt ${attempt + 1} failed: ${e.message}")
                sleep(HEADER_WAIT_MS)
            }
        }

        return if (lastError is AuthenticationException) {
            AndroidLog.logW(sourceName, "Session headers were never captured; retry later")
            LyricOutcome.TransientFailure(lastError)
        } else {
            LyricOutcome.TransientFailure(lastError)
        }
    }

    /**
     * 读磁盘缓存并复核
     *
     * 命中后重新解析一遍：缓存损坏、或解析器变更导致旧响应不再可用时，删掉该条目
     * 并回落网络。**绝不**把"缓存不可用"当成"平台没有歌词"——后者是终态。
     *
     * 缓存键带语言标签：`color-lyrics/v2` 的译文随 `clientLanguage` 变化，
     * 不带语言会在用户切换应用语言后拿到旧语言的歌词。
     */
    private fun readCached(key: String, identity: TrackIdentity): LyricOutcome? {
        val bytes = LyricCacheStore.getBytes(null, PROVIDER_NAME, key) ?: return null

        val lines = runCatching { SpotifyLyricAnalysis.parseLyrics(bytes) }.getOrNull().orEmpty()
        if (lines.isNotEmpty()) {
            AndroidLog.logI(sourceName, "Lyric disk cache hit for $key")
            val doc = toDoc(lines, identity)
            LyricDiag.dumpDoc(sourceName, doc)
            return LyricOutcome.Success(doc)
        }

        AndroidLog.logW(sourceName, "Discarding unusable cache entry for $key")
        LyricCacheStore.delete(null, PROVIDER_NAME, key)
        return null
    }

    private fun toDoc(lines: List<SpotifyLine>, identity: TrackIdentity): LyricDoc {
        val mapped = lines.mapNotNull { line ->
            if (line.text.isBlank()) return@mapNotNull null
            LyricLine(
                startMs = line.startTimeMs,
                endMs = line.endTimeMs,
                text = line.text,
                phonetic = line.transliteratedWords?.takeIf { it.isNotBlank() },
                words = line.words?.map { LyricWord(it.word, it.startTime, it.endTime) }.orEmpty(),
            )
        }.sortedBy { it.startMs }

        return LyricDoc(
            lines = mapped,
            title = identity.title,
            artist = identity.artist,
            durationMs = identity.durationMs,
            lyricId = identity.stableId,
        )
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /** 磁盘缓存命名空间，须与 `LyricCacheStore.ONLINE_PROVIDERS` 一致 */
        const val PROVIDER_NAME = "Spotify"

        const val MAX_ATTEMPTS = 3
        const val HEADER_WAIT_MS = 400L
    }
}
