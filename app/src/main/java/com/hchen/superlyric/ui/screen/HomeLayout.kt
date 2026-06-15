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
package com.hchen.superlyric.ui.screen

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.hchen.hooktool.data.AppData
import com.hchen.hooktool.utils.InvokeTool
import com.hchen.hooktool.utils.PrefsTool
import com.hchen.superlyric.R
import com.hchen.superlyric.data.ApiAppData
import com.hchen.superlyric.data.PrefsKey
import com.hchen.superlyric.data.SupportApps
import com.hchen.superlyric.ui.Application
import com.hchen.superlyric.ui.component.SearchBox
import com.hchen.superlyric.ui.component.SearchPager
import com.hchen.superlyric.ui.component.SearchStatus
import com.hchen.superlyric.ui.component.SearchStatus.Status.COLLAPSED
import com.hchen.superlyric.ui.component.SearchStatus.Status.COLLAPSING
import com.hchen.superlyric.ui.component.SearchStatus.Status.EXPANDED
import com.hchen.superlyric.ui.component.SearchStatus.Status.EXPANDING
import com.hchen.superlyric.ui.component.TopAppBarAnim
import com.hchen.superlyric.ui.data.LocalViewModel
import com.hchen.superlyric.ui.effect.BlurredBar
import com.hchen.superlyric.ui.effect.rememberBlurBackdrop
import com.hchen.superlyric.ui.viewmodel.MainUiAction
import com.hchen.superlyric.ui.viewmodel.MainViewModel
import com.hchen.superlyric.utils.PackageUtils
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun HomeLayout(
    paddingValues: PaddingValues,
    isWideScreen: Boolean = false
) {
    val context = LocalContext.current
    val viewModel = LocalViewModel.current
    val hookApps by viewModel.hookApps.collectAsState()
    val apiApps by viewModel.apiApps.collectAsState()
    val currentApp by viewModel.currentApp.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    val scrollBehavior = MiuixScrollBehavior()
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    val searchStatus by remember { mutableStateOf(SearchStatus(label = context.getString(R.string.select_search))) }
    val filteredHookApps = remember(searchStatus.searchText, hookApps) {
        val query = searchStatus.searchText.trim()
        if (query.isEmpty()) emptyList()
        else hookApps.filter { it.label.contains(query, ignoreCase = true) }
            .also { PackageUtils.sortAppDataList(it) }
    }
    val filteredApiApps = remember(searchStatus.searchText, apiApps) {
        val query = searchStatus.searchText.trim()
        if (query.isEmpty()) emptyList()
        else apiApps.filter { it.label.contains(query, ignoreCase = true) }
            .also { PackageUtils.sortAppDataList(it) }
    }

    LaunchedEffect(searchStatus.searchText, filteredHookApps, filteredApiApps) {
        searchStatus.resultStatus = when {
            searchStatus.searchText.isBlank() -> SearchStatus.ResultStatus.DEFAULT
            filteredHookApps.isEmpty() && filteredApiApps.isEmpty() -> SearchStatus.ResultStatus.EMPTY
            else -> SearchStatus.ResultStatus.SHOW
        }
    }

    LaunchedEffect(searchStatus.current) {
        when (searchStatus.current) {
            EXPANDED -> viewModel.handleAction(MainUiAction.Searching(true))
            EXPANDING -> viewModel.handleAction(MainUiAction.Searching(true))
            COLLAPSED -> viewModel.handleAction(MainUiAction.Searching(false))
            COLLAPSING -> viewModel.handleAction(MainUiAction.Searching(false))
        }
    }

    val pullToRefreshState = rememberPullToRefreshState()
    val show = remember { mutableStateOf(false) }

    val logLevel by viewModel.logLevel.collectAsState()
    val logLevels = remember {
        listOf(
            context.getString(R.string.log_I),
            context.getString(R.string.log_W),
            context.getString(R.string.log_E),
            context.getString(R.string.log_D)
        )
    }

    val state by AnalogReceiver.receiverFlow.collectAsState()
    val registered by AnalogReceiver.registeredFlow.collectAsState()
    val paused by AnalogReceiver.pausedFlow.collectAsState()
    var isApiTestDialogShowing by remember { mutableStateOf(false) }

    LaunchedEffect(isApiTestDialogShowing) {
        if (!isApiTestDialogShowing) {
            if (SuperLyricHelper.isAvailable()) {
                if (SuperLyricHelper.isReceiverRegistered(AnalogReceiver.mReceiver)) {
                    SuperLyricHelper.unregisterReceiver(AnalogReceiver.mReceiver)
                    AnalogReceiver.registeredFlow.value = false
                    AnalogReceiver.receiverFlow.value = ReceiverState()
                }
            }
        }
    }

    val settingsEntries = remember {
        listOf(
            DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = "API 测试",
                        selected = false,
                        onClick = {
                            isApiTestDialogShowing = true
                        }
                    ),
                    DropdownItem(
                        text = context.getString(R.string.clear_dexkit_cache),
                        selected = false,
                        onClick = {
                            var version = Application.getRemotePreferences().getInt("super_lyric_dexkit_cache_version", 0)
                            Application.getRemotePreferences().edit { putInt("super_lyric_dexkit_cache_version", ++version) }
                            Toast.makeText(context, context.getString(R.string.cleared), Toast.LENGTH_SHORT).show()
                        }
                    ),
                    DropdownItem(
                        text = context.getString(R.string.log_level),
                        children = logLevels.mapIndexed { index, text ->
                            DropdownItem(
                                text = text,
                                selected = logLevel == index,
                                onClick = {
                                    viewModel.handleAction(MainUiAction.UpdateLogLevel(index))
                                    PrefsTool.prefs(context).edit { putInt(PrefsKey.LOG_LEVEL, index) }
                                }
                            )
                        }
                    ),
                )
            )
        )
    }

    val actions: @Composable RowScope.() -> Unit = {
        OverlayIconCascadingDropdownMenu(
            entries = settingsEntries,
            collapseOnSelection = true,
        ) {
            Icon(
                imageVector = MiuixIcons.Settings,
                contentDescription = "Tune",
            )
        }
    }

    Scaffold(
        topBar = {
            searchStatus.TopAppBarAnim {
                BlurredBar(backdrop = backdrop, blurEnabled = blurActive) {
                    if (isWideScreen) {
                        SmallTopAppBar(
                            title = stringResource(R.string.home),
                            scrollBehavior = scrollBehavior,
                            defaultWindowInsetsPadding = false,
                            color = if (blurActive) Color.Transparent else colorScheme.surface,
                            actions = actions
                        )
                    } else {
                        TopAppBar(
                            title = stringResource(R.string.home),
                            scrollBehavior = scrollBehavior,
                            defaultWindowInsetsPadding = false,
                            color = if (blurActive) Color.Transparent else colorScheme.surface,
                            actions = actions
                        )
                    }
                }
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                searchBarTopPadding = dynamicTopPadding,
                enableRefresh = true,
                onRefreshing = {
                    viewModel.handleAction(MainUiAction.Refresh)
                },
                result = {
                    if (filteredApiApps.isNotEmpty()) {
                        item {
                            SmallTitle(text = stringResource(R.string.apps_api_list))
                        }

                        itemsIndexed(
                            items = filteredApiApps,
                            key = { _, apiData -> apiData.packageName }
                        ) { index, apiData ->
                            AppItemFactory(show, apiData)
                        }
                    }
                    if (filteredHookApps.isNotEmpty()) {
                        item {
                            SmallTitle(text = stringResource(R.string.apps_hook_list))
                        }

                        itemsIndexed(
                            items = filteredHookApps,
                            key = { _, appData -> appData.packageName }
                        ) { index, appData ->
                            AppItemFactory(show, appData)
                        }
                    }
                },
                defaultResult = {
                    EmptyApps(stringResource(R.string.select_none))
                }
            )
        }
    ) { pv ->
        searchStatus.SearchBox(
            backdrop = backdrop,
            searchBarTopPadding = dynamicTopPadding,
            contentPadding = PaddingValues(top = pv.calculateTopPadding()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                PullToRefresh(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.handleAction(MainUiAction.Refresh) },
                    pullToRefreshState = pullToRefreshState,
                    refreshTexts = listOf(
                        stringResource(R.string.pull_down_to_refresh),
                        stringResource(R.string.release_to_refresh),
                        stringResource(R.string.refreshing),
                        stringResource(R.string.refresh_successfully)
                    ),
                    contentPadding = PaddingValues(top = pv.calculateTopPadding() + it.value)
                ) {
                    Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .scrollEndHaptic()
                                .overScrollVertical()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(
                                top = pv.calculateTopPadding() + it.value,
                                bottom = paddingValues.calculateBottomPadding()
                            ),
                            overscrollEffect = null
                        ) {
                            if (hookApps.isEmpty() && apiApps.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(top = 12.dp)
                                    ) {
                                        EmptyApps(stringResource(R.string.select_list_empty))
                                    }
                                }
                            } else {
                                if (apiApps.isNotEmpty()) {
                                    item {
                                        SmallTitle(text = stringResource(R.string.apps_api_list))
                                    }
                                    itemsIndexed(
                                        items = apiApps,
                                        key = { _, apiData -> apiData.packageName }
                                    ) { index, apiData ->
                                        AppItemFactory(show, apiData)
                                    }
                                }

                                if (hookApps.isNotEmpty()) {
                                    item {
                                        SmallTitle(text = stringResource(R.string.apps_hook_list))
                                    }
                                    itemsIndexed(
                                        items = hookApps,
                                        key = { _, appData -> appData.packageName }
                                    ) { index, appData ->
                                        AppItemFactory(show, appData)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AppDetailsDialog(
            show = show,
            appData = currentApp
        )

        WindowBottomSheet(
            show = isApiTestDialogShowing,
            title = "API 测试",
            allowDismiss = false,
            startAction = {
                IconButton(
                    onClick = { isApiTestDialogShowing = false },
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = "Cancel",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            },
            endAction = {
                IconButton(
                    onClick = { isApiTestDialogShowing = false },
                ) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = "Confirm",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            },
            onDismissRequest = {
                isApiTestDialogShowing = false
            }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    SmallTitle(text = "基本状态", insideMargin = PaddingValues(16.dp, 8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.secondaryContainer,
                        )
                    ) {
                        BasicComponent(title = "API 状态：${if (SuperLyricHelper.isAvailable()) "可用" else "不可用"}")
                        BasicComponent(title = "API 版本：${SuperLyricHelper.getApiVersion()}")

                        BasicComponent(
                            title = "注册状态：${if (SuperLyricHelper.isPublisherRegistered()) "已注册" else "未注册"}",
                            summary = "SuperLyricService：${InvokeTool.getStaticField<Any>(SuperLyricHelper::class.java, "mManager")}"
                        )
                    }

                    SmallTitle(text = "模拟发布", insideMargin = PaddingValues(16.dp, 8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.secondaryContainer,
                        )
                    ) {
                        ArrowPreference(
                            title = "测试发布歌词",
                            onClick = {
                                SuperLyricHelper.sendLyric(
                                    SuperLyricData()
                                        .setLyric(
                                            SuperLyricLine(
                                                "测试歌词",
                                                arrayOf(
                                                    SuperLyricWord("测", 0, 500),
                                                    SuperLyricWord("试", 500, 1000),
                                                    SuperLyricWord("歌", 1000, 1500),
                                                    SuperLyricWord("词", 1500, 2000)
                                                ),
                                                0,
                                                2000
                                            )
                                        )
                                        .setTranslation(
                                            SuperLyricLine(
                                                "测试翻译"
                                            )
                                        )
                                )

                                Toast.makeText(context, "已发布", Toast.LENGTH_SHORT).show()
                            }
                        )
                        ArrowPreference(
                            title = "测试发布停止事件",
                            onClick = {
                                SuperLyricHelper.sendStop(SuperLyricData())
                                Toast.makeText(context, "已发布", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    SmallTitle(text = "模拟接收", insideMargin = PaddingValues(16.dp, 8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.secondaryContainer,
                        )
                    ) {
                        ArrowPreference(
                            title = if (!registered) "注册接收器" else "注销接收器",
                            summary = "当前状态：${if (registered) "已注册" else "未注册"}",
                            onClick = {
                                if (!SuperLyricHelper.isReceiverRegistered(AnalogReceiver.mReceiver)) {
                                    SuperLyricHelper.registerReceiver(AnalogReceiver.mReceiver)
                                    AnalogReceiver.registeredFlow.value = true
                                    Toast.makeText(context, "已注册", Toast.LENGTH_SHORT).show()
                                } else {
                                    SuperLyricHelper.unregisterReceiver(AnalogReceiver.mReceiver)
                                    AnalogReceiver.registeredFlow.value = false
                                    AnalogReceiver.receiverFlow.value = ReceiverState()
                                    Toast.makeText(context, "已销毁", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        ArrowPreference(
                            title = if (!paused) "暂停接收" else "恢复接收",
                            onClick = {
                                AnalogReceiver.pausedFlow.value = !paused
                            }
                        )
                        BasicComponent(
                            title = "接收器实时数据",
                            summary = "Publisher：${state.publisher}\nData：${state.data}"
                        )
                    }

                    Spacer(
                        Modifier.padding(
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyApps(
    text: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun AppItemFactory(
    show: MutableState<Boolean>,
    appData: AppData
) {
    val viewModel = LocalViewModel.current as MainViewModel

    AppItemComponent(
        title = appData.label,
        summary = appData.packageName,
        icon = appData.icon,
        versionName = when (appData) {
            is ApiAppData -> appData.apiVersionName
            else -> appData.versionName
        },
        versionCode = when (appData) {
            is ApiAppData -> appData.apiVersionCode
            else -> appData.versionCode
        },
        onClick = {
            viewModel.handleAction(MainUiAction.CurrentApp(appData))
            show.value = true
        }
    )
}

@Composable
private fun AppItemComponent(
    title: String,
    summary: String,
    icon: Bitmap,
    versionName: String,
    versionCode: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
    ) {
        ArrowPreference(
            enabled = enabled,
            title = title,
            summary = summary,
            endActions = {
                Row(
                    horizontalArrangement = Arrangement.Absolute.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.tertiaryContainer)
                    ) {
                        Text(
                            text = versionName,
                            maxLines = 1,
                            fontSize = 12.sp,
                            overflow = TextOverflow.Visible,
                            color = MiuixTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(3.dp)
                        )
                    }
                    Spacer(Modifier.width(3.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.tertiaryContainer)
                    ) {
                        Text(
                            text = versionCode,
                            maxLines = 1,
                            fontSize = 12.sp,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(3.dp)
                        )
                    }
                }
            },
            startAction = {
                Box(Modifier.padding(end = 8.dp)) {
                    Icon(
                        painter = BitmapPainter(icon.asImageBitmap()),
                        contentDescription = "App Icon",
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
            },
            onClick = {
                onClick?.invoke()
            }
        )
    }
}

@Composable
private fun AppDetailsDialog(
    show: MutableState<Boolean>,
    appData: AppData
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    WindowDialog(
        show = show.value,
        onDismissRequest = {
            show.value = false
        }
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = BitmapPainter(appData.icon.asImageBitmap()),
                contentDescription = "App Icon",
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .padding(bottom = 6.dp)
            )

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp),
                text = appData.label,
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = DialogDefaults.titleColor(),
            )

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                text = appData.packageName,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                textAlign = TextAlign.Center,
                color = DialogDefaults.summaryColor(),
            )

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                text = stringResource(
                    R.string.current_version,
                    appData.versionName,
                    appData.versionCode
                ),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                textAlign = TextAlign.Center,
                color = DialogDefaults.summaryColor(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.tertiaryContainer)
        ) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                text = stringResource(
                    R.string.instructions_for_use,
                    when (appData) {
                        is ApiAppData -> stringResource(R.string.support_api)
                        else -> stringResource(SupportApps.mPackageToDetails[appData.packageName] ?: R.string.unknown)
                    }
                ),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                color = MiuixTheme.colorScheme.onTertiaryContainer,
            )
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
        )

        Row(horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    show.value = false
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(android.R.string.ok),
                onClick = {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(appData.packageName)
                        if (intent != null) {
                            context.startActivity(intent)
                        }
                    } catch (_: Throwable) {
                    }

                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    show.value = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

private object AnalogReceiver {
    val registeredFlow = MutableStateFlow(false)
    val pausedFlow = MutableStateFlow(false)
    val receiverFlow = MutableStateFlow(ReceiverState())
    val mReceiver = object : ISuperLyricReceiver.Stub() {
        override fun onLyric(publisher: String?, data: SuperLyricData?) {
            if (pausedFlow.value) return
            receiverFlow.value = ReceiverState(
                publisher = publisher,
                data = data
            )
        }

        override fun onStop(publisher: String?, data: SuperLyricData?) {
            if (pausedFlow.value) return
            receiverFlow.value = ReceiverState(
                publisher = publisher,
                data = data
            )
        }
    }
}

private data class ReceiverState(
    val publisher: String? = null,
    val data: SuperLyricData? = null
)