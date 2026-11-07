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

import android.annotation.SuppressLint
import android.media.MediaMetadata
import android.os.Bundle
import com.hchen.hooktool.ModuleConfig
import com.hchen.hooktool.log.AndroidLog
import com.hchen.superlyric.shared.BuildConfig
import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import com.hchen.superlyric.kernel.parse.QrcParser
import com.hchen.superlyric.kernel.pipeline.LyricPipeline
import com.hchen.superlyric.kernel.pipeline.LyricRequest
import java.lang.reflect.Field
import org.json.JSONObject

/**
 * 全量诊断输出
 *
 * 解析假设（例如 QQ 的标识正则、网易云的歌词版本参数）无法凭代码判别对错，唯一可靠的
 * 办法是把原始载荷与解析结果原样打出，与预期逐条对照。
 */
object LyricDiag {

    /** 所有诊断输出的统一 tag，便于用一条 `-s` 抓全 */
    const val TAG = "SLDiag"

    /**
     * 总开关
     *
     * 判据取自 HookTool 的日志等级，不另读一遍偏好设置：宿主进程里
     * `AndroidLog.logD` 能否输出只由 [ModuleConfig.getLogLevel] 决定，跟着同一个值走
     * 才不会出现"日志等级为调试、诊断却无输出"的分歧。
     *
     * 用 getter 而非字段：等级由模块入口在加载时写入，可能晚于本对象的首次访问，
     * 一次性求值会固定为首次访问时的值。
     *
     * 诊断会打印完整歌词原文、原始报文与全部元数据，发布版不得默认开启。
     */
    val enabled: Boolean
        get() = BuildConfig.DEBUG || ModuleConfig.getLogLevel() == ModuleConfig.LOG_D

    /**
     * `MediaMetadata` 内部的原始 `Bundle`
     *
     * 仅用于 [textOf] 取原始值判类型：`MediaMetadata` 没有泛型取值器，类型化取值器
     * 会在非文本键上抛 `ClassCastException` 并在宿主进程刷屏。
     */
    @SuppressLint("SoonBlockedPrivateApi") // 只读诊断；反射失败由 runCatching 降级为 null
    private val BUNDLE_FIELD: Field? = runCatching {
        MediaMetadata::class.java
            .getDeclaredField("mBundle")
            .apply { isAccessible = true }
    }.getOrNull()

    /** 单次载荷输出的字符上限，防止畸形报文撑爆日志缓冲区 */
    @JvmField
    var maxPayloadChars: Int = 120_000

    // logcat 单条约 4KB，中文每字符 UTF-8 占 3 字节：2600 字符约 7.8KB，会被截断。
    // 取 1200 字符，最坏 3.6KB 加前缀，仍在单条上限内。
    private const val CHUNK = 1200

    /**
     * 包装 [LyricPipeline.parse]，先输出完整请求（含未解析的原始载荷）再输出解析结果
     *
     * 异常原样抛出，不改变任何控制流。
     */
    @JvmStatic
    fun parse(source: String, request: LyricRequest): LyricDoc? {
        if (!enabled) return LyricPipeline.parse(request)

        dumpRequest(source, request)

        val qrcStats = QrcParser.ParseStats()
        val doc = try {
            LyricPipeline.parse(request, qrcStats)
        } catch (t: Throwable) {
            log(source, "PARSE THREW ${t.javaClass.simpleName}: ${t.message}")
            throw t
        }

        if (qrcStats.skippedLongLines > 0 || qrcStats.skippedUnmatchedLines > 0 ||
            qrcStats.emptyWords > 0 || qrcStats.skippedXmlTags > 0 || qrcStats.truncatedLines
        ) {
            log(source, "QRC STATS $qrcStats")
        }
        dumpDocSummary(source, doc)
        return doc
    }

    /**
     * 源码级只输出摘要
     *
     * 完整正文由发布入口 [dumpDoc] 输出一次即可；两处都出全文会让两股分块流在 logcat
     * 中交错，既难读又浪费缓冲区。
     */
    @JvmStatic
    fun dumpDocSummary(source: String, doc: LyricDoc?) {
        if (!enabled) return

        if (doc == null) {
            log(source, "DOC-SUMMARY null（本层未产出有效行；不等于平台无歌词）")
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
     * 输出一次解析请求的全部输入
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
     * 输出解析产出的文档全文：曲目信息，以及每一行的主歌词、译文、音译与逐字时间
     */
    @JvmStatic
    fun dumpDoc(source: String, doc: LyricDoc?) {
        if (!enabled) return

        if (doc == null) {
            log(source, "=== DOC === null（本层未产出有效行；不等于平台无歌词）")
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

    /** 输出一段文本载荷全文（自动分块） */
    @JvmStatic
    fun dumpPayload(source: String, label: String, text: String) {
        if (!enabled) return
        dumpText(source, label, text)
    }

    /** 输出字节载荷的规模、可打印预览与十六进制头部 */
    @JvmStatic
    fun dumpBytes(source: String, label: String, bytes: ByteArray) {
        if (!enabled) return

        log(source, "$label len=${bytes.size} ${describeMagic(bytes)}")
        dumpText(source, "$label-HEX", toHex(bytes, 256))
    }

    /**
     * 输出一段尚未解密的原始字段：只给规模与前 96 字符预览
     *
     * 加密载荷常有数十 KB，全文输出会撑爆 logcat 缓冲区，而判断字段形态只需要看头部。
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
     * 输出网易云歌词接口的响应骨架：顶层键、`nolyric` 标记，以及每一路歌词通道的字符数
     *
     * 用于核实"版本参数全为 0 时服务端是否返回空壳"这类只能靠真机定论的问题。
     */
    @JvmStatic
    fun dumpNeteaseChannels(source: String, url: String, payload: JSONObject) {
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
     * 输出一条 `MediaMetadata` 的全部键与其中的文本值
     *
     * `dumpsys media_session` 只渲染 title/artist/album 三项，而宿主可能把真正的曲名
     * 放在别的键上（例如 `DISPLAY_TITLE`），同时用歌词行污染 `TITLE`。排查"曲名被
     * 宿主改写"时必须看到全量键。
     */
    @JvmStatic
    fun dumpMetadata(source: String, metadata: MediaMetadata) {
        if (!enabled) return
        // 诊断输出不得改变控制流：QQ 的 Bundle 可能带 null 键，排序会抛 NPE 并中断
        // 后续的取消停止与切歌处理
        runCatching { dumpMetadataUnchecked(source, metadata) }
            .onFailure { log(source, "META dump failed: " + it.javaClass.simpleName) }
    }

    private fun dumpMetadataUnchecked(source: String, metadata: MediaMetadata) {
        val keys = metadata.keySet().filterNotNull()
        val parts = keys.sorted().joinToString(" | ") { key ->
            val text = textOf(metadata, key)
            if (text != null) key + "=\"" + text.take(60) + "\"" else key + "=<non-text>"
        }
        log(source, "META keyCount=" + keys.size + " :: " + parts)
    }

    /**
     * 取某个键的文本值，非文本键返回 `null`
     *
     * 不能直接调 `MediaMetadata.getText(key)`：它内部是 `Bundle.getCharSequence`，
     * 撞上 `DURATION`（Long）或 `USER_RATING`（Rating）会抛 `ClassCastException`；
     * 平台虽会吞掉异常并返回 null，`BaseBundle` 仍会先以
     * "Attempt to cast generated internal exception" 打一条 W；元数据回调频率高，每次
     * 切歌都会在宿主进程刷屏。
     *
     * 因此反射取内部 `Bundle` 的原始值，先判类型再转文本；反射失败时退回类型化取值器。
     * 原始值读取没有非 deprecated 的替代口，局部抑制仅限本方法。
     */
    @Suppress("DEPRECATION")
    private fun textOf(metadata: MediaMetadata, key: String): String? {
        val bundle = BUNDLE_FIELD
            ?.let { field -> runCatching { field.get(metadata) }.getOrNull() }
                as? Bundle
        if (bundle == null) return runCatching { metadata.getText(key)?.toString() }.getOrNull()
        return (bundle.get(key) as? CharSequence)?.toString()
    }

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

    /**
     * 分块输出长文本，每块带 `[i/n]` 序号，可完整还原
     *
     * 换行会被替换为可见标记 `⏎`：logcat 会把消息里的 `\n` 拆成独立的行，只有首块
     * 带序号、后续行无法归属，更难读。
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
