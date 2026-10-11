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
package com.hchen.superlyric.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.effect.cardBlur
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.util.Locale

/**
 * 底部看板的状态药丸（静止时露在屏幕最底端）
 */
@Composable
internal fun ApiPanelPeekHeader(
    state: ApiReceiverState,
    paused: Boolean,
    backdrop: LayerBackdrop?,
    cardBlend: List<BlendColorEntry>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .cardBlur(backdrop = backdrop, cardBlend = cardBlend),
        colors = CardDefaults.defaultColors(
            color = if (backdrop != null) Color.Transparent else colorScheme.surfaceContainer
        ),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 14.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.api_panel_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSecondaryContainer
                )

                val (statusText, statusBg, statusFg) = when {
                    paused -> Triple(stringResource(R.string.api_status_paused), colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer)
                    state.eventType == "ON_STOP" -> Triple(stringResource(R.string.api_status_stop), Color(0xFFE53935).copy(alpha = 0.2f), Color(0xFFD32F2F))
                    state.eventType == "ON_LYRIC" -> Triple(stringResource(R.string.api_status_streaming), colorScheme.primaryContainer, colorScheme.onPrimaryContainer)
                    else -> Triple(stringResource(R.string.api_status_standby), colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "● $statusText",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusFg
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.api_source_format, state.publisher ?: stringResource(R.string.api_source_none)),
                    fontSize = 12.sp,
                    color = colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.api_packet_seq_format, state.packetSeq, state.fullCount, state.progressCount),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackgroundVariant
                )
            }
        }
    }
}

/**
 * 监控统计详情卡片
 */
@Composable
internal fun ApiMonitorSummaryCard(
    state: ApiReceiverState,
    paused: Boolean,
    backdrop: LayerBackdrop?,
    cardBlend: List<BlendColorEntry>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .cardBlur(backdrop = backdrop, cardBlend = cardBlend),
        colors = CardDefaults.defaultColors(
            color = if (backdrop != null) Color.Transparent else colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.api_monitor_title),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.api_system_service_status), fontSize = 13.sp, color = colorScheme.onSecondaryContainer)
                Text(
                    text = if (SuperLyricHelper.isAvailable()) stringResource(R.string.api_status_available_version, SuperLyricHelper.getApiVersion()) else stringResource(R.string.api_status_unavailable),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (SuperLyricHelper.isAvailable()) colorScheme.primary else Color.Red
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.api_latest_event_type), fontSize = 13.sp, color = colorScheme.onSecondaryContainer)
                Text(
                    text = state.eventType ?: stringResource(R.string.api_none),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackgroundVariant
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.api_update_timestamp), fontSize = 13.sp, color = colorScheme.onSecondaryContainer)
                Text(
                    text = state.receiveTime ?: "--:--:--.---",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackgroundVariant
                )
            }
        }
    }
}

/**
 * 媒体元数据与进度条卡片
 */
@Composable
internal fun ApiMediaMetadataCard(
    data: SuperLyricData?,
    backdrop: LayerBackdrop?,
    cardBlend: List<BlendColorEntry>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .cardBlur(backdrop = backdrop, cardBlend = cardBlend),
        colors = CardDefaults.defaultColors(
            color = if (backdrop != null) Color.Transparent else colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.api_current_metadata),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))

            Text(
                text = data?.title?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.unknown_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${data?.artist?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.unknown_artist)} · ${data?.album?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.unknown_album)}",
                fontSize = 13.sp,
                color = colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(10.dp))

            val pos = data?.position ?: -1L
            val dur = data?.duration ?: -1L
            val progressRatio = if (dur > 0 && pos >= 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimeMs(pos),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackgroundVariant
                )
                Text(
                    text = "${(progressRatio * 100).toInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.primary
                )
                Text(
                    text = formatTimeMs(dur),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackgroundVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colorScheme.tertiaryContainer)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progressRatio)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colorScheme.primary)
                )
            }

            if (data != null && data.hasLyricId()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "LyricId: ${data.lyricId}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackgroundVariant
                )
            }
        }
    }
}

/**
 * 实时单句歌词与逐字拆解卡片
 */
@Composable
internal fun ApiCurrentLineCard(
    data: SuperLyricData?,
    currentIndex: Int,
    backdrop: LayerBackdrop?,
    cardBlend: List<BlendColorEntry>
) {
    val currentLine = data?.lyric

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .cardBlur(backdrop = backdrop, cardBlend = cardBlend),
        colors = CardDefaults.defaultColors(
            color = if (backdrop != null) Color.Transparent else colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.api_current_line_detail, if (currentIndex >= 0) "#$currentIndex" else "--"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )

                if (currentLine != null) {
                    Text(
                        text = "${formatTimeMs(currentLine.startTime)} ~ ${formatTimeMs(currentLine.endTime)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onBackgroundVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val lineText = currentLine?.text ?: stringResource(R.string.api_no_main_lyric)
            Text(
                text = lineText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSecondaryContainer
            )

            val transText = if (data?.hasTranslation() == true) data.translation?.text else null
            if (!transText.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(colorScheme.tertiaryContainer)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(text = stringResource(R.string.api_tag_trans), fontSize = 10.sp, color = colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = transText,
                        fontSize = 13.sp,
                        color = colorScheme.onBackgroundVariant
                    )
                }
            }

            val secText = if (data?.hasSecondary() == true) data.secondary?.text else null
            if (!secText.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(colorScheme.tertiaryContainer)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(text = stringResource(R.string.api_tag_secondary), fontSize = 10.sp, color = colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = secText,
                        fontSize = 13.sp,
                        color = colorScheme.onBackgroundVariant
                    )
                }
            }

            val words = currentLine?.words
            if (!words.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.api_words_breakdown, words.size),
                    fontSize = 11.sp,
                    color = colorScheme.onBackgroundVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    words.forEach { word ->
                        val durationMs = (word.endTime - word.startTime).coerceAtLeast(0)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colorScheme.tertiaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = word.word,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "${durationMs}ms",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onBackgroundVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 全量歌词概览与上下文卡片
 */
@Composable
internal fun ApiAllLyricsOverviewCard(
    allLyrics: Array<SuperLyricLine>,
    currentIndex: Int,
    backdrop: LayerBackdrop?,
    cardBlend: List<BlendColorEntry>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .cardBlur(backdrop = backdrop, cardBlend = cardBlend),
        colors = CardDefaults.defaultColors(
            color = if (backdrop != null) Color.Transparent else colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.api_all_lyrics_context),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.api_total_lines, allLyrics.size),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackgroundVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            if (currentIndex > 0 && currentIndex - 1 < allLyrics.size) {
                val prev = allLyrics[currentIndex - 1]
                Text(
                    text = "[#${currentIndex - 1}] ${prev.text}",
                    fontSize = 12.sp,
                    color = colorScheme.onBackgroundVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
            }

            if (currentIndex in allLyrics.indices) {
                val cur = allLyrics[currentIndex]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "▶ [#$currentIndex] ${cur.text}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            if (currentIndex >= 0 && currentIndex + 1 < allLyrics.size) {
                val next = allLyrics[currentIndex + 1]
                Text(
                    text = "[#${currentIndex + 1}] ${next.text}",
                    fontSize = 12.sp,
                    color = colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


internal fun formatTimeMs(ms: Long): String {
    if (ms < 0) return "00:00.000"
    val minutes = (ms / 1000) / 60
    val seconds = (ms / 1000) % 60
    val millis = ms % 1000
    return String.format(Locale.getDefault(), "%02d:%02d.%03d", minutes, seconds, millis)
}
