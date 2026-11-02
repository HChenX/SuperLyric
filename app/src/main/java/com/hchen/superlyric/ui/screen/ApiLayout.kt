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
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hchen.superlyric.BuildConfig
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.data.LocalPagerState
import com.hchen.superlyric.ui.data.UIConstants
import com.hchen.superlyric.ui.effect.BgEffectBackground
import com.hchen.superlyric.ui.effect.BgEffectTheme
import com.hchen.superlyric.ui.effect.BlurredBar
import com.hchen.superlyric.ui.effect.cardBlendColors
import com.hchen.superlyric.ui.effect.rememberBlurBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun ApiLayout(
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    val pagerState = LocalPagerState.current

    // 前台时确保接收器已注册，后台时注销，避免 IPC 事务投递到被冻结进程；API 页可见时再次确保注册
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

    // 卡片接近顶栏时进入折叠状态并启用毛玻璃模糊
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
    val cardBlend = cardBlendColors(isInDark)

    val density = LocalDensity.current
    val viewportHeightDp by remember {
        derivedStateOf {
            with(density) { lazyListState.layoutInfo.viewportSize.height.toDp() }
        }
    }

    val allLyrics = state.lyric?.lines
    val currentIndex = state.index

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
                        // 调试面板可注入模拟歌词、暂停接收、清零计数，仅 DEBUG 构建可见
                        if (BuildConfig.DEBUG) {
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
                    }
                )
            }
        }
    ) { pv ->
        val topBarPadding = pv.calculateTopPadding()
        // 看板收起时歌词区的高度：为歌词保留视口，底部露出看板把手与状态概览
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
                // 上层：歌词展示区
                // - 顶部直接对齐 topBarPadding，不加额外间距
                // - 面板收起时置于顶层，接收歌词区的滑动
                // - 上拉时由 graphicsLayer 的 alpha 渐隐；播放器不重建，逐字进度不会被重置
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

                    // 面板内容放在具备最小视口高度的 Column 中：
                    // - panelSpacer 能完全滚出视口，scrollProgress 可达到 1f（0.85f 起触发顶栏模糊）
                    // - contentPadding.bottom 保证滚到顶部时最后一项不被底栏遮挡
                    // - 上滑时渐显带顶部圆角的容器背景，遮住卡片间隙透出的底层像素
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
                                // 拖拽把手
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
                                backdrop = backdrop,
                                cardBlend = cardBlend
                            )

                            Spacer(Modifier.height(12.dp))

                            ApiMediaMetadataCard(
                                state = state,
                                backdrop = backdrop,
                                cardBlend = cardBlend
                            )

                            Spacer(Modifier.height(12.dp))

                            ApiCurrentLineCard(
                                state = state,
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

        // API 调试控制台；关闭默认 insets，仅保留底部系统导航栏高度与 16dp 内边距
        WindowBottomSheet(
            show = BuildConfig.DEBUG && isDebugSheetShowing,
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
