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

import android.annotation.SuppressLint
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hchen.superlyric.ui.data.LocalPagerState
import com.hchen.superlyric.ui.data.UIConstants
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.effect.BgEffectBackground
import com.hchen.superlyric.ui.effect.BgEffectTheme
import com.hchen.superlyric.ui.effect.BlurredBar
import com.hchen.superlyric.ui.effect.blend.ColorBlendToken
import com.hchen.superlyric.ui.effect.cardBlur
import com.hchen.superlyric.ui.effect.rememberBlurBackdrop
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricCache
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun ApiLayout(
    paddingValues: PaddingValues,
    isWideScreen: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    val pagerState = LocalPagerState.current

    // 监听应用切前后台与页面切换，切入后台时注销避免 IPC 事务投递至被冻结进程，切回前台时自动重新注册（若无数据则拉取最新歌词）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    ApiReceiverManager.ensureRegistered()
                }
                Lifecycle.Event.ON_STOP -> {
                    ApiReceiverManager.unregister()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ApiReceiverManager.unregister()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == UIConstants.API_PAGE_INDEX) {
            ApiReceiverManager.ensureRegistered()
        }
    }

    val state by ApiReceiverManager.receiverFlow.collectAsState()
    val paused by ApiReceiverManager.pausedFlow.collectAsState()

    var isDebugSheetShowing by remember { mutableStateOf(false) }

    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()

    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "panelSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val backdrop = rememberBlurBackdrop()
    val barBackdrop = rememberBlurBackdrop()

    // 当卡片滑动接近顶栏时及时激活折叠状态与毛玻璃模糊
    val collapsed by remember { derivedStateOf { scrollProgress >= 0.85f } }
    val blurActive by remember(barBackdrop) { derivedStateOf { barBackdrop != null && scrollProgress >= 0.85f } }

    val barColor = if (blurActive) {
        Color.Transparent
    } else {
        if (collapsed) colorScheme.surface else Color.Transparent
    }

    val titleColor = colorScheme.onSurface.copy(
        alpha = ((scrollProgress - 0.35f) / 0.5f).coerceIn(0f, 1f)
    )

    val isInDark = isSystemInDarkTheme()
    val cardBlend = if (isInDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light

    val density = LocalDensity.current
    val viewportHeightDp by remember {
        derivedStateOf {
            with(density) { lazyListState.layoutInfo.viewportSize.height.toDp() }
        }
    }

    val data = state.data
    val allLyrics = state.allLyricsCache ?: data?.allLyrics
    val currentIndex = data?.currentLyricIndex ?: -1

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            BlurredBar(backdrop = barBackdrop, blurEnabled = blurActive) {
                SmallTopAppBar(
                    title = stringResource(R.string.api),
                    scrollBehavior = scrollBehavior,
                    color = barColor,
                    titleColor = titleColor,
                    defaultWindowInsetsPadding = false,
                    actions = {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                isDebugSheetShowing = true
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Tune,
                                contentDescription = stringResource(R.string.api_debug),
                                tint = colorScheme.onSurface
                            )
                        }
                    }
                )
            }
        }
    ) { pv ->
        val topBarPadding = pv.calculateTopPadding()
        // 底部实时看板静止时的吸底预留高度：给上方歌词区域留出充足视口，下方露出看板把手与状态概览
        val spacerHeightDp by remember(viewportHeightDp, topBarPadding) {
            derivedStateOf {
                val total = viewportHeightDp - paddingValues.calculateBottomPadding()
                (total - topBarPadding - 110.dp).coerceAtLeast(100.dp)
            }
        }
        val isPanelCollapsed by remember {
            derivedStateOf { scrollProgress <= 0.01f }
        }

        Box(modifier = if (barBackdrop != null) Modifier.layerBackdrop(barBackdrop) else Modifier) {
            BgEffectBackground(
                dynamicBackground = true,
                isOs3Effect = true,
                effectTheme = BgEffectTheme.SUNSET,
                isFullSize = false,
                modifier = Modifier.fillMaxSize(),
                bgModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
                alpha = { 1f - scrollProgress }
            ) {
                // 上层：沉浸式开源播放器风格歌词展示区
                // 1. 消除顶部多余 padding：直接对齐 topBarPadding
                // 2. 边界精确隔离：当面板收起时置于顶层接收歌词滑动；滑动底部卡片把手时才滚动卡片
                // 3. 上拉渐隐淡出：通过 graphicsLayer 的 alpha 顺滑渐隐，上拉时完全透明，且底层组件不被频繁卸载/重挂载（彻底避免上拉导致逐字进度被重置）
                val lyricAlpha = (1f - scrollProgress * 1.8f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topBarPadding)
                        .height(spacerHeightDp)
                        .padding(horizontal = 24.dp)
                        .zIndex(if (isPanelCollapsed) 1f else 0f)
                        .graphicsLayer {
                            alpha = lyricAlpha
                            scaleX = 1f - (scrollProgress * 0.05f)
                            scaleY = 1f - (scrollProgress * 0.05f)
                        }
                ) {
                    ApiLyricPlayerView(
                        state = state,
                        currentIndex = currentIndex,
                        allLyrics = allLyrics,
                        paused = paused
                    )
                }

                // 下层：滑动展开的实时数据展示面板
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(
                        top = topBarPadding,
                        bottom = paddingValues.calculateBottomPadding() + 24.dp
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical(),
                    overscrollEffect = null
                ) {
                    item(key = "panelSpacer") {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(spacerHeightDp)
                        )
                    }

                    // 统一放置在具备最小视口高度的 Column 中：
                    // 确保 panelSpacer 能完全滚出视口使得 scrollProgress 达到 1f 触发 topbar 模糊，
                    // 同时使用 contentPadding.bottom 保证滑到顶时最后一项不会被底栏遮挡；
                    // 上滑时渐显卡片容器背景（带顶部圆角），彻底封死卡片间隙与边缘可能漏出的任何底层像素。
                    item(key = "panelContent") {
                        val panelBgAlpha = ((scrollProgress - 0.04f) / 0.20f).coerceIn(0f, 1f)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = viewportHeightDp - topBarPadding)
                                .background(
                                    if (panelBgAlpha > 0f) colorScheme.surface.copy(alpha = panelBgAlpha * 0.95f) else Color.Transparent,
                                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                                )
                                .padding(top = if (panelBgAlpha > 0f) 4.dp else 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Spacer(Modifier.height(10.dp))
                                // 抓手条
                                Box(
                                    modifier = Modifier
                                        .width(36.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f))
                                )
                                Spacer(Modifier.height(10.dp))
                            }

                            ApiPanelPeekHeader(
                                state = state,
                                paused = paused,
                                backdrop = backdrop,
                                cardBlend = cardBlend,
                                onClick = {
                                    scope.launch {
                                        if (lazyListState.firstVisibleItemIndex == 0) {
                                            lazyListState.animateScrollToItem(1)
                                        } else {
                                            lazyListState.animateScrollToItem(0)
                                        }
                                    }
                                }
                            )

                            Spacer(Modifier.height(12.dp))

                            ApiMonitorSummaryCard(
                                state = state,
                                paused = paused,
                                backdrop = backdrop,
                                cardBlend = cardBlend
                            )

                            Spacer(Modifier.height(12.dp))

                            ApiMediaMetadataCard(
                                data = data,
                                backdrop = backdrop,
                                cardBlend = cardBlend
                            )

                            Spacer(Modifier.height(12.dp))

                            ApiCurrentLineCard(
                                data = data,
                                currentIndex = currentIndex,
                                backdrop = backdrop,
                                cardBlend = cardBlend
                            )

                            if (!allLyrics.isNullOrEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                ApiAllLyricsOverviewCard(
                                    allLyrics = allLyrics,
                                    currentIndex = currentIndex,
                                    backdrop = backdrop,
                                    cardBlend = cardBlend
                                )
                            }
                        }
                    }
                }
            }
        }

        // 右上角 API 调试控制台弹窗
        // 修复：关闭默认内置 insets，仅保留底部导航白条/三大按键 + 16dp 呼吸空间，避免空间过大
        WindowBottomSheet(
            show = isDebugSheetShowing,
            title = stringResource(R.string.api_debug_and_test),
            defaultWindowInsetsPadding = false,
            startAction = {
                IconButton(onClick = { isDebugSheetShowing = false }) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = stringResource(android.R.string.cancel),
                        tint = colorScheme.onBackground
                    )
                }
            },
            endAction = {
                IconButton(onClick = { isDebugSheetShowing = false }) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = stringResource(android.R.string.ok),
                        tint = colorScheme.onBackground
                    )
                }
            },
            onDismissRequest = { isDebugSheetShowing = false }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                )
            ) {
                item {
                    SmallTitle(text = stringResource(R.string.api_debug_listen_control), insideMargin = PaddingValues(16.dp, 8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = colorScheme.secondaryContainer
                        )
                    ) {
                        SwitchPreference(
                            checked = paused,
                            onCheckedChange = {
                                ApiReceiverManager.pausedFlow.value = it
                            },
                            title = stringResource(R.string.api_debug_pause_listen),
                            summary = if (paused) stringResource(R.string.api_debug_pause_listen_summary_paused) else stringResource(R.string.api_debug_pause_listen_summary_running)
                        )
                    }

                    SmallTitle(text = stringResource(R.string.api_debug_mock_injection), insideMargin = PaddingValues(16.dp, 8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = colorScheme.secondaryContainer
                        )
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.api_debug_inject_full),
                            summary = stringResource(R.string.api_debug_inject_full_summary),
                            onClick = {
                                ApiReceiverManager.injectMockLyric()
                                Toast.makeText(context, R.string.api_debug_injected_full_toast, Toast.LENGTH_SHORT).show()
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.api_debug_advance_line),
                            summary = stringResource(R.string.api_debug_advance_line_summary),
                            onClick = {
                                ApiReceiverManager.advanceMockProgress()
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.api_debug_advance_word),
                            summary = stringResource(R.string.api_debug_advance_word_summary),
                            onClick = {
                                ApiReceiverManager.advanceMockWordProgress(400)
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.api_debug_inject_stop),
                            summary = stringResource(R.string.api_debug_inject_stop_summary),
                            onClick = {
                                ApiReceiverManager.injectMockStop()
                                Toast.makeText(context, R.string.api_debug_injected_stop_toast, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    SmallTitle(text = stringResource(R.string.api_debug_service_sync_reset), insideMargin = PaddingValues(16.dp, 8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = colorScheme.secondaryContainer
                        )
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.api_debug_pull_latest),
                            summary = stringResource(R.string.api_debug_pull_latest_summary),
                            onClick = {
                                ApiReceiverManager.pullLatest { success ->
                                    Toast.makeText(
                                        context,
                                        if (success) R.string.api_debug_pull_success else R.string.api_debug_pull_no_data,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.api_debug_reset_stats),
                            summary = stringResource(R.string.api_debug_reset_stats_summary),
                            onClick = {
                                ApiReceiverManager.reset()
                                Toast.makeText(context, R.string.api_debug_reset_toast, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 沉浸式开源音乐播放器风格的歌词呈现组件
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiLyricPlayerView(
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
            data.currentLyric
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

/**
 * 底部看板的状态药丸（静止时露在屏幕最底端）
 */
@Composable
private fun ApiPanelPeekHeader(
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
private fun ApiMonitorSummaryCard(
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
private fun ApiMediaMetadataCard(
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
private fun ApiCurrentLineCard(
    data: SuperLyricData?,
    currentIndex: Int,
    backdrop: LayerBackdrop?,
    cardBlend: List<BlendColorEntry>
) {
    val currentLine = data?.currentLyric

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
private fun ApiAllLyricsOverviewCard(
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


private fun formatTimeMs(ms: Long): String {
    if (ms < 0) return "00:00.000"
    val minutes = (ms / 1000) / 60
    val seconds = (ms / 1000) % 60
    val millis = ms % 1000
    return String.format(Locale.getDefault(), "%02d:%02d.%03d", minutes, seconds, millis)
}

/**
 * API 接收器与状态管理单例
 */
internal object ApiReceiverManager {
    val receiverFlow = MutableStateFlow(ApiReceiverState())
    val pausedFlow = MutableStateFlow(false)

    private var isRegistered = false
    private var totalPackets = 0
    private var fullPackets = 0
    private var progressPackets = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val mReceiver = object : ISuperLyricReceiver.Stub() {
        override fun onLyric(publisher: String?, data: SuperLyricData?) {
            if (pausedFlow.value) return
            handleIncomingLyric(publisher, data)
        }

        override fun onStop(publisher: String?, data: SuperLyricData?) {
            if (pausedFlow.value) return
            handleIncomingStop(publisher, data)
        }
    }

    fun ensureRegistered() {
        try {
            if (!SuperLyricHelper.isAvailable()) return
            val currentlyRegistered = SuperLyricHelper.isReceiverRegistered(mReceiver)
            if (!currentlyRegistered) {
                SuperLyricHelper.registerReceiver(mReceiver)
            }
            isRegistered = true
        } catch (e: Throwable) {
            android.util.Log.e("ApiReceiverManager", "Failed to register receiver", e)
            isRegistered = false
        }
        if (receiverFlow.value.data == null) {
            pullLatest()
        }
    }

    fun unregister() {
        try {
            if (SuperLyricHelper.isAvailable() && SuperLyricHelper.isReceiverRegistered(mReceiver)) {
                SuperLyricHelper.unregisterReceiver(mReceiver)
            }
        } catch (e: Throwable) {
            android.util.Log.e("ApiReceiverManager", "Failed to unregister receiver", e)
        } finally {
            isRegistered = false
        }
    }

    private fun handleIncomingLyric(publisher: String?, data: SuperLyricData?) {
        totalPackets++
        val isFull = data != null && data.hasAllLyrics()
        if (isFull) {
            fullPackets++
        } else {
            progressPackets++
        }

        // 使用官方 SuperLyricCache 统一解析增量与全量歌词
        val effectiveData = SuperLyricCache.resolve(data) ?: data
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        receiverFlow.value = ApiReceiverState(
            publisher = publisher,
            data = effectiveData,
            eventType = "ON_LYRIC",
            receiveTime = time,
            packetSeq = totalPackets,
            fullCount = fullPackets,
            progressCount = progressPackets,
            allLyricsCache = effectiveData?.allLyrics
        )
    }

    private fun handleIncomingStop(publisher: String?, data: SuperLyricData?) {
        totalPackets++
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val effectiveData = SuperLyricCache.resolve(data) ?: data
        receiverFlow.value = ApiReceiverState(
            publisher = publisher,
            data = effectiveData,
            eventType = "ON_STOP",
            receiveTime = time,
            packetSeq = totalPackets,
            fullCount = fullPackets,
            progressCount = progressPackets,
            allLyricsCache = effectiveData?.allLyrics
        )
    }

    fun pullLatest(onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                val latest = SuperLyricHelper.getLatestLyric()
                if (latest != null) {
                    val effectiveData = SuperLyricCache.resolve(latest) ?: latest
                    totalPackets++
                    if (latest.hasAllLyrics()) fullPackets++ else progressPackets++
                    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                    receiverFlow.value = ApiReceiverState(
                        publisher = "SuperLyricService",
                        data = effectiveData,
                        eventType = "PULL_LATEST",
                        receiveTime = time,
                        packetSeq = totalPackets,
                        fullCount = fullPackets,
                        progressCount = progressPackets,
                        allLyricsCache = effectiveData.allLyrics
                    )
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(true)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false)
                }
            }
        }
    }

    fun injectMockLyric() {
        val mockWords1 = arrayOf(
            SuperLyricWord("看", 0, 400),
            SuperLyricWord("蔚", 400, 800),
            SuperLyricWord("蓝", 800, 1200),
            SuperLyricWord("的", 1200, 1500),
            SuperLyricWord("天", 1500, 2000),
            SuperLyricWord("空", 2000, 2600)
        )
        val line1 = SuperLyricLine("看蔚蓝的天空", mockWords1, 0, 2600)
        val line2 = SuperLyricLine(
            "白云在轻轻飘动",
            arrayOf(
                SuperLyricWord("白", 2700, 3100),
                SuperLyricWord("云", 3100, 3500),
                SuperLyricWord("在", 3500, 3800),
                SuperLyricWord("轻", 3800, 4200),
                SuperLyricWord("轻", 4200, 4600),
                SuperLyricWord("飘", 4600, 5000),
                SuperLyricWord("动", 5000, 5600)
            ),
            2700,
            5600
        )
        val line3 = SuperLyricLine("耳边回响着熟悉的旋律", null, 5700, 9000)
        val line4 = SuperLyricLine("那是我们写下的歌", null, 9100, 13000)
        val line5 = SuperLyricLine("记录下青春最初的颜色", null, 13100, 17500)
        val line6 = SuperLyricLine("穿越时空的思念依然如昨", null, 17600, 22000)

        val allLines = arrayOf(line1, line2, line3, line4, line5, line6)
        val mockData = SuperLyricData()
            .setTitle("晴天之歌")
            .setArtist("SuperLyric")
            .setAlbum("Demo Album")
            .setLyricId("mock_song_001")
            .setDuration(22000)
            .setPosition(600)
            .setCurrentLyricIndex(0)
            .setLyric(line1)
            .setTranslation(SuperLyricLine("Look at the azure sky", null, 0, 2600))
            .setSecondary(SuperLyricLine("kan wei lan de tian kong", null, 0, 2600))
            .setAllLyrics(allLines)

        handleIncomingLyric("MockDebugPublisher", mockData)
    }

    fun advanceMockProgress() {
        val current = receiverFlow.value
        val data = current.data ?: run {
            injectMockLyric()
            return
        }
        val lines = current.allLyricsCache ?: data.allLyrics
        if (lines.isNullOrEmpty()) {
            injectMockLyric()
            return
        }
        val curIdx = data.currentLyricIndex
        val nextIdx = (curIdx + 1) % lines.size
        val nextLine = lines[nextIdx]
        val nextPos = nextLine.startTime + 100

        val progressData = SuperLyricData()
            .setTitle(data.title)
            .setArtist(data.artist)
            .setAlbum(data.album)
            .setLyricId(data.lyricId)
            .setDuration(data.duration)
            .setPosition(nextPos)
            .setCurrentLyricIndex(nextIdx)
            .setLyric(nextLine)

        if (nextIdx == 0) {
            progressData.setTranslation(SuperLyricLine("Look at the azure sky", null, 0, 2600))
            progressData.setSecondary(SuperLyricLine("kan wei lan de tian kong", null, 0, 2600))
        } else if (nextIdx == 1) {
            progressData.setTranslation(SuperLyricLine("White clouds drift softly by", null, 2700, 5600))
        }

        handleIncomingLyric(current.publisher ?: "MockDebugPublisher", progressData)
    }

    fun advanceMockWordProgress(stepMs: Long = 400) {
        val current = receiverFlow.value
        val data = current.data ?: run {
            injectMockLyric()
            return
        }
        val lines = current.allLyricsCache ?: data.allLyrics
        if (lines.isNullOrEmpty()) {
            injectMockLyric()
            return
        }
        val curIdx = data.currentLyricIndex
        val safeIdx = if (curIdx in lines.indices) curIdx else 0
        val curLine = lines[safeIdx]
        val curPos = if (data.position >= 0) data.position else curLine.startTime

        val (nextPos, nextIdx) = if (curPos + stepMs >= curLine.endTime && safeIdx + 1 < lines.size) {
            val nLine = lines[safeIdx + 1]
            Pair(nLine.startTime + 100, safeIdx + 1)
        } else {
            Pair(curPos + stepMs, safeIdx)
        }

        val targetLine = if (nextIdx in lines.indices) lines[nextIdx] else curLine

        val progressData = SuperLyricData()
            .setTitle(data.title)
            .setArtist(data.artist)
            .setAlbum(data.album)
            .setLyricId(data.lyricId)
            .setDuration(if (data.hasDuration() && data.duration > 0) data.duration else 0)
            .setPosition(nextPos)
            .setCurrentLyricIndex(nextIdx)
            .setLyric(targetLine)

        if (data.hasTranslation()) progressData.setTranslation(data.translation)
        if (data.hasSecondary()) progressData.setSecondary(data.secondary)

        handleIncomingLyric(current.publisher ?: "MockDebugPublisher", progressData)
    }

    fun injectMockStop() {
        val current = receiverFlow.value
        handleIncomingStop(current.publisher ?: "MockDebugPublisher", current.data)
    }

    fun reset() {
        totalPackets = 0
        fullPackets = 0
        progressPackets = 0
        SuperLyricCache.clear()
        receiverFlow.value = ApiReceiverState()
    }
}

internal data class ApiReceiverState(
    val publisher: String? = null,
    val data: SuperLyricData? = null,
    val eventType: String? = null,
    val receiveTime: String? = null,
    val packetSeq: Int = 0,
    val fullCount: Int = 0,
    val progressCount: Int = 0,
    val allLyricsCache: Array<SuperLyricLine>? = null,
    val positionTimestamp: Long = SystemClock.elapsedRealtime()
)
