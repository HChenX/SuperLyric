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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.data.LocalHandlePagerChange
import com.hchen.superlyric.ui.data.LocalPagerState
import com.hchen.superlyric.ui.data.LocalViewModel
import com.hchen.superlyric.ui.data.UIConstants
import com.hchen.superlyric.ui.effect.BlurredBar
import com.hchen.superlyric.ui.effect.rememberBlurBackdrop
import com.hchen.superlyric.ui.screen.AboutLayout
import com.hchen.superlyric.ui.screen.ApiLayout
import com.hchen.superlyric.ui.screen.HomeLayout
import com.hchen.superlyric.ui.viewmodel.MainViewModel
import com.hchen.superlyric.ui.viewmodel.MainViewModelFactory
import com.hchen.superlyric.utils.PackageLoader
import com.hchen.superlyricapi.SuperLyricHelper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            addPrefsReadyListener = Application::addPrefsReadyListener,
            removePrefsReadyListener = Application::removePrefsReadyListener,
            addAppLoadedListener = PackageLoader::addPackageLoadedListener,
            removeAppLoadedListener = PackageLoader::removePackageLoadedListener,
            reloadApps = { PackageLoader.loadPackages(applicationContext) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        Application.addPrefsReadyListener {
            PackageLoader.loadPackages(this)
        }
        PackageLoader.loadPackages(this)

        setContent {
            App()
        }
    }

    @Composable
    private fun App() {
        val pagerState = rememberPagerState(pageCount = { UIConstants.PAGE_COUNT })
        val coroutineScope = rememberCoroutineScope()
        val handlePagerChange: (Boolean, Int) -> Unit = remember(pagerState, coroutineScope) {
            { isWideScreen, page ->
                coroutineScope.launch {
                    if (isWideScreen) {
                        pagerState.scrollToPage(page)
                    } else {
                        pagerState.animateScrollToPage(page)
                    }
                }
            }
        }

        var showUnavailable by remember { mutableStateOf(false) }
        var unavailableReason by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            if (!SuperLyricHelper.isAvailable()) {
                val errorMsg = runCatching { SuperLyricHelper.registerPublisher() }
                    .exceptionOrNull()?.message ?: "Unknown"
                unavailableReason = errorMsg
                showUnavailable = true
            }
        }

        CompositionLocalProvider(
            LocalViewModel provides viewModel,
            LocalPagerState provides pagerState,
            LocalHandlePagerChange provides handlePagerChange
        ) {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWideScreen = maxWidth > UIConstants.WIDE_SCREEN_THRESHOLD ||
                            (maxWidth > UIConstants.MEDIUM_WIDTH_THRESHOLD && (maxHeight.value / maxWidth.value < UIConstants.PORTRAIT_ASPECT_RATIO_THRESHOLD))
                    if (isWideScreen) {
                        WideScreenLayout()
                    } else {
                        CompactScreenLayout()
                    }
                }
            }

            WindowDialog(
                show = showUnavailable,
                title = stringResource(R.string.warn),
                summary = stringResource(R.string.service_unavailable, unavailableReason)
            ) {
                Row(horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = { showUnavailable = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }

    @Composable
    private fun CompactScreenLayout(modifier: Modifier = Modifier) {
        val pagerState = LocalPagerState.current
        val handlePagerChange = LocalHandlePagerChange.current

        val backdrop = rememberBlurBackdrop()
        val blurActive = backdrop != null

        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                BlurredBar(backdrop = backdrop, blurEnabled = blurActive) {
                    NavigationBar(
                        mode = NavigationBarDisplayMode.IconWithSelectedLabel,
                        color = if (blurActive) Color.Transparent else colorScheme.surface
                    ) {
                        navigationItems.forEach { item ->
                            NavigationBarItem(
                                label = stringResource(item.labelRes),
                                icon = item.icon,
                                selected = pagerState.currentPage == item.index,
                                onClick = { handlePagerChange(false, item.index) }
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                UiContent(
                    paddingValues = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding()),
                    isWideScreen = false
                )
            }
        }
    }

    @Composable
    private fun WideScreenLayout(modifier: Modifier = Modifier) {
        val windowWidth = LocalWindowInfo.current.containerSize.width
        var weight by remember(windowWidth) { mutableFloatStateOf(0.25f) }
        val dragState = rememberDraggableState { delta ->
            if (windowWidth > 0) {
                val nextWeight = weight + delta / windowWidth
                weight = nextWeight.coerceIn(0.25f, 0.3f)
            }
        }

        val scrollBehavior = MiuixScrollBehavior()
        val pagerState = LocalPagerState.current
        val handlePagerChange = LocalHandlePagerChange.current

        Scaffold(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.surface)
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
                                    navigationItems.forEach { item ->
                                        BasicComponent(
                                            title = stringResource(item.labelRes),
                                            onClick = { handlePagerChange(true, item.index) },
                                            holdDownState = pagerState.currentPage == item.index
                                        )
                                    }
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
                )
                Box(modifier = Modifier.weight(weight = 1f - weight)) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        popupHost = {}
                    ) { paddingValues ->
                        UiContent(
                            paddingValues = PaddingValues(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding()),
                            isWideScreen = true
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun UiContent(
        paddingValues: PaddingValues,
        isWideScreen: Boolean = false,
        modifier: Modifier = Modifier
    ) {
        val pagerState = LocalPagerState.current
        HorizontalPager(
            modifier = modifier.fillMaxSize(),
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            verticalAlignment = Alignment.Top,
            overscrollEffect = null
        ) { page ->
            when (page) {
                UIConstants.HOME_PAGE_INDEX -> {
                    HomeLayout(paddingValues, isWideScreen)
                }

                UIConstants.API_PAGE_INDEX -> {
                    ApiLayout(paddingValues, isWideScreen)
                }

                UIConstants.ABOUT_PAGE_INDEX -> {
                    AboutLayout(paddingValues, isWideScreen)
                }
            }
        }
    }

    private data class NavigationItemData(
        val index: Int,
        val labelRes: Int,
        val icon: androidx.compose.ui.graphics.vector.ImageVector
    )

    private val navigationItems = listOf(
        NavigationItemData(UIConstants.HOME_PAGE_INDEX, R.string.home, MiuixIcons.HorizontalSplit),
        NavigationItemData(UIConstants.API_PAGE_INDEX, R.string.api, MiuixIcons.Music),
        NavigationItemData(UIConstants.ABOUT_PAGE_INDEX, R.string.about, MiuixIcons.Info)
    )
}