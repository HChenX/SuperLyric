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

        // 走通用解析库的载荷，先剥掉"主行 + 同时间戳伴随行"里的伴随行。
        // 库判定哪一行是哪一行的伴随用的是**时间区间重叠**，相邻主行会互相误配，
        // 且配对后无法还原——必须在库拿到文本之前拆（详见 [CompanionSplitter]）。
        val split = when (format) {
            LyricFormat.GENERAL, LyricFormat.KRC -> CompanionSplitter.split(content)
            else -> null
        }
        val body = split?.main ?: content

        val doc = when (format) {
            LyricFormat.QRC -> QrcParser.parse(body)
            LyricFormat.LRCX -> KuwoLrcxParser.parse(body) ?: parseByLibrary(body)
            LyricFormat.SODA_JSON -> SodaJsonParser.parse(body)
            // 本形态的载荷必须自研解析：通用库会把起始相差小于 200ms 的相邻主行
            // 相互吞并成「主行 + 译文」（详见 [WordTagLrcParser]）。
            LyricFormat.KRC, LyricFormat.GENERAL, LyricFormat.AUTO ->
                if (split != null) WordTagLrcParser.parse(body) ?: parseByLibrary(body)
                else parseByLibrary(body)
        } ?: return null

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
     * <h4>为什么必须与 QRC 分开识别</h4>
     * QRC 与 YRC 的词标签形态极像，词标签**分量个数**是二者唯一可靠的区分点：
     * YRC 恒为 `(a,b,flag)` 三元组，QRC 为 `(a,b)` 二元组。
     * 二者识别错误会让整段报文进错解析器。
     *
     * <h4>关于词起始的时间基准</h4>
     * 早期文档断言"QRC 用行内相对偏移、YRC 用歌曲绝对毫秒"，**该断言与实测不符**——
     * 两种基准在两种格式里都出现过（QQ 的 QRC 实测就是绝对毫秒）。
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

        val lines = normalize(collapseSimultaneous(result.lines), maxOf(result.durationMs, request.durationMs))
            .map { it.withoutEmptySidecars() }
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
     * 解析伴随载荷为「时间戳 → 文本」
     *
     * 只接受两种结构性形态，二者可确定区分：
     * - 毫秒对形态 `[start,dur]text`（QRC 系）
     * - 分秒形态 `[mm:ss.xx]text`（LRC 系，经库解析）
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

    /** 单行时长的合理上限；超过它即视为解析得到的结束时间不可信 */
    private const val MAX_LINE_SPAN_MS = 300_000L

    /** 结束时间不可信、且曲目时长未知时的兜底行时长 */
    private const val FALLBACK_LINE_MS = 5_000L

    /** TME 报文用这个串表示"此行没有译文"，属占位符而非内容 */
    private const val NO_TRANSLATION_MARKER = "//"

    /**
     * 清掉**无意义的伴随内容**，两类：
     * - **空白** —— 部分宿主会下发只有空格的伴随行（实测波点），挂上去等于一行空译文
     * - **占位符 `//`** —— TME 报文用它表示"此行无译文"（实测 QQ 的制作信息行）
     *
     * 放在 [finalize] 出口而不是某个解析器里：译文有两条来源 —— 解析器**行内**写入
     * （QQ 的 `<Lyric_2>`、椒盐的行内译文）与伴随载荷经 [TranslationMerger] 对齐，
     * 只有公共出口能同时覆盖两者
     *
     * **只作用于伴随通道**，主行正文一律不动
     */
    private fun LyricLine.withoutEmptySidecars(): LyricLine {
        val cleanTranslation = translation?.takeIf { it.isMeaningfulSidecar() }
        val cleanPhonetic = phonetic?.takeIf { it.isMeaningfulSidecar() }
        if (cleanTranslation == translation && cleanPhonetic == phonetic) return this
        return copy(translation = cleanTranslation, phonetic = cleanPhonetic)
    }

    private fun String.isMeaningfulSidecar(): Boolean {
        val trimmed = trim()
        return trimmed.isNotEmpty() && trimmed != NO_TRANSLATION_MARKER
    }

    /** CJK 字符：汉字、平假名、片假名、谚文。 */
    private val CJK = Regex("""[㐀-䶿一-鿿぀-ゟ゠-ヿ가-힯]""")

    /**
     * 折叠**起始时间完全相同**的行组
     *
     * 部分宿主（实测椒盐）把一首曲目的三路内容以**同一时间戳重复出现**的形态给出：
     * ```
     * [00:20.699] <00:20.699>初<…>は                  ← 主歌词（带逐字标签）
     * [00:20.699]ha ji me te no ru u bu ru wa      ← 罗马音
     * [00:20.699]第一次参观卢浮宫                     ← 翻译
     * ```
     * 通行解析库只能为一条主行接纳**一条**伴随行，于是它把罗马音当成了译文，而真正的译文
     * 留在列表里**变成了独立的主歌词行** —— 界面于是会把翻译当作歌词显示
     *
     * ## 判据是否合法
     * 依据的是"与主行成对出现的行内语法"（同一时间戳重复出现），属 Translation Track Rule
     * 允许的判据。**不是**依据"缺少逐字标签"推断
     *
     * ## 分组内如何分工
     * 1. 主行 = 组内有逐字切片的那一条；都没有则取第一条
     * 2. 其余同伴：纯拉丁文且主行含 CJK 的判为**罗马音**（走 phonetic），否则判为**翻译**
     * 3. 两个通道都已占用的多余同伴**丢弃** —— 宁可少一行，也不能把伴随内容当成歌词发出去
     *
     * ## 已知边界
     * 若主行为中文、同伴为英文**译文**（而非罗马音），会被判为罗马音。二者在文本层面无法
     * 可靠区分，此处选择了对"日文歌 + 罗马音"这一更常见形态更准的一侧
     */
    private fun collapseSimultaneous(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines

        val sorted = lines.sortedBy { it.startMs }
        val result = ArrayList<LyricLine>(sorted.size)

        var index = 0
        while (index < sorted.size) {
            var end = index + 1
            while (end < sorted.size && sorted[end].startMs == sorted[index].startMs) end++

            if (end - index == 1) {
                result += sorted[index]
                index = end
                continue
            }

            val group = sorted.subList(index, end)

            // 保守护栏：组内若有多条带逐字的行，那更像"同一瞬间真的有多个演唱内容"
            // （例如对唱），不是"主行 + 伴随通道"，原样保留、不做折叠。
            if (group.count { it.hasWords } > 1) {
                result += group
                index = end
                continue
            }

            val mainIndex = group.indexOfFirst { it.hasWords }.let { if (it >= 0) it else 0 }
            var main = group[mainIndex]

            // 解析库可能已抢先把同伴行塞进了 translation。若那其实是罗马音，
            // 先把它挪回 phonetic，腾出翻译位给真正的译文——否则译文会无处安放而被丢弃。
            val owned = main.translation
            if (main.phonetic.isNullOrEmpty() && !owned.isNullOrEmpty() && isRomanization(owned, main.text)) {
                main = main.copy(translation = null, phonetic = owned)
            }

            for (i in group.indices) {
                if (i == mainIndex) continue
                val text = group[i].text.trim()
                if (text.isEmpty()) continue

                val romanization = isRomanization(text, main.text)
                if (romanization) {
                    if (main.phonetic.isNullOrEmpty()) main = main.copy(phonetic = text)
                } else {
                    if (main.translation.isNullOrEmpty()) main = main.copy(translation = text)
                }
            }

            result += main
            index = end
        }
        return result
    }

    /**
     * 判断同伴行是否为**罗马音**。
     *
     * 纯拉丁文本且主行本身含 CJK 时才成立——主行没有可被罗马化的字符时，
     * 那条同伴只可能是译文。
     */
    private fun isRomanization(candidate: String, mainText: String): Boolean {
        if (candidate.isEmpty() || CJK.containsMatchIn(candidate)) return false
        return CJK.containsMatchIn(mainText)
    }

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
     *
     * @param durationMs 曲目总时长；末行缺少可信结束时间时用它收口
     */
    private fun normalize(lines: List<LyricLine>, durationMs: Long): List<LyricLine> {
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
                .let { boundEnd(it, startMs, capByNext, durationMs) }

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

    /**
     * 收口不可信的结束时间。
     *
     * 上游库对"结束时间未知"的行会填 `Int.MAX_VALUE` 之类的哨兵值，末行尤其常见
     * （它后面没有下一行可以兜住）。若不处理，末行的区间会一直延伸到 2038 年。
     * 曲目时长已知时收到时长，否则给一个兜底行时长。
     */
    private fun boundEnd(endMs: Long, startMs: Long, capByNext: Long, durationMs: Long): Long {
        if (endMs - startMs <= MAX_LINE_SPAN_MS) return endMs

        val fallback = if (durationMs > startMs) durationMs else startMs + FALLBACK_LINE_MS
        return minOf(maxOf(startMs, fallback), capByNext)
    }
}
