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
package com.hchen.superlyric.kernel.pipeline

import com.hchen.superlyric.kernel.decode.DecodeException
import com.hchen.superlyric.kernel.decode.KrcCipher
import com.hchen.superlyric.kernel.decode.QrcCipher
import com.hchen.superlyric.kernel.merge.SidecarChannel
import com.hchen.superlyric.kernel.merge.TranslationMerger
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.parse.KrcLanguage
import com.hchen.superlyric.kernel.parse.QrcParser
import com.hchen.superlyric.kernel.text.LrcOffset
import com.hchen.superlyric.kernel.text.LrcTimestamps
import java.nio.charset.StandardCharsets

/**
 * 载荷格式
 *
 * 由调用方（Provider）显式声明，它本来就知道数据来自哪个平台。仅当确实无法确定时
 * 才使用 [AUTO]
 */
enum class LyricFormat {
    /** QQ 音乐 QRC（加密或明文） */
    QRC,

    /** 酷狗 KRC（加密或明文） */
    KRC,

    /** 酷我 / 波点 LRCX */
    LRCX,

    /** 汽水 / 波点 JSON */
    SODA_JSON,

    /** 通用文本格式：LRC / 增强 LRC / TTML / YRC / Lyricify Syllable，由检测器细分 */
    GENERAL,

    /** 由载荷自身特征推断 */
    AUTO,
}

/**
 * 一次解析请求
 */
data class LyricRequest @JvmOverloads constructor(
    val format: LyricFormat = LyricFormat.AUTO,
    val bytes: ByteArray? = null,
    val text: String? = null,
    val translationText: String? = null,
    val phoneticText: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val lyricId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LyricRequest) return false
        return format == other.format &&
                bytes.contentEquals(other.bytes) &&
                text == other.text &&
                translationText == other.translationText &&
                phoneticText == other.phoneticText &&
                title == other.title &&
                artist == other.artist &&
                album == other.album &&
                durationMs == other.durationMs &&
                lyricId == other.lyricId
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + (translationText?.hashCode() ?: 0)
        result = 31 * result + (phoneticText?.hashCode() ?: 0)
        result = 31 * result + title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + (lyricId?.hashCode() ?: 0)
        return result
    }
}

/**
 * 歌词解析管线门面
 *
 * 职责边界
 * - 解密（[KrcCipher] / [QrcCipher]）：本模块自研，稳定且需字节级精确
 * - 检测：[LyricFormatDetector] 按结构签名区分平台格式与普通文本
 * - 解析：[ParserRegistry] 候选链逐个尝试，专用解析器失败后回退通用库
 * - 对齐：所有译文 / 罗马音统一走 [TranslationMerger]
 * - 规范化：[LineNormalizer] 负责空行、顺序、时长和逐字边界
 */
object LyricPipeline {

    private const val MAX_TEXT_CHARS = 2_000_000

    /**
     * 解析一次请求
     *
     * @throws DecodeException 原始载荷无法解密
     */
    fun parse(
        request: LyricRequest,
        qrcStats: QrcParser.ParseStats? = null,
    ): LyricDoc? = parseDetailed(request, qrcStats, null)

    /**
     * 解析一次请求，并输出每个候选的接受/拒绝原因
     *
     * 不改变 [parse] 的返回契约；诊断回调异常不影响解析结果。
     */
    fun parseDetailed(
        request: LyricRequest,
        qrcStats: QrcParser.ParseStats? = null,
        onTrace: ((LyricParseTrace) -> Unit)? = null,
    ): LyricDoc? {
        fun trace(trace: LyricParseTrace) {
            if (onTrace != null) runCatching { onTrace(trace) }
        }

        val content = resolveContent(request) ?: return null
        if (content.isBlank()) return null
        if (content.length > MAX_TEXT_CHARS) {
            trace(LyricParseTrace("input", false, "text exceeds $MAX_TEXT_CHARS chars"))
            return null
        }

        val kind = runCatching {
            when (request.format) {
                LyricFormat.AUTO, LyricFormat.GENERAL -> LyricFormatDetector.detect(content)
                LyricFormat.QRC -> LyricTextKind.QRC
                LyricFormat.KRC -> LyricTextKind.KRC
                LyricFormat.LRCX -> LyricTextKind.LRCX
                LyricFormat.SODA_JSON -> LyricTextKind.SODA_JSON
            }
        }.getOrDefault(LyricTextKind.UNKNOWN)

        // KRC 把译文与罗马音放在头部 [language:...] 的 base64 JSON 中；先拆出并
        // 从主载荷移除，避免未知指令被通用解析器忽略、伴随通道整段丢失。
        val krcLanguage = if (kind == LyricTextKind.KRC) {
            runCatching { KrcLanguage.extract(content) }.getOrNull()
        } else {
            null
        }
        val base = krcLanguage?.main ?: content

        // 普通 LRC 的 [mm:ss]、[mm:ss:ff] 和 [mm:ss.xx] 统一成通用库可检测的
        // 三位小数；QRC/KRC/LRCX/Soda 使用各自的时间轴，不能改写。
        val canonical = if (kind.usesPlainTextTimeline()) {
            LrcTimestamps.canonicalize(base)
        } else {
            base
        }

        // 通用 LRC 的 [offset:±ms] 必须在解析前摘掉，解析完成后再统一平移。
        // QRC 的时间轴已有自己的 offset，KRC 不使用该指令，二者都不能走这里。
        val lrcOffset = if (kind.usesPlainTextTimeline()) LrcOffset.parse(canonical) else null
        val source = lrcOffset?.text ?: canonical

        // 走通用解析库的载荷，先剥掉"主行 + 同时间戳伴随行"里的伴随行。
        // 库判定哪一行是哪一行的伴随用的是时间区间重叠，相邻主行会互相误配，
        // 且配对后无法还原：必须在库拿到文本之前拆（详见 [CompanionSplitter]）。
        val split = runCatching {
            when (kind) {
                LyricTextKind.KRC,
                LyricTextKind.SALT,
                LyricTextKind.LRC,
                LyricTextKind.UNKNOWN,
                -> CompanionSplitter.split(source)

                else -> null
            }
        }.getOrNull()
        val body = split?.main ?: source
        val requestForFinalize = if (split == null) request else request.withSplitChannels(split)
        val candidates = runCatching {
            ParserRegistry.candidates(kind, body, split, qrcStats)
        }.getOrDefault(emptyList())

        for (candidate in candidates) {
            val canParse = runCatching(candidate.canParse).getOrDefault(false)
            if (!canParse) {
                trace(LyricParseTrace(candidate.id, false, "not matched"))
                continue
            }

            val raw = runCatching(candidate.parse).getOrNull()
            if (raw == null) {
                trace(LyricParseTrace(candidate.id, false, "no document"))
                continue
            }

            var result = runCatching { finalize(raw, requestForFinalize) }.getOrNull()
            if (result == null) {
                trace(LyricParseTrace(candidate.id, false, "no usable lines"))
                continue
            }

            if (krcLanguage != null) {
                if (krcLanguage.translation.isNotEmpty()) {
                    result = TranslationMerger.attach(result, krcLanguage.translation, SidecarChannel.TRANSLATION)
                }
                if (krcLanguage.phonetic.isNotEmpty()) {
                    result = TranslationMerger.attach(result, krcLanguage.phonetic, SidecarChannel.PHONETIC)
                }
            }

            trace(LyricParseTrace(candidate.id, true, null, result.lines.size))
            return if (kind.usesPlainTextTimeline() &&
                lrcOffset != null && lrcOffset.offsetMs != 0L
            ) {
                restoreAfterOffset(LrcOffset.apply(result, lrcOffset.offsetMs))
            } else {
                result
            }
        }
        return null
    }

    /** 文本格式使用普通 LRC 时间轴，需要 canonicalize 和 [offset:] 处理 */
    private fun LyricTextKind.usesPlainTextTimeline(): Boolean = when (this) {
        LyricTextKind.TTML,
        LyricTextKind.LYRICIFY,
        LyricTextKind.YRC,
        LyricTextKind.SALT,
        LyricTextKind.LRC,
        LyricTextKind.UNKNOWN,
        -> true

        else -> false
    }

    /** 把拆分出的伴随通道并入请求，统一交给 [finalize] 对齐 */
    private fun LyricRequest.withSplitChannels(split: CompanionSplitter.Split): LyricRequest = copy(
        translationText = joinChannels(split.translation, translationText),
        phoneticText = joinChannels(split.phonetic, phoneticText),
    )

    private fun joinChannels(primary: String?, secondary: String?): String? = when {
        primary.isNullOrBlank() -> secondary
        secondary.isNullOrBlank() -> primary
        else -> "$primary\n$secondary"
    }

    /**
     * 取得明文载荷
     *
     * 加密载荷（QRC / KRC）走字节通道解密；其余格式直接取文本通道。
     */
    @Throws(DecodeException::class)
    private fun resolveContent(request: LyricRequest): String? = when (request.format) {
        LyricFormat.QRC -> when {
            request.bytes != null && request.bytes.isNotEmpty() -> QrcCipher.decrypt(request.bytes)
            else -> request.text
        }

        LyricFormat.KRC -> when {
            request.bytes != null && request.bytes.isNotEmpty() -> KrcCipher.decrypt(request.bytes)
            else -> request.text
        }

        else -> request.text
            ?: request.bytes?.takeIf { it.isNotEmpty() }?.toString(StandardCharsets.UTF_8)
    }

    /**
     * offset 会把行/词压到 0 或推出 duration 窗口；重新折叠并规范化，
     * 恢复正时长、行内、不重叠和 duration 过滤。
     */
    private fun restoreAfterOffset(doc: LyricDoc): LyricDoc? {
        val lines = LineNormalizer.normalize(
            LineNormalizer.collapseSimultaneous(doc.lines),
            doc.durationMs,
        ).map { SidecarMerger.withoutEmptySidecars(it) }
        if (lines.isEmpty()) return null
        return doc.copy(lines = lines)
    }

    private fun finalize(doc: LyricDoc, request: LyricRequest): LyricDoc? {
        // 独立歌手标签先并入下一条主行，再参与同时刻折叠与译文对齐；否则标签行会被
        // 当成独立歌词行，或把本应属于下一行的译文截走。
        var result = doc.copy(lines = SpeakerLabelMerger.merge(doc.lines))
        // 先折叠同一时刻的行，再挂伴随通道：反过来的话，译文可能先挂到即将被折叠丢弃的
        // 伴随行上，折叠后整段译文消失（只由行序决定）。
        result = result.copy(lines = LineNormalizer.collapseSimultaneous(result.lines))

        SidecarMerger.attach(result, request.translationText, SidecarChannel.TRANSLATION)?.let { result = it }
        SidecarMerger.attach(result, request.phoneticText, SidecarChannel.PHONETIC)?.let { result = it }

        val lines = LineNormalizer.normalize(result.lines, maxOf(result.durationMs, request.durationMs))
            .map { SidecarMerger.withoutEmptySidecars(it) }
        if (lines.isEmpty()) return null

        return result.copy(
            lines = lines,
            title = result.title.ifBlank { request.title },
            artist = result.artist.ifBlank { request.artist },
            album = result.album.ifBlank { request.album },
            durationMs = if (result.durationMs > 0) result.durationMs else request.durationMs,
            lyricId = request.lyricId ?: result.lyricId,
        )
    }

}
