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
package com.hchen.superlyric.kernel.merge

import com.hchen.superlyric.kernel.model.LyricDoc
import com.hchen.superlyric.kernel.model.LyricLine
import kotlin.math.abs

/**
 * 伴随通道：译文与音译走**两条独立通道**，互不覆盖
 */
enum class SidecarChannel {
    /** 译文，落位到 [LyricLine.translation] */
    TRANSLATION,

    /** 罗马音 / 音译，落位到 [LyricLine.phonetic] */
    PHONETIC,
}

/**
 * 伴随载荷中的一条「时间戳 → 文本」记录
 */
data class TimeText(val startMs: Long, val text: String)

/**
 * 翻译 / 音译行的**唯一**对齐机制
 *
 * ## 对齐规则
 * 1. **就近匹配**：在 [TOLERANCE_MS] 窗口内取时间戳最接近的主行，不要求精确相等
 * 2. **一对一消费**：每条伴随记录至多被消费一次，杜绝同一译文重复挂载
 * 3. **不覆盖行内译文**：主行若已由解析器在行内给出该通道的内容，则跳过
 * 4. **未匹配即丢弃**：落空的伴随记录通常是乐器标记或占位符，绝**不**提升为独立歌词行
 *
 * 规则 4 与"严禁把译文输出为主行"共同构成 Translation Track Rule
 *
 * @author 焕晨HChen
 */
object TranslationMerger {

    /**
     * 对齐容差（毫秒）
     *
     * 150ms 足以覆盖平台侧两段载荷的时间戳抖动，又远小于正常的行间隔，不会跨行错位
     */
    const val TOLERANCE_MS = 150L

    /**
     * 将伴随载荷挂载到主歌词文档上
     *
     * @param doc     主歌词文档；为空时原样返回
     * @param sidecar 伴随载荷；为空时原样返回
     * @param channel 目标通道
     * @return 挂载后的新文档（[LyricDoc] 不可变，返回的是新实例）
     */
    fun attach(doc: LyricDoc, sidecar: List<TimeText>, channel: SidecarChannel): LyricDoc {
        if (doc.isEmpty || sidecar.isEmpty()) return doc

        val lines = doc.lines.toMutableList()
        var changed = false

        // 每条伴随记录只匹配一次，天然满足"一对一消费"
        for (entry in sidecar) {
            if (entry.text.isEmpty()) continue

            var bestLineIndex = -1
            var bestDelta = Long.MAX_VALUE

            for (lineIndex in lines.indices) {
                val line = lines[lineIndex]
                if (!needsChannel(line, channel)) continue

                val delta = abs(line.startMs - entry.startMs)
                // 负数只可能来自溢出：abs(Long.MIN_VALUE) 仍是负数。若不拦住，
                // 这条伴随记录既躲过容差判定、又必然成为"最近"的那条，被错挂上去
                if (delta < 0L || delta > TOLERANCE_MS) continue
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestLineIndex = lineIndex
                }
            }

            if (bestLineIndex < 0) continue

            val line = lines[bestLineIndex]
            lines[bestLineIndex] = when (channel) {
                SidecarChannel.TRANSLATION -> line.copy(translation = entry.text)
                SidecarChannel.PHONETIC -> line.copy(phonetic = entry.text)
            }
            changed = true
        }

        return if (changed) doc.copy(lines = lines) else doc
    }

    private fun needsChannel(line: LyricLine, channel: SidecarChannel): Boolean = when (channel) {
        SidecarChannel.TRANSLATION -> !line.hasTranslation
        SidecarChannel.PHONETIC -> !line.hasPhonetic
    }
}
