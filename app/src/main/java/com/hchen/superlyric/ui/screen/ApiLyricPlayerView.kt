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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.sp
import com.hchen.superlyric.R
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * API 看板的歌词视图
 *
 * 展示曲目信息与逐行歌词，按接收到的位置推算逐字高亮，并支持手动浏览与回到当前行
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApiLyricPlayerView(
    state: ApiReceiverState,
    currentIndex: Int,
    allLyrics: List<Line>?,
    paused: Boolean = false
) {
    val lyric = state.lyric
    val scope = rememberCoroutineScope()
    var isUserBrowsing by remember { mutableStateOf(false) }
    val lyricListState = rememberLazyListState()

    // 只有用户手指拖拽会置位 isUserDragging，animateScrollToItem 不会触发
    val isUserDragging by lyricListState.interactionSource.collectIsDraggedAsState()

    // 用户开始拖拽时进入手动浏览模式
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            isUserBrowsing = true
        }
    }

    // 当前播放行是否在视口内可见
    // currentIndex 与 allLyrics 必须作为 remember 的键：无键的 remember 会捕获首帧参数，
    // 数据到达后 derivedStateOf 仍按 null/0 判断，回到当前行的按钮可能永远不出现
    val isCurrentLineVisible by remember(allLyrics, currentIndex) {
        derivedStateOf {
            if (allLyrics.isNullOrEmpty() || currentIndex !in allLyrics.indices) return@derivedStateOf true
            lyricListState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
        }
    }

    // 用户主动浏览且当前行已滑出视口时显示回到当前行的按钮
    val showBackToCurrentLine by remember(isUserBrowsing, isCurrentLineVisible) {
        derivedStateOf {
            isUserBrowsing && !isCurrentLineVisible
        }
    }

    // 位置只在动态播放流（FULL/STATE 且 PLAYING）中按墙上时钟外推；未播放、
    // 静态拉取（PULL_LATEST）、停止或暂停时固定在 basePos
    // playPos 必须是稳定对象：若 remember 的键包含 state.position，每个位置包都会
    // 产生新的 MutableLongState，派生状态仍捕获旧对象，逐字高亮会停在首次组合的位置
    val playPos = remember { mutableLongStateOf(state.position) }
    var currentPlayPos by playPos
    // speed <= 0 或非有限值表示冻结或未知，不能按 1 倍速外推
    val playbackSpeed = state.speed.takeIf { it.isFinite() && it > 0f } ?: 0f
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.position, state.positionTimestamp, state.speed, paused, state.eventType, state.source, lifecycleOwner) {
        // 只在 RESUMED 时运行：后台或锁屏时停止 20ms 轮询，回前台由新的位置包重新锚定
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val basePos = state.position
            val baseWall = state.positionTimestamp
            // null 表示播放状态未知（LegacyV37 没有播放状态通道），不能据此冻结；
            // 只有显式的 PAUSED / STOPPED / ERROR / BUFFERING 才停止外推
            val stateAllowsPlayback = state.playbackState == null ||
                    state.playbackState == PlaybackState.PLAYING
            val isStreamingPlaying = !paused &&
                    (state.eventType == "FULL" || state.eventType == "STATE") &&
                    state.source != "MockDebugPublisher" &&
                    lyric != null && stateAllowsPlayback &&
                    playbackSpeed > 0f

            if (!isStreamingPlaying) {
                playPos.longValue = basePos
                return@repeatOnLifecycle
            }

            val currentLine = if (!allLyrics.isNullOrEmpty() && currentIndex in allLyrics.indices) {
                allLyrics[currentIndex]
            } else {
                lyric.lines.getOrNull(currentIndex)
            }
            val lineEnd = currentLine?.end?.takeIf { it > basePos }
                ?: (lyric.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)

            while (true) {
                val elapsed = (SystemClock.elapsedRealtime() - baseWall).coerceAtLeast(0L)
                val calculated = basePos + (elapsed.toDouble() * playbackSpeed).toLong()
                if (calculated >= lineEnd) {
                    playPos.longValue = lineEnd
                    break
                }
                playPos.longValue = calculated
                // 循环周期只决定刷新密度：位置按墙上时钟计算，与循环频率无关；
                // 20ms（50Hz）高于逐字高亮需要的密度，且低于每帧刷新的开销
                delay(20)
            }
        }
    }

    // 消费歌词列表的全部未消费滚动量，避免滚动传递给外部面板
    val lyricNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                return available
            }
        }
    }

    // 歌曲切换（lyric.id 变化）时重置浏览状态并回到当前行
    LaunchedEffect(lyric?.id) {
        isUserBrowsing = false
        if (!allLyrics.isNullOrEmpty() && currentIndex in allLyrics.indices) {
            lyricListState.scrollToItem(currentIndex)
        }
    }

    // 行切换且用户未拖拽时退出手动浏览模式，并平滑滚动回当前行
    LaunchedEffect(currentIndex) {
        if (allLyrics.isNullOrEmpty() || currentIndex !in allLyrics.indices) return@LaunchedEffect
        if (isUserDragging) return@LaunchedEffect
        isUserBrowsing = false
        lyricListState.animateScrollToItem(currentIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 曲目信息与发布源纵向排列，长标题和长包名互不挤压
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = lyric?.title?.takeIf { it.isNotEmpty() } ?: stringResource(
                    if (state.eventType == "STOP" && state.playbackState == null)
                        R.string.unknown_title else R.string.api_not_playing
                ),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val album = lyric?.album
            Text(
                text = "${lyric?.artist?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.unknown_artist)}${
                    if (!album.isNullOrEmpty()) " · $album" else ""
                }",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))

            // 发布源标识独立成行，超长包名只截断自身
            Box(
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorScheme.tertiaryContainer)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = state.source ?: "SuperLyric API",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!allLyrics.isNullOrEmpty()) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val totalHeight = maxHeight
                // 当前行锚定在视口上方约 32% 处
                val comfortableTopPadding = (totalHeight * 0.32f).coerceAtLeast(60.dp)
                // 底部预留 55% 滚动空间，末尾歌词也能滚到锚点位置
                val comfortableBottomPadding = (totalHeight * 0.55f).coerceAtLeast(120.dp)

                LazyColumn(
                    state = lyricListState,
                    userScrollEnabled = true,
                    contentPadding = PaddingValues(
                        top = comfortableTopPadding,
                        bottom = comfortableBottomPadding
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(lyricNestedScroll)
                        .overScrollVertical(),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    itemsIndexed(
                        items = allLyrics,
                        key = { index, line -> "${line.start}_$index" }
                    ) { index, line ->
                        val isActive = (index == currentIndex)
                        val scale by animateFloatAsState(
                            targetValue = if (isActive) 1.0f else 0.96f,
                            animationSpec = tween(durationMillis = 280),
                            label = "lyric_scale"
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                }
                        ) {
                            // 整句起止时间与持续时长（ms）
                            val lineDuration = (line.end - line.start).coerceAtLeast(0L)
                            val timingText = if (line.start > 0 || line.end > 0) {
                                "${line.start}ms ~ ${line.end}ms (${lineDuration}ms)"
                            } else {
                                "0ms ~ 0ms"
                            }

                            Text(
                                text = timingText,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal,
                                color = if (isActive) colorScheme.primary.copy(alpha = 0.75f) else colorScheme.onSurface.copy(alpha = 0.28f),
                                modifier = Modifier.padding(bottom = 3.dp)
                            )

                            // 歌词内容与逐字排版
                            val words = line.words
                            if (isActive && !words.isNullOrEmpty()) {
                                // 当前行的活跃字词索引：同一时刻最多一个词处于演唱状态。
                                // 读取稳定的 playPos，而不是被 remember 键替换的 delegate；
                                // derivedStateOf 只在被读取时按当前位置求值
                                val activeWordIndex by remember(words) {
                                    derivedStateOf {
                                        val pos = playPos.longValue
                                        words.indexOfFirst { pos in it.start until it.end }
                                    }
                                }

                                // 正文逐字排版，只保留 2dp 字间距
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    words.forEachIndexed { wIdx, word ->
                                        val isWordSinging = (wIdx == activeWordIndex)
                                        val isWordSung = when {
                                            activeWordIndex >= 0 -> wIdx < activeWordIndex
                                            else -> currentPlayPos >= word.end
                                        }

                                        val wordColor = when {
                                            isWordSinging -> colorScheme.primary
                                            isWordSung -> colorScheme.primary.copy(alpha = 0.92f)
                                            else -> colorScheme.onSurface.copy(alpha = 0.42f)
                                        }

                                        Text(
                                            text = word.text,
                                            fontSize = 24.sp,
                                            fontWeight = if (isWordSinging || isWordSung) FontWeight.Bold else FontWeight.Medium,
                                            color = wordColor,
                                            lineHeight = 32.sp
                                        )
                                    }
                                }

                                // 逐字时序条：列出当前句每个词的持续时长（ms），与正文排版分离
                                Spacer(Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    words.forEachIndexed { wIdx, word ->
                                        val wStart = word.start
                                        val wEnd = word.end
                                        val wDuration = (wEnd - wStart).coerceAtLeast(0L)
                                        val isWordSinging = (wIdx == activeWordIndex)
                                        val isWordSung = when {
                                            activeWordIndex >= 0 -> wIdx < activeWordIndex
                                            else -> currentPlayPos >= wEnd
                                        }

                                        val stripBgColor = when {
                                            isWordSinging -> colorScheme.primaryContainer
                                            isWordSung -> colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                            else -> Color.Transparent
                                        }

                                        val stripTextColor = when {
                                            isWordSinging -> colorScheme.onPrimaryContainer
                                            isWordSung -> colorScheme.onSecondaryContainer
                                            else -> colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(stripBgColor)
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = word.text.trim(),
                                                fontSize = 11.sp,
                                                fontWeight = if (isWordSinging) FontWeight.Bold else FontWeight.Normal,
                                                color = stripTextColor
                                            )
                                            Text(
                                                text = "${wDuration}ms",
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                color = stripTextColor.copy(alpha = 0.85f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 非当前行，或当前行没有逐字数据
                                Text(
                                    text = line.text,
                                    fontSize = if (isActive) 24.sp else 17.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isActive) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.38f),
                                    textAlign = TextAlign.Start,
                                    lineHeight = if (isActive) 32.sp else 24.sp
                                )
                            }

                            // 翻译
                            val trans = line.translation
                            if (!trans.isNullOrEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = trans,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.primary.copy(alpha = 0.85f),
                                    textAlign = TextAlign.Start
                                )
                            }

                            // 音译
                            val sec = line.phonetic
                            if (!sec.isNullOrEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = sec,
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }

                // 用户浏览到别处时显示回到当前行的入口。
                // 必须用全限定名：导入的 AnimatedVisibility 会解析为 ColumnScope 扩展。
                androidx.compose.animation.AnimatedVisibility(
                    visible = showBackToCurrentLine,
                    enter = fadeIn(tween(200)) + scaleIn(tween(200)),
                    exit = fadeOut(tween(200)) + scaleOut(tween(200)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                isUserBrowsing = false
                                scope.launch {
                                    if (currentIndex in allLyrics.indices) {
                                        lyricListState.animateScrollToItem(currentIndex)
                                    }
                                }
                            },
                        colors = CardDefaults.defaultColors(
                            color = colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Music,
                                contentDescription = null,
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.api_back_to_current_line),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        } else {
            // 无歌词时的空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Music,
                            contentDescription = "Music",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.api_empty_lyrics_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.api_empty_lyrics_subtitle),
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
