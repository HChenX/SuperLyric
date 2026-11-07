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
import android.content.SharedPreferences
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.hchen.superlyricapi.SuperLyric
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
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

import java.util.function.Consumer

class MainActivity : ComponentActivity() {

    /**
     * 本次注册到 [Application] 静态表的 prefs 监听器，供 [onDestroy] 精确摘除
     *
     * 可空：`onCreate` 在赋值前抛异常时 `onDestroy` 仍会被调用，读取未初始化的
     * `lateinit` 字段会再次抛出，把启动失败升级为崩溃
     */
    private var prefsReadyListener: Consumer<SharedPreferences>? = null
    private val appListPermission by lazy { PermissionLists.getGetInstalledAppsPermission() }

    /**
     * 进入页面时是否已具备读取应用列表的权限
     *
     * 记录进入时的状态，[onResume] 只在"无 -> 有"时重新扫描。
     * 每次回到前台都重扫会让一次普通的后台切换也触发全量包扫描。
     */
    private var appListPermissionGranted = false

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            addPrefsReadyListener = Application::addPrefsReadyListener,
            removePrefsReadyListener = Application::removePrefsReadyListener,
            addAppLoadedListener = PackageLoader::addPackageLoadedListener,
            removeAppLoadedListener = PackageLoader::removePackageLoadedListener,
            // 使用进程级 context，避免 lambda 捕获在配置变更后仍存活的 Activity
            reloadApps = { PackageLoader.loadPackages(Application.getAppContext()) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        appListPermissionGranted = hasAppListPermission()
        // 监听器注册在静态表上，不随 Activity 销毁而注销：捕获 Activity 会让每次重建
        // 泄漏一个已销毁实例，后续 prefs 就绪时还会对已销毁实例调用 loadPackages
        val appContext = applicationContext
        // 必须在 onDestroy 摘除：静态表只增不减会让每次重建多留一个监听器，
        // prefs 就绪时 loadPackages 会重复执行
        val listener = Consumer<SharedPreferences> {
            PackageLoader.loadPackages(appContext)
        }
        prefsReadyListener = listener
        Application.addPrefsReadyListener(listener)
        PackageLoader.loadPackages(this)

        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        // 覆盖从系统设置返回的授权；对话框内授权由回调处理
        if (!appListPermissionGranted && hasAppListPermission()) {
            onAppListPermissionGranted()
        }
    }

    private fun hasAppListPermission(): Boolean =
        XXPermissions.isGrantedPermission(this, appListPermission)

    private fun requestAppListPermission(onGranted: () -> Unit, onDenied: () -> Unit) {
        XXPermissions.with(this)
            .permission(appListPermission)
            .request(object : OnPermissionCallback {
                override fun onPermissionResult(
                    granted: MutableList<IPermission>,
                    denied: MutableList<IPermission>
                ) {
                    if (denied.isEmpty()) onGranted() else onDenied()
                }
            })
    }

    override fun onDestroy() {
        // 摘除的必须是本次注册的实例：静态表按实例移除
        prefsReadyListener?.let(Application::removePrefsReadyListener)
        super.onDestroy()
    }

    private fun onAppListPermissionGranted() {
        if (appListPermissionGranted) return
        appListPermissionGranted = true
        PackageLoader.loadPackages(applicationContext)
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

        val permission = remember { appListPermission }
        var showAppListPermission by remember { mutableStateOf(!appListPermissionGranted) }
        var permissionDenied by remember {
            mutableStateOf(XXPermissions.isDoNotAskAgainPermission(this@MainActivity, permission))
        }

        var showUnavailable by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            // 只探测服务是否存在；isAvailable() 会分别尝试两个服务名
            if (!SuperLyric.isAvailable()) showUnavailable = true
        }

        CompositionLocalProvider(
            LocalViewModel provides viewModel,
            LocalPagerState provides pagerState,
            LocalHandlePagerChange provides handlePagerChange
        ) {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWideScreen = UIConstants.isWideScreen(maxWidth.value, maxHeight.value)
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
                summary = stringResource(R.string.service_unavailable)
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

            WindowDialog(
                show = showAppListPermission,
                title = stringResource(R.string.app_list_permission_title),
                summary = stringResource(
                    if (permissionDenied) R.string.app_list_permission_denied_summary
                    else R.string.app_list_permission_summary
                )
            ) {
                Row(horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { showAppListPermission = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(
                            if (permissionDenied) R.string.go_to_settings else R.string.grant
                        ),
                        onClick = {
                            if (permissionDenied) {
                                XXPermissions.startPermissionActivity(
                                    this@MainActivity, permission
                                )
                                showAppListPermission = false
                            } else {
                                requestAppListPermission(
                                    onGranted = {
                                        permissionDenied = false
                                        showAppListPermission = false
                                        onAppListPermissionGranted()
                                    },
                                    onDenied = {
                                        permissionDenied =
                                            XXPermissions.isDoNotAskAgainPermission(
                                                this@MainActivity, permission
                                            )
                                    }
                                )
                            }
                        },
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
        modifier: Modifier = Modifier,
        isWideScreen: Boolean = false
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
                    HomeLayout(paddingValues, isWideScreen = isWideScreen)
                }

                UIConstants.API_PAGE_INDEX -> {
                    ApiLayout(paddingValues)
                }

                UIConstants.ABOUT_PAGE_INDEX -> {
                    AboutLayout(paddingValues)
                }
            }
        }
    }

    private data class NavigationItemData(
        val index: Int,
        val labelRes: Int,
        val icon: ImageVector
    )

    private val navigationItems = listOf(
        NavigationItemData(UIConstants.HOME_PAGE_INDEX, R.string.home, MiuixIcons.HorizontalSplit),
        NavigationItemData(UIConstants.API_PAGE_INDEX, R.string.api, MiuixIcons.Music),
        NavigationItemData(UIConstants.ABOUT_PAGE_INDEX, R.string.about, MiuixIcons.Info)
    )
}
