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
package com.hchen.superlyric.ui.layout

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import com.hchen.hooktool.data.AppData
import com.hchen.superlyric.R
import com.hchen.superlyric.data.SupportApps
import com.hchen.superlyric.ui.base.SearchBox
import com.hchen.superlyric.ui.base.SearchPager
import com.hchen.superlyric.ui.base.SearchStatus
import com.hchen.superlyric.ui.base.SearchStatus.Status.COLLAPSED
import com.hchen.superlyric.ui.base.SearchStatus.Status.COLLAPSING
import com.hchen.superlyric.ui.base.SearchStatus.Status.EXPANDED
import com.hchen.superlyric.ui.base.SearchStatus.Status.EXPANDING
import com.hchen.superlyric.ui.data.LocalMiuixScrollBehavior
import com.hchen.superlyric.ui.data.LocalViewModel
import com.hchen.superlyric.ui.viewmodel.MainUiAction
import com.hchen.superlyric.ui.viewmodel.MainViewModel
import com.hchen.superlyric.utils.PackageUtils
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun SupportAppLayout(
    isWideScreen: Boolean = false
) {
    val context = LocalContext.current
    val viewModel = LocalViewModel.current as MainViewModel
    val hookApps by viewModel.hookApps.collectAsState()
    val apiApps by viewModel.apiApps.collectAsState()
    val currentApp by viewModel.currentApp.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val scrollBehavior = LocalMiuixScrollBehavior.current
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
        else apiApps.filter { it.appData.label.contains(query, ignoreCase = true) }
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

    Scaffold(
        topBar = {
            searchStatus.TopAppBarAnim {
                if (isWideScreen) {
                    SmallTopAppBar(
                        title = stringResource(R.string.app_name),
                        scrollBehavior = scrollBehavior,
                        defaultWindowInsetsPadding = false
                    )
                } else {
                    TopAppBar(
                        title = stringResource(R.string.app_name),
                        scrollBehavior = scrollBehavior,
                        defaultWindowInsetsPadding = false
                    )
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
                            key = { _, apiData -> apiData.appData.packageName }
                        ) { index, apiData ->
                            AppItemFactory(show, apiData.appData)
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
    ) { paddingValues ->
        searchStatus.SearchBox(
            searchBarTopPadding = dynamicTopPadding,
            contentPadding = paddingValues,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding() + it.value)
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
                    )
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            bottom = if (isWideScreen) {
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                            } else {
                                12.dp
                            }
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
                                    key = { _, apiData -> apiData.appData.packageName }
                                ) { index, apiData ->
                                    AppItemFactory(show, apiData.appData)
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

        AppDetailsDialog(
            show = show,
            appData = currentApp
        )
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
        versionName = appData.versionName,
        versionCode = appData.versionCode,
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
                text = stringResource(R.string.instructions_for_use, stringResource(SupportApps.mPackageToDetails[appData.packageName] ?: R.string.unknown)),
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