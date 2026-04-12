/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.data.LocalHandlePagerChange
import com.hchen.superlyric.ui.data.LocalMiuixScrollBehavior
import com.hchen.superlyric.ui.data.LocalPagerState
import com.hchen.superlyric.ui.data.LocalViewModel
import com.hchen.superlyric.ui.data.UIConstants
import com.hchen.superlyric.ui.layout.AboutLayout
import com.hchen.superlyric.ui.layout.SupportAppLayout
import com.hchen.superlyric.ui.viewmodel.MainViewModel
import com.hchen.superlyric.ui.viewmodel.MainViewModelFactory
import com.hchen.superlyric.utils.PackageUtils
import com.hchen.superlyricapi.SuperLyricHelper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        PackageUtils.initialPackage(this)

        setContent {
            App()
        }
    }

    @Composable
    private fun App() {
        val scrollBehavior = MiuixScrollBehavior()
        val pagerState = rememberPagerState(pageCount = { UIConstants.PAGE_COUNT })
        val coroutineScope = rememberCoroutineScope()
        val handlePagerChange: (Boolean, Int) -> Unit = remember(pagerState, coroutineScope) {
            { isWideScreen, page ->
                coroutineScope.launch {
                    if (isWideScreen)
                        pagerState.scrollToPage(page)
                    else pagerState.animateScrollToPage(page)
                }
            }
        }

        var showUnavailable by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            showUnavailable = !SuperLyricHelper.isAvailable()
        }

        CompositionLocalProvider(
            LocalViewModel provides viewModel,
            LocalMiuixScrollBehavior provides scrollBehavior,
            LocalPagerState provides pagerState,
            LocalHandlePagerChange provides handlePagerChange
        ) {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWideScreen = maxWidth > UIConstants.WIDE_SCREEN_THRESHOLD ||
                            (maxWidth > UIConstants.MEDIUM_WIDTH_THRESHOLD && (maxHeight.value / maxWidth.value < UIConstants.PORTRAIT_ASPECT_RATIO_THRESHOLD))
                    if (isWideScreen) WideScreenLayout()
                    else CompactScreenLayout()
                }
            }

            WindowDialog(
                show = showUnavailable,
                title = stringResource(R.string.warn),
                summary = stringResource(
                    R.string.service_unavailable,
                    runCatching { SuperLyricHelper.registerPublisher() }
                        .exceptionOrNull()?.message ?: "Unknown"
                )
            ) {
                Row(horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            showUnavailable = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }

    @Composable
    private fun CompactScreenLayout() {
        val isSearching by viewModel.isSearching.collectAsState()
        val pagerState = LocalPagerState.current
        val handePagerChange = LocalHandlePagerChange.current

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                AnimatedVisibility(
                    visible = !isSearching,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    NavigationBar {
                        NavigationBarItem(
                            label = stringResource(R.string.home),
                            icon = MiuixIcons.HorizontalSplit,
                            selected = pagerState.currentPage == UIConstants.HOME_PAGE_INDEX,
                            onClick = {
                                handePagerChange(false, UIConstants.HOME_PAGE_INDEX)
                            }
                        )

                        NavigationBarItem(
                            label = stringResource(R.string.about),
                            icon = MiuixIcons.Info,
                            selected = pagerState.currentPage == UIConstants.ABOUT_PAGE_INDEX,
                            onClick = {
                                handePagerChange(false, UIConstants.ABOUT_PAGE_INDEX)
                            }
                        )
                    }
                }
            }
        ) {
            UiContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = it.calculateBottomPadding()),
                isWideScreen = false
            )
        }
    }

    @Composable
    private fun WideScreenLayout() {
        val windowWidth = LocalWindowInfo.current.containerSize.width
        var weight by remember(windowWidth) { mutableFloatStateOf(0.4f) }
        val dragState = rememberDraggableState { delta ->
            val nextWeight = weight + delta / windowWidth
            weight = nextWeight.coerceIn(0.2f, 0.5f)
        }

        val scrollBehavior = MiuixScrollBehavior()
        val pagerState = LocalPagerState.current
        val handePagerChange = LocalHandlePagerChange.current

        Scaffold(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.surface)
            ) {
                Box(modifier = Modifier.weight(weight = weight)) {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 18.dp, end = 12.dp),
                        topBar = {
                            TopAppBar(
                                title = stringResource(R.string.app_name),
                                scrollBehavior = scrollBehavior
                            )
                        },
                        popupHost = {}
                    ) { paddingValues ->
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                                .overScrollVertical()
                                .scrollEndHaptic()
                                .nestedScroll(scrollBehavior.nestedScrollConnection)
                        ) {
                            item {
                                Card(
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    BasicComponent(
                                        title = stringResource(R.string.home),
                                        onClick = { handePagerChange(true, UIConstants.HOME_PAGE_INDEX) },
                                        holdDownState = pagerState.currentPage == UIConstants.HOME_PAGE_INDEX
                                    )

                                    BasicComponent(
                                        title = stringResource(R.string.about),
                                        onClick = { handePagerChange(true, UIConstants.ABOUT_PAGE_INDEX) },
                                        holdDownState = pagerState.currentPage == UIConstants.ABOUT_PAGE_INDEX
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalDivider(
                    modifier = Modifier
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Horizontal
                        )
                        .padding(horizontal = 6.dp)
                )
                Box(modifier = Modifier.weight(weight = 1f - weight)) {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 6.dp),
                        popupHost = {}
                    ) { paddingValues ->
                        UiContent(
                            isWideScreen = true
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun UiContent(
        modifier: Modifier = Modifier,
        isWideScreen: Boolean = false
    ) {
        val pagerState = LocalPagerState.current
        HorizontalPager(
            modifier = modifier,
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            verticalAlignment = Alignment.Top,
            overscrollEffect = null
        ) { page ->
            when (page) {
                UIConstants.HOME_PAGE_INDEX -> {
                    SupportAppLayout(isWideScreen)
                }

                UIConstants.ABOUT_PAGE_INDEX -> {
                    AboutLayout(isWideScreen)
                }
            }
        }
    }
}