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
import com.hchen.superlyric.kernel.map.SyncedLyricsMapper
import com.hchen.superlyric.kernel.merge.SidecarChannel
import com.hchen.superlyric.kernel.merge.TimeText
import com.hchen.superlyric.kernel.merge.TranslationMerger
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.parse.KuwoLrcxParser
import com.hchen.superlyric.kernel.parse.QrcParser
import com.hchen.superlyric.kernel.parse.SodaJsonParser
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import java.nio.charset.StandardCharsets

/**
 * 载荷格式。由调用方（Provider）显式声明——它本来就知道数据来自哪个平台。
 * 仅当确实无法确定时才使用 [AUTO]。
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

    /** 通用文本格式：LRC / 增强 LRC / TTML / YRC / Lyricify Syllable，交由库自动识别 */
    GENERAL,

    /** 由载荷自身特征推断 */
    AUTO,
}

/**
 * 一次解析请求。
 *
 * @property format          载荷格式
 * @property bytes           原始字节（加密载荷走此通道）
 * @property text            原始文本（已解密载荷走此通道）
 * @property translationText 独立下发的译文载荷（伴随通道）
 * @property phoneticText    独立下发的罗马音载荷（伴随通道）
 * @property title           曲名，用于补齐载荷中缺失的元数据
 * @property artist          歌手
 * @property album           专辑
 * @property durationMs      曲目总时长
 * @property lyricId         曲目唯一标识
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
 * 歌词解析管线门面。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li><b>解密</b>（[KrcCipher] / [QrcCipher]）——本模块自研，稳定且需字节级精确；</li>
 *   <li><b>解析</b>——库覆盖的格式走 [AutoParser]；库不覆盖的（QRC / LRCX / Soda JSON）
 *       走本模块自研解析器，直接产出 [LyricDoc]；</li>
 *   <li><b>对齐</b>——所有译文 / 罗马音统一走 [TranslationMerger]；</li>
 *   <li><b>规范化</b>——排序、时序单调、丢弃空行，产出的 [LyricDoc] 保证可用。</li>
 * </ul>
 *
 * <h3>返回值语义（供上层归类为 LyricOutcome）</h3>
 * <ul>
 *   <li>返回 [LyricDoc] → 解析成功；</li>
 *   <li>返回 {@code null} → **确定性地没有歌词**（载荷为空、或解析后没有任何有效行）；</li>
 *   <li>抛出 [DecodeException] → 解码失败，属**可重试**情形，上层绝不可据此下发纯音乐占位。</li>
 * </ul>
 *
 * @author 焕晨HChen
 */
object LyricPipeline {

    private val autoParser = AutoParser()

    /**
     * 解析一次请求。
     *
     * @throws DecodeException 原始载荷无法解密
     */
    fun parse(request: LyricRequest): LyricDoc? {
        val content = resolveContent(request) ?: return null
        if (content.isBlank()) return null

        val format = if (request.format == LyricFormat.AUTO) sniff(content) else request.format

        val doc = when (format) {
            LyricFormat.QRC -> QrcParser.parse(content)
            LyricFormat.LRCX -> KuwoLrcxParser.parse(content) ?: parseByLibrary(content)
            LyricFormat.SODA_JSON -> SodaJsonParser.parse(content)
            LyricFormat.KRC, LyricFormat.GENERAL -> parseByLibrary(content)
            LyricFormat.AUTO -> parseByLibrary(content)
        } ?: return null

        return finalize(doc, request)
    }

    /**
     * 取得明文载荷。
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
     * 仅在格式未知时使用的特征嗅探。
     *
     * 判据全部是**结构性强特征**（头部标签 / 根 JSON 键 / XML 根元素），
     * 不做"看起来像"的模糊判断。
     */
    private fun sniff(content: String): LyricFormat {
        val head = content.trimStart()
        return when {
            head.startsWith("{") && head.contains("\"sentences\"") -> LyricFormat.SODA_JSON
            KuwoLrcxParser.canParse(content) -> LyricFormat.LRCX
            QrcCipher.isPlainQrc(content) -> LyricFormat.QRC
            KRC_LINE_MARKER.containsMatchIn(content) -> LyricFormat.KRC
            // 网易云 YRC 必须排在 QRC 之前判定
            YRC_LINE_MARKER.containsMatchIn(content) -> LyricFormat.GENERAL
            QRC_LINE_MARKER.containsMatchIn(content) -> LyricFormat.QRC
            else -> LyricFormat.GENERAL
        }
    }

    /**
     * 网易云 YRC 行的结构特征：{@code [数字,数字]} 后跟**三元组** {@code (数字,数字,数字)} 词标签。
     *
     * <h4>为什么必须与 QRC 分开识别</h4>
     * QRC 与 YRC 的词标签长得极像，但语义**相反**：
     * <ul>
     *   <li>QRC 的 {@code wStart} 是**行内相对偏移**；</li>
     *   <li>YRC 的 {@code wStart} 是**歌曲绝对毫秒**。</li>
     * </ul>
     * 若把 YRC 误判为 QRC，每个词的起始时间会被再加上一次行起始时间，
     * 逐字高光会整体漂移。二者的可靠区分点是**分量个数**：
     * YRC 恒为 {@code (a,b,flag)} 三元组，QRC 为 {@code (a,b)} 二元组。
     */
    private val YRC_LINE_MARKER = Regex("""\[\d+,\d+][^\n]*\(\d+,\d+,\d+\)""")

    /**
     * 裸 QRC 行的结构特征：{@code [数字,数字]} 后跟**二元组** {@code (数字,数字)} 词标签。
     * 要求闭合括号，因此三元组不会误入此分支。
     */
    private val QRC_LINE_MARKER = Regex("""\[\d+,\d+][^\n]*\(\d+,\d+\)""")

    /** KRC 明文行的结构特征：{@code [数字,数字]} 后跟 {@code <数字,数字,数字>} */
    private val KRC_LINE_MARKER = Regex("""\[\d+,\d+][^\n]*<\d+,\d+,\d+>""")

    private fun parseByLibrary(content: String): LyricDoc? {
        val parsed = autoParser.parse(content)
        if (parsed.lines.isEmpty()) return null
        return SyncedLyricsMapper.toDoc(parsed)
    }

    /**
     * 规范化并补齐伴随通道与元数据。
     */
    private fun finalize(doc: LyricDoc, request: LyricRequest): LyricDoc? {
        var result = doc

        attachSidecar(result, request.translationText, SidecarChannel.TRANSLATION)?.let { result = it }
        attachSidecar(result, request.phoneticText, SidecarChannel.PHONETIC)?.let { result = it }

        val lines = normalize(result.lines)
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

    private fun attachSidecar(doc: LyricDoc, text: String?, channel: SidecarChannel): LyricDoc? {
        if (text.isNullOrBlank()) return null
        val entries = parseSidecarText(text)
        if (entries.isEmpty()) return null
        return TranslationMerger.attach(doc, entries, channel)
    }

    /**
     * 解析伴随载荷为「时间戳 → 文本」。
     *
     * 只接受两种结构性形态，二者可确定区分：
     * <ul>
     *   <li>毫秒对形态 {@code [start,dur]text}（QRC 系）；</li>
     *   <li>分秒形态 {@code [mm:ss.xx]text}（LRC 系，经库解析）。</li>
     * </ul>
     */
    private fun parseSidecarText(text: String): List<TimeText> {
        if (MILLISECOND_LINE.containsMatchIn(text)) {
            return QrcParser.parseSidecar(text)
        }
        val parsed = autoParser.parse(text)
        if (parsed.lines.isEmpty()) return emptyList()
        return parsed.lines.map { line ->
            TimeText(line.start.toLong(), lineText(line))
        }.filter { it.text.isNotEmpty() }
    }

    private val MILLISECOND_LINE = Regex("""\[\d+,\d+]""")

    private fun lineText(line: com.mocharealm.accompanist.lyrics.core.model.ISyncedLine): String =
        when (line) {
            is com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine ->
                line.content.trim()

            is com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine ->
                line.syllables.joinToString("") { it.content }.trim()

            else -> ""
        }

    /**
     * 规范化：按起始时间排序、强制时序单调、丢弃空行。
     *
     * 历史缺陷中的「时序倒错」与「0ms 坍缩」在此层被结构性消除——
     * 它是模块内所有解析器的**共同出口**，任何格式都无法绕过。
     */
    private fun normalize(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()

        val result = ArrayList<LyricLine>(sorted.size)
        for (index in sorted.indices) {
            val current = sorted[index]
            val startMs = maxOf(0L, current.startMs)

            val nextStart = if (index + 1 < sorted.size) sorted[index + 1].startMs else -1L
            val lastWordEnd = current.words.maxOfOrNull { it.endMs } ?: startMs

            // 行结束时间不得超过下一行起始，避免行区间互相重叠
            val capByNext = if (nextStart > startMs) nextStart else Long.MAX_VALUE
            val endMs = minOf(maxOf(startMs, current.endMs, lastWordEnd), capByNext)

            val words = current.words.map { word ->
                val wordStart = word.startMs.coerceIn(startMs, maxOf(startMs, endMs))
                val wordEnd = word.endMs.coerceAtLeast(wordStart)
                if (word.startMs == wordStart && word.endMs == wordEnd) word
                else word.copy(startMs = wordStart, endMs = wordEnd)
            }

            result += current.copy(startMs = startMs, endMs = endMs, words = words)
        }
        return result
    }
}
