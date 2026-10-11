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
package com.hchen.superlyric.utils

import com.hchen.hooktool.ModuleConfig
import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.BuildConfig
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine

/**
 * 真机联调期的**全量诊断输出**
 *
 * ## 为什么需要它
 * 解析假设（例如 QQ 的标识正则、网易云的歌词版本参数）无法凭代码判别对错，
 * 唯一可靠的办法是**把原始载荷与解析结果原样打出来**，与预期逐条对照。
 *
 * ## 为什么落在 `:app` 而不是内核
 * `:lyric-kernel` 是纯 JVM 模块，**严禁**依赖任何 `android.*` 类型，
 * 因此不能调用 `AndroidLog`。所有诊断输出集中在 `:app` 侧。
 *
 * ## 输出量控制
 * logcat 单条消息上限约 4KB，超出会被截断。因此所有长文本一律按
 * [CHUNK] 字符**分块**并带 `[i/n]` 序号输出，可完整还原。
 *
 * ## 过滤命令
 * ```
 * adb logcat -v time -s SLDiag
 * ```
 *
 * @author 焕晨HChen
 */
object LyricDiag {

    /** 所有诊断输出的统一 tag，便于一条 `-s` 抓全。 */
    const val TAG = "SLDiag"

    /**
     * 总开关
     *
     * 判据取自 HookTool 自己的日志等级，而不是重新去读一遍偏好设置：宿主进程里
     * `AndroidLog.logD` 能否打出，只由 [ModuleConfig.getLogLevel] 决定，本开关跟着
     * 同一个值走，就不可能出现"日志等级明明是调试、诊断却一行不出"的分歧。
     * 真机实测过这种分歧：宿主进程的 `[Provider][D]` 在打，`SLDiag` 一条都没有
     *
     * 之所以是 getter 而不是字段：等级由模块入口在加载时写入，可能晚于本对象的首次触碰，
     * 一次性求值会把结果钉死
     *
     * 诊断会打印完整歌词原文、原始报文与全部元数据，发布版绝不能默认开启
     */
    val enabled: Boolean
        get() = BuildConfig.DEBUG || ModuleConfig.getLogLevel() == ModuleConfig.LOG_D

    /**
     * `MediaMetadata` 内部的原始 `Bundle`。
     *
     * 仅用于 [dumpMetadata] 取原始值判类型——`MediaMetadata` 没有泛型取值器，
     * 而类型化取值器会在非文本键上抛 `ClassCastException` 并在宿主进程刷屏。
     */
    private val BUNDLE_FIELD: java.lang.reflect.Field? = runCatching {
        android.media.MediaMetadata::class.java
            .getDeclaredField("mBundle")
            .apply { isAccessible = true }
    }.getOrNull()

    /** 单次载荷输出的字符上限，防止畸形报文撑爆日志缓冲区。 */
    @JvmField
    var maxPayloadChars: Int = 120_000

    private const val CHUNK = 2600

    // ---------------------------------------------------------------- 解析包装

    /**
     * 包装 [LyricPipeline.parse]：先完整输出请求（含**未解析的原始载荷**），
     * 再输出解析结果。异常原样抛出，不改变任何控制流。
     */
    @JvmStatic
    fun parse(source: String, request: LyricRequest): LyricDoc? {
        if (!enabled) return LyricPipeline.parse(request)

        dumpRequest(source, request)

        val doc = try {
            LyricPipeline.parse(request)
        } catch (t: Throwable) {
            log(source, "PARSE THREW ${t.javaClass.simpleName}: ${t.message}")
            throw t
        }

        dumpDocSummary(source, doc)
        return doc
    }

    /**
     * 源码级只出**摘要**。
     *
     * 完整正文由发布入口（[dumpDoc] / [dumpData]）负责输出一次即可；
     * 两处都出全文会让两股分块流在 logcat 中交错，既难读又浪费缓冲区。
     */
    @JvmStatic
    fun dumpDocSummary(source: String, doc: LyricDoc?) {
        if (!enabled) return

        if (doc == null) {
            log(source, "DOC-SUMMARY null（确定性无歌词）")
            return
        }

        val head = doc.lines.take(3).joinToString(" / ") { it.text.take(24) }
        log(
            source,
            "DOC-SUMMARY lines=${doc.lines.size} span=[${doc.firstStartMs}..${doc.lastEndMs}] " +
                "words=${doc.lines.count { it.hasWords }} trans=${doc.lines.count { it.hasTranslation }} " +
                "phon=${doc.lines.count { it.hasPhonetic }} head=[$head]",
        )
    }

    /**
     * 输出一次解析请求的全部输入。
     */
    @JvmStatic
    fun dumpRequest(source: String, request: LyricRequest) {
        if (!enabled) return

        log(
            source,
            "=== REQUEST === format=${request.format} bytes=${request.bytes?.size ?: 0} " +
                "text=${request.text?.length ?: 0} trans=${request.translationText?.length ?: 0} " +
                "phon=${request.phoneticText?.length ?: 0} title=\"${request.title}\" " +
                "artist=\"${request.artist}\" album=\"${request.album}\" " +
                "duration=${request.durationMs} lyricId=${request.lyricId}",
        )

        request.bytes?.takeIf { it.isNotEmpty() }?.let { dumpBytes(source, "RAW-BYTES", it) }
        request.text?.takeIf { it.isNotEmpty() }?.let { dumpPayload(source, "RAW-TEXT", it) }
        request.translationText?.takeIf { it.isNotEmpty() }
            ?.let { dumpPayload(source, "RAW-TRANSLATION", it) }
        request.phoneticText?.takeIf { it.isNotEmpty() }
            ?.let { dumpPayload(source, "RAW-PHONETIC", it) }
    }

    /**
     * 输出解析产出的文档全文：曲目信息 + 每一行的主歌词 / 译文 / 音译 / 逐字时间。
     */
    @JvmStatic
    fun dumpDoc(source: String, doc: LyricDoc?) {
        if (!enabled) return

        if (doc == null) {
            log(source, "=== DOC === null（确定性无歌词）")
            return
        }

        log(
            source,
            "=== DOC === title=\"${doc.title}\" artist=\"${doc.artist}\" album=\"${doc.album}\" " +
                "duration=${doc.durationMs} lyricId=${doc.lyricId} lines=${doc.lines.size} " +
                "span=[${doc.firstStartMs}..${doc.lastEndMs}] " +
                "anyWords=${doc.lines.any { it.hasWords }} " +
                "anyTrans=${doc.lines.any { it.hasTranslation }} " +
                "anyPhon=${doc.lines.any { it.hasPhonetic }}",
        )

        val builder = StringBuilder(64 * doc.lines.size)
        for (index in doc.lines.indices) {
            appendLine(builder, index, doc.lines[index])
        }
        dumpText(source, "DOC-BODY", builder.toString())
    }

    /**
     * 输出一份即将跨 Binder 发布的 `SuperLyricData` 的完整内容。
     */
    @JvmStatic
    fun dumpData(source: String, data: SuperLyricData?) {
        if (!enabled) return

        if (data == null) {
            log(source, "=== PUBLISH DATA === null")
            return
        }

        val lines = data.allLyrics
        log(
            source,
            "=== PUBLISH DATA === title=\"${data.title}\" artist=\"${data.artist}\" " +
                "album=\"${data.album}\" lyricId=${data.lyricId} duration=${data.duration} " +
                "position=${data.position} index=${data.currentLyricIndex} " +
                "allLyrics=${lines?.size ?: 0} fullPayload=${data.isFullPayload} " +
                "deltaPayload=${data.isDeltaPayload}",
        )

        val builder = StringBuilder(64 * (lines?.size ?: 0))
        lines?.forEachIndexed { index, line -> appendLine(builder, index, line) }
        dumpText(source, "PUBLISH-BODY", builder.toString())
    }

    // ---------------------------------------------------------------- 原始载荷

    /** 输出一段文本载荷全文（自动分块）。 */
    @JvmStatic
    fun dumpPayload(source: String, label: String, text: String) {
        if (!enabled) return
        dumpText(source, label, text)
    }

    /** 输出字节载荷的规模、可打印预览与十六进制头部。 */
    @JvmStatic
    fun dumpBytes(source: String, label: String, bytes: ByteArray) {
        if (!enabled) return

        log(source, "$label len=${bytes.size} ${describeMagic(bytes)}")
        dumpText(source, "$label-HEX", toHex(bytes, 256))
    }

    /**
     * 输出一段**尚未解密**的原始字段：只给规模与前 96 字符预览。
     *
     * 加密载荷动辄数十 KB，全文输出会撑爆 logcat 缓冲区，而判断
     * "这个字段到底是什么形态"只需要看头部。
     */
    @JvmStatic
    fun dumpRawPreview(source: String, label: String, text: String?) {
        if (!enabled) return

        if (text.isNullOrEmpty()) {
            log(source, "$label <empty>")
            return
        }

        val preview = text.substring(0, minOf(96, text.length))
        val hexLike = text.length >= 16 && text.all { it in "0123456789abcdefABCDEF" }
        log(source, "$label len=${text.length} hexLike=$hexLike head=\"$preview\"")
    }

    /**
     * 输出网易云歌词接口的响应骨架：顶层键、`nolyric` 标记，以及每一路歌词通道的字符数。
     *
     * 专用于核实"版本参数全为 0 时服务端是否返回空壳"这类只能靠真机定论的问题。
     */
    @JvmStatic
    fun dumpNeteaseChannels(source: String, url: String, payload: org.json.JSONObject) {
        if (!enabled) return

        val keys = ArrayList<String>()
        val iterator = payload.keys()
        while (iterator.hasNext()) keys.add(iterator.next())

        val channels = NET_CHANNELS.joinToString(" ") { key ->
            val obj = payload.optJSONObject(key)
            when {
                obj == null -> "$key=absent"
                else -> "$key=${obj.optString("lyric", "").length}ch"
            }
        }
        log(
            source,
            "RESPONSE url=$url keys=$keys nolyric=${payload.optBoolean("nolyric", false)} | $channels",
        )
    }

    private val NET_CHANNELS =
        listOf("yrc", "lrc", "ytlrc", "tlyric", "yromalrc", "romalrc")

    /**
     * 输出一条 `MediaMetadata` 的**全部键**与其中的文本值。
     *
     * `dumpsys media_session` 只渲染 title/artist/album 三项，而宿主可能把真正的曲名
     * 放在别的键上（例如 `DISPLAY_TITLE`），同时用歌词行污染 `TITLE`。
     * 排查"曲名被宿主改写"这类问题时必须看到全量键。
     */
    @JvmStatic
    fun dumpMetadata(source: String, metadata: android.media.MediaMetadata) {
        if (!enabled) return

        val keys = metadata.keySet()
        val parts = keys.sorted().joinToString(" | ") { key ->
            val text = textOf(metadata, key)
            if (text != null) "$key=\"${text.take(60)}\"" else "$key=<non-text>"
        }
        log(source, "META keyCount=${keys.size} :: $parts")
    }

    /**
     * 取某个键的文本值，非文本键返回 `null`
     *
     * **不能直接调 `MediaMetadata.getText(key)`**：它内部是 `Bundle.getCharSequence`，
     * 撞上 `DURATION`（Long）或 `USER_RATING`（Rating）会抛 `ClassCastException`。
     * 异常虽被平台内部吞掉并返回 null，但 `BaseBundle` 会先以
     * "Attempt to cast generated internal exception" 打一条 W——元数据回调是高频的，
     * 于是每次切歌都会在宿主进程里刷屏。
     *
     * `MediaMetadata` **没有**泛型取值器，所以这里反射取内部 `Bundle` 拿原始值，
     * 先判类型再转文本。反射失败时退回类型化取值器（此时会有上述噪声，但功能不受影响）。
     */
    @Suppress("DEPRECATION")
    private fun textOf(metadata: android.media.MediaMetadata, key: String): String? {
        val bundle = BUNDLE_FIELD
            ?.let { field -> runCatching { field.get(metadata) }.getOrNull() }
            as? android.os.Bundle
        if (bundle == null) return runCatching { metadata.getText(key)?.toString() }.getOrNull()
        return (bundle.get(key) as? CharSequence)?.toString()
    }

    // ---------------------------------------------------------------- 内部

    private fun appendLine(builder: StringBuilder, index: Int, line: LyricLine) {
        builder.append('[')
        if (index < 10) builder.append(' ')
        if (index < 100) builder.append(' ')
        builder.append(index).append("] ")
        builder.append(ms(line.startMs)).append('-').append(ms(line.endMs))
        builder.append(" | ").append(line.text)

        line.translation?.let { builder.append("\n      T: ").append(it) }
        line.phonetic?.let { builder.append("\n      P: ").append(it) }

        if (line.words.isNotEmpty()) {
            builder.append("\n      W: ")
            for (wordIndex in line.words.indices) {
                val word = line.words[wordIndex]
                if (wordIndex > 0) builder.append(' ')
                builder.append("(\"").append(word.text).append("\"@")
                    .append(word.startMs).append('-').append(word.endMs).append(')')
            }
        }
        builder.append('\n')
    }

    private fun appendLine(builder: StringBuilder, index: Int, line: SuperLyricLine) {
        builder.append('[')
        if (index < 10) builder.append(' ')
        if (index < 100) builder.append(' ')
        builder.append(index).append("] ")
        builder.append(ms(line.startTime)).append('-').append(ms(line.endTime))
        builder.append(" | ").append(line.text)

        line.translation?.let { builder.append("\n      T: ").append(it) }
        line.secondary?.let { builder.append("\n      P: ").append(it) }

        val words = line.words
        if (words != null && words.isNotEmpty()) {
            builder.append("\n      W: ")
            for (wordIndex in words.indices) {
                val word = words[wordIndex]
                if (wordIndex > 0) builder.append(' ')
                builder.append("(\"").append(word.word).append("\"@")
                    .append(word.startTime).append('-').append(word.endTime).append(')')
            }
        }
        builder.append('\n')
    }

    /**
     * 分块输出长文本。每块带 `[i/n]` 序号，可完整还原。
     *
     * 换行会被替换为可见标记 `⏎`——logcat 会把消息里的 `\n` 拆成**独立的行**，
     * 那样只有首块带序号、后续行无法归属，反而更难读。
     */
    private fun dumpText(source: String, label: String, text: String) {
        val safe = if (text.length > maxPayloadChars) {
            text.substring(0, maxPayloadChars) + "⏎…[TRUNCATED ${text.length - maxPayloadChars} chars]"
        } else {
            text
        }.replace('\n', '⏎')

        val chunks = (safe.length + CHUNK - 1) / CHUNK
        log(source, "--- $label: ${safe.length} chars, $chunks chunk(s) ---")

        if (safe.isEmpty()) {
            log(source, "$label[0/0] <empty>")
            return
        }

        var offset = 0
        var index = 1
        while (offset < safe.length) {
            val end = minOf(offset + CHUNK, safe.length)
            AndroidLog.logI(TAG, "[$source] $label[$index/$chunks] " + safe.substring(offset, end))
            offset = end
            index++
        }
    }

    private fun log(source: String, message: String) {
        AndroidLog.logI(TAG, "[$source] $message")
    }

    private fun ms(value: Long): String {
        val clamped = if (value < 0L) 0L else value
        val totalSeconds = clamped / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = clamped % 1000
        return buildString {
            if (minutes < 10) append('0')
            append(minutes).append(':')
            if (seconds < 10) append('0')
            append(seconds).append('.')
            when {
                millis < 10 -> append("00")
                millis < 100 -> append('0')
            }
            append(millis)
        }
    }

    private fun describeMagic(bytes: ByteArray): String {
        val head = bytes.copyOfRange(0, minOf(4, bytes.size))
        val magic = head.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            val text = value.toChar()
            if (text in ' '..'~') text.toString() else "%02X".format(value)
        }
        // krc1 魔数：密文前 4 字节为 'krc1'
        val krc = bytes.size >= 4 &&
            bytes[0] == 'k'.code.toByte() &&
            bytes[1] == 'r'.code.toByte() &&
            bytes[2] == 'c'.code.toByte() &&
            bytes[3] == '1'.code.toByte()
        return "head=\"$magic\" looksLikeKrc=$krc"
    }

    private fun toHex(bytes: ByteArray, limit: Int): String {
        val count = minOf(limit, bytes.size)
        val builder = StringBuilder(count * 3 + 32)
        for (index in 0 until count) {
            if (index > 0) builder.append(' ')
            builder.append("%02X".format(bytes[index].toInt() and 0xFF))
            if ((index + 1) % 32 == 0) builder.append('\n')
        }
        if (bytes.size > limit) builder.append("\n…[${bytes.size - limit} more bytes]")
        return builder.toString()
    }
}
