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
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.parse.KuwoLrcxParser
import com.hchen.superlyric.kernel.parse.QrcParser
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
 * 歌词解析管线门面
 *
 * ## 职责边界
 * - **解密**（[KrcCipher] / [QrcCipher]）—— 本模块自研，稳定且需字节级精确
 * - **解析** —— 库覆盖的格式走 [AutoParser]；库不覆盖的（QRC / LRCX / Soda JSON）
 *   走本模块自研解析器，直接产出 [LyricDoc]
 * - **对齐** —— 所有译文 / 罗马音统一走 [TranslationMerger]
 * - **规范化** —— 排序、时序单调、丢弃空行，产出的 [LyricDoc] 保证可用
 *
 * ## 返回值语义（供上层归类为 LyricOutcome）
 * - 返回 [LyricDoc] → 解析成功
 * - 返回 `null` → **本层没能产出任何有效行**：载荷为空、格式不认识、或解析器一行都没读出来。
 *   它**不是**"平台确定没有歌词"——那句话只能由平台自身给出（如"无版权/无歌词"标记）。
 *   上层据此下发纯音乐占位前，必须自己确认这一点
 * - 抛出 [DecodeException] → 解码失败，属**可重试**情形，上层绝不可据此下发纯音乐占位
 *
 * @author 焕晨HChen
 */
object LyricPipeline {

    /**
     * 解析一次请求。
     *
     * @throws DecodeException 原始载荷无法解密
     */
    fun parse(
        request: LyricRequest,
        qrcStats: com.hchen.superlyric.kernel.parse.QrcParser.ParseStats? = null,
    ): LyricDoc? {
        val content = resolveContent(request) ?: return null
        if (content.isBlank()) return null

        val format = if (request.format == LyricFormat.AUTO) sniff(content) else request.format

        // 走通用解析库的载荷，先剥掉"主行 + 同时间戳伴随行"里的伴随行。
        // 库判定哪一行是哪一行的伴随用的是**时间区间重叠**，相邻主行会互相误配，
        // 且配对后无法还原——必须在库拿到文本之前拆（详见 [CompanionSplitter]）。
        val split = when (format) {
            LyricFormat.GENERAL, LyricFormat.KRC -> CompanionSplitter.split(content)
            else -> null
        }
        val body = split?.main ?: content

        val doc = ParserRegistry.parse(format, body, split, qrcStats) ?: return null

        return finalize(doc, if (split == null) request else request.withSplitChannels(split))
    }

    /** 把拆分出的伴随通道并入请求，统一交给 [finalize] 对齐。 */
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
     * 网易云 YRC 行的结构特征：`[数字,数字]` 后跟**三元组** `(数字,数字,数字)` 词标签。
     *
     * #### 为什么必须与 QRC 分开识别
     * QRC 与 YRC 的词标签形态极像，词标签**分量个数**是二者唯一可靠的区分点：
     * YRC 恒为 `(a,b,flag)` 三元组，QRC 为 `(a,b)` 二元组。
     * 二者识别错误会让整段报文进错解析器。
     *
     * #### 关于词起始的时间基准
     * 词起始的时间基准**不能按格式名推断**：两种基准在两种格式里都出现过
     * （QQ 的 QRC 实测就是绝对毫秒）。
     * [com.hchen.superlyric.kernel.parse.QrcParser] 因此按首词逐行自动判定基准，
     * **不要**再依据格式名去假设时间基准。
     */
    private val YRC_LINE_MARKER = Regex("""\[\d+,\d+][^\n]*\(\d+,\d+,\d+\)""")

    /**
     * 裸 QRC 行的结构特征：`[数字,数字]` 后跟**二元组** `(数字,数字)` 词标签。
     * 要求闭合括号，因此三元组不会误入此分支。
     */
    private val QRC_LINE_MARKER = Regex("""\[\d+,\d+][^\n]*\(\d+,\d+\)""")

    /** KRC 明文行的结构特征：`[数字,数字]` 后跟 `<数字,数字,数字>` */
    private val KRC_LINE_MARKER = Regex("""\[\d+,\d+][^\n]*<\d+,\d+,\d+>""")

    /**
     * 规范化并补齐伴随通道与元数据。
     */
    private fun finalize(doc: LyricDoc, request: LyricRequest): LyricDoc? {
        // 先折叠同一时刻的行，再挂伴随通道：反过来的话，译文可能先挂到即将被折叠丢弃的
        // 伴随行上，折叠后整段译文消失（只由行序决定）。
        var result = doc.copy(lines = LineNormalizer.collapseSimultaneous(doc.lines))

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
