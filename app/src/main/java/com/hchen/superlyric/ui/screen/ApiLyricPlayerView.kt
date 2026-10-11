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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
import com.hchen.superlyric.R
import com.hchen.superlyricapi.SuperLyricLine
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
 * 沉浸式开源音乐播放器风格的歌词呈现组件
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApiLyricPlayerView(
    state: ApiReceiverState,
    currentIndex: Int,
    allLyrics: Array<SuperLyricLine>?,
    paused: Boolean = false
) {
    val data = state.data
    val scope = rememberCoroutineScope()
    var isUserBrowsing by remember { mutableStateOf(false) }
    val lyricListState = rememberLazyListState()

    // 严密捕获用户真实物理手指拖拽动作（程序 animateScrollToItem 滚动不会触发 isUserDragging）
    val isUserDragging by lyricListState.interactionSource.collectIsDraggedAsState()

    // 仅当用户手指真实在屏幕上拖拽时才进入手动浏览模式
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            isUserBrowsing = true
        }
    }

    // 判断当前正在播放的行是否在当前视口内可见
    val isCurrentLineVisible by remember {
        derivedStateOf {
            if (allLyrics.isNullOrEmpty() || currentIndex !in allLyrics.indices) return@derivedStateOf true
            lyricListState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
        }
    }

    // 严密的悬浮按钮显示条件：仅在用户主动浏览且当前播放行已滑出可视区域时才显示
    val showBackToCurrentLine by remember {
        derivedStateOf {
            isUserBrowsing && !isCurrentLineVisible
        }
    }

    // 真实毫秒时钟驱动：仅在真实处于动态播放流（ON_LYRIC 且非静态拉取）时才进行流逝插值计算；
    // 未播放、静态拉取（PULL_LATEST）、停止或暂停时保持静态固定在 basePos，绝不盲目空转或重置从头跑
    var currentPlayPos by remember(data?.position) { mutableLongStateOf(data?.position ?: 0L) }
    LaunchedEffect(data?.position, state.positionTimestamp, paused, state.eventType, state.publisher) {
        val basePos = data?.position ?: 0L
        val baseWall = state.positionTimestamp
        val isStreamingPlaying = !paused &&
                state.eventType == "ON_LYRIC" &&
                state.publisher != "MockDebugPublisher" &&
                data != null

        if (!isStreamingPlaying) {
            currentPlayPos = basePos
            return@LaunchedEffect
        }

        val currentLine = if (!allLyrics.isNullOrEmpty() && currentIndex in allLyrics.indices) {
            allLyrics[currentIndex]
        } else {
            data.lyric
        }
        val lineEnd = currentLine?.endTime?.takeIf { it > basePos } ?: Long.MAX_VALUE

        while (true) {
            val elapsed = (SystemClock.elapsedRealtime() - baseWall).coerceAtLeast(0L)
            val calculated = basePos + elapsed
            if (calculated >= lineEnd) {
                currentPlayPos = lineEnd
                break
            }
            currentPlayPos = calculated
            // 这个节拍只决定**刷新密度**，不影响正确性——位置按墙上时钟算，与循环频率无关。
            // 20ms ≈ 50Hz：比逐字高亮需要的密度高，又明显低于"每帧一次"的代价
            delay(20)
        }
    }

    // 拦截歌词列表的嵌套滚动，完全消费未消费的滚动量，彻底杜绝外溢影响到外部面板
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

    // 当歌曲发生切换（标题改变）时立即重置浏览状态并复位到当前行
    LaunchedEffect(data?.title) {
        isUserBrowsing = false
        if (!allLyrics.isNullOrEmpty() && currentIndex in allLyrics.indices) {
            lyricListState.scrollToItem(currentIndex)
        }
    }

    // 自动居中对齐到舒适视线区：
    // 当行切换（currentIndex 发生变化）且用户当前未按住屏幕拖拽时，自动退出手动浏览模式并平滑滚动回当前行
    LaunchedEffect(currentIndex) {
        if (allLyrics.isNullOrEmpty() || currentIndex !in allLyrics.indices) return@LaunchedEffect
        if (isUserDragging) return@LaunchedEffect
        isUserBrowsing = false
        lyricListState.animateScrollToItem(currentIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部曲目信息与发布者徽章
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data?.title?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.api_not_playing),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val album = data?.album
                Text(
                    text = "${data?.artist?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.unknown_artist)}${
                        if (!album.isNullOrEmpty()) " · $album" else ""
                    }",
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(12.dp))

            // 发布源标识胶囊
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorScheme.tertiaryContainer)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = state.publisher ?: "SuperLyric API",
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
                // 舒适视线区设定：当前歌词锚定在视口上部约 32% 黄金舒适区
                val comfortableTopPadding = (totalHeight * 0.32f).coerceAtLeast(60.dp)
                // 底部预留 55% 滚动留白，允许超额滚动，彻底杜绝歌曲末尾歌词被卡死在最底部的问题
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
                        key = { index, line -> "${line.startTime}_$index" }
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
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                }
                        ) {
                            // 1. 整句起止时间 ms 与持续时间标
                            val lineDuration = (line.endTime - line.startTime).coerceAtLeast(0L)
                            val timingText = if (line.startTime > 0 || line.endTime > 0) {
                                "${line.startTime}ms ~ ${line.endTime}ms (${lineDuration}ms)"
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

                            // 2. 歌词内容展现与逐字排版
                            val words = line.words
                            if (isActive && !words.isNullOrEmpty()) {
                                // 正在演唱的当前行：
                                // 计算当前句内唯一活跃字词索引：严格互斥，同一时刻最多仅有一个字词处于 singing 激活状态
                                val activeWordIndex = remember(words, currentPlayPos) {
                                    words.indexOfFirst { currentPlayPos in it.startTime until it.endTime }
                                }

                                // A. 正文流式排版（紧凑自然字间距，绝不拉宽/撑大任何英文字母或汉字）
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    words.forEachIndexed { wIdx, word ->
                                        val isWordSinging = (wIdx == activeWordIndex)
                                        val isWordSung = when {
                                            activeWordIndex >= 0 -> wIdx < activeWordIndex
                                            else -> currentPlayPos >= word.endTime
                                        }

                                        val wordColor = when {
                                            isWordSinging -> colorScheme.primary
                                            isWordSung -> colorScheme.primary.copy(alpha = 0.92f)
                                            else -> colorScheme.onSurface.copy(alpha = 0.42f)
                                        }

                                        Text(
                                            text = word.word,
                                            fontSize = 24.sp,
                                            fontWeight = if (isWordSinging || isWordSung) FontWeight.Bold else FontWeight.Medium,
                                            color = wordColor,
                                            lineHeight = 32.sp
                                        )
                                    }
                                }

                                // B. 逐字时序微检尺（Active Word Timing Strip）
                                // 独立陈列当前句每个词的时序切片与持续毫秒，既纯粹直观，又不破坏歌词排版美感
                                Spacer(Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    words.forEachIndexed { wIdx, word ->
                                        val wStart = word.startTime
                                        val wEnd = word.endTime
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
                                                text = word.word.trim(),
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
                                // 未激活上下文行（保持干净自然的整句排版）或无逐字数据的整句
                                Text(
                                    text = line.text,
                                    fontSize = if (isActive) 24.sp else 17.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isActive) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.38f),
                                    textAlign = TextAlign.Start,
                                    lineHeight = if (isActive) 32.sp else 24.sp
                                )
                            }

                            // 3. 翻译歌词
                            val trans = if (isActive && data != null && data.hasTranslation()) data.translation?.text else line.translation
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

                            // 4. 音译 / 副歌词
                            val sec = if (isActive && data != null && data.hasSecondary()) data.secondary?.text else line.secondary
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

                // 用户手动浏览离开当前行时，展示“回到播放行”悬浮小胶囊
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
            // 待机空状态占位
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
