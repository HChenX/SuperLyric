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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.hchen.hooktool.utils.PrefsTool
import com.hchen.superlyric.R
import com.hchen.superlyric.data.PrefsKey
import com.hchen.superlyric.data.SupportApps
import com.hchen.superlyric.data.apps.ApiAppData
import com.hchen.superlyric.ui.Application
import com.hchen.superlyric.ui.data.LocalViewModel
import com.hchen.superlyric.ui.effect.BlurredBar
import com.hchen.superlyric.ui.effect.rememberBlurBackdrop
import com.hchen.superlyric.ui.viewmodel.MainUiAction
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun HomeLayout(
    paddingValues: PaddingValues,
    isWideScreen: Boolean = false,
    modifier: Modifier = Modifier
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
    val pullToRefreshState = rememberPullToRefreshState()
    var isDetailDialogVisible by remember { mutableStateOf(false) }

    val logLevel by viewModel.logLevel.collectAsState()
    val logLevels = remember {
        listOf(
            context.getString(R.string.log_I),
            context.getString(R.string.log_W),
            context.getString(R.string.log_E),
            context.getString(R.string.log_D)
        )
    }

    val settingsEntries = remember(logLevel) {
        listOf(
            DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = context.getString(R.string.clear_dexkit_cache),
                        selected = false,
                        onClick = {
                            val remotePrefs = Application.getRemotePreferences()
                            if (remotePrefs != null) {
                                var version = remotePrefs.getInt("super_lyric_dexkit_cache_version", 0)
                                remotePrefs.edit { putInt("super_lyric_dexkit_cache_version", ++version) }
                                Toast.makeText(context, context.getString(R.string.cleared), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.clear_failed), Toast.LENGTH_SHORT).show()
                            }
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
        modifier = modifier.fillMaxSize(),
        topBar = {
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
        },
    ) { pv ->
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
                contentPadding = PaddingValues(top = pv.calculateTopPadding())
            ) {
                Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                    if (apiApps.isNotEmpty() || hookApps.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .scrollEndHaptic()
                                .overScrollVertical()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(
                                top = pv.calculateTopPadding(),
                                bottom = paddingValues.calculateBottomPadding()
                            ),
                            overscrollEffect = null
                        ) {
                            item(key = "title_supported_apps") {
                                SmallTitle(text = stringResource(R.string.apps_list))
                            }

                            itemsIndexed(
                                items = apiApps,
                                key = { _, apiData -> "api_${apiData.packageName}" },
                                contentType = { _, _ -> "app_item" }
                            ) { _, apiData ->
                                AppItemComponent(
                                    appData = apiData,
                                    isApi = true,
                                    onClick = {
                                        viewModel.handleAction(MainUiAction.CurrentApp(apiData))
                                        isDetailDialogVisible = true
                                    }
                                )
                            }

                            itemsIndexed(
                                items = hookApps,
                                key = { _, appData -> "hook_${appData.packageName}" },
                                contentType = { _, _ -> "app_item" }
                            ) { _, appData ->
                                AppItemComponent(
                                    appData = appData,
                                    isApi = false,
                                    onClick = {
                                        viewModel.handleAction(MainUiAction.CurrentApp(appData))
                                        isDetailDialogVisible = true
                                    }
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                modifier = Modifier.padding(SmallTitleDefaults.InsideMargin),
                                text = stringResource(R.string.apps_list_empty),
                                style = textStyles.subtitle,
                                textAlign = TextAlign.Center,
                                color = colorScheme.onBackgroundVariant,
                            )
                        }
                    }
                }
            }
        }

        AppDetailsDialog(
            show = isDetailDialogVisible,
            appData = currentApp,
            onDismiss = { isDetailDialogVisible = false }
        )
    }
}

/**
 * Modern pill badge indicating whether the app is supported via Hook or native API.
 * Uses high-contrast, theme-adaptive tones (fresh primary for API, vivid violet for Hook)
 * to avoid dull and inactive-looking gray badges.
 */
@Composable
private fun AppTypeBadge(
    isApi: Boolean,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val (backgroundColor, textColor) = if (isApi) {
        if (isDark) {
            colorScheme.primary.copy(alpha = 0.22f) to colorScheme.primary
        } else {
            colorScheme.primary.copy(alpha = 0.12f) to colorScheme.primary
        }
    } else {
        if (isDark) {
            Color(0xFF7C4DFF).copy(alpha = 0.22f) to Color(0xFFD1C4E9)
        } else {
            Color(0xFF6750A4).copy(alpha = 0.12f) to Color(0xFF6750A4)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isApi) stringResource(R.string.badge_api) else stringResource(R.string.switch_mode_hook),
            maxLines = 1,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            overflow = TextOverflow.Visible
        )
    }
}

@Composable
private fun AppItemComponent(
    appData: AppData,
    isApi: Boolean,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
    ) {
        ArrowPreference(
            enabled = enabled,
            title = appData.label.ifEmpty { appData.packageName.orEmpty() },
            summary = appData.packageName.orEmpty(),
            endActions = {
                Row(
                    horizontalArrangement = Arrangement.Absolute.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppTypeBadge(isApi = isApi)
                }
            },
            startAction = {
                Box(Modifier.padding(end = 8.dp)) {
                    val iconBitmap = appData.icon?.asImageBitmap()
                    if (iconBitmap != null) {
                        Icon(
                            painter = BitmapPainter(iconBitmap),
                            contentDescription = appData.label,
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Music,
                                contentDescription = appData.label,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
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
    show: Boolean,
    appData: AppData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    WindowDialog(
        show = show,
        onDismissRequest = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val iconBitmap = appData.icon?.asImageBitmap()
            if (iconBitmap != null) {
                Icon(
                    painter = BitmapPainter(iconBitmap),
                    contentDescription = appData.label,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .padding(bottom = 6.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colorScheme.surfaceContainer)
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MiuixIcons.Music,
                        contentDescription = appData.label,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp),
                text = appData.label.ifEmpty { appData.packageName.orEmpty() },
                fontSize = textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = DialogDefaults.titleColor(),
            )

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                text = appData.packageName.orEmpty(),
                fontSize = textStyles.body1.fontSize,
                textAlign = TextAlign.Center,
                color = DialogDefaults.summaryColor(),
            )

            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(
                    R.string.current_version,
                    appData.versionName.orEmpty(),
                    appData.versionCode
                ),
                fontSize = textStyles.body1.fontSize,
                textAlign = TextAlign.Center,
                color = DialogDefaults.summaryColor(),
            )
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colorScheme.tertiaryContainer)
        ) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                text = stringResource(
                    R.string.instructions_for_use,
                    if (appData is ApiAppData) {
                        stringResource(R.string.support_api)
                    } else {
                        stringResource(SupportApps.sPackageLabelRes[appData.packageName] ?: R.string.unknown)
                    }
                ),
                fontSize = textStyles.body1.fontSize,
                color = colorScheme.onTertiaryContainer,
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
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(android.R.string.ok),
                onClick = {
                    try {
                        val pkgName = appData.packageName.orEmpty()
                        val intent = context.packageManager.getLaunchIntentForPackage(pkgName)
                        if (intent != null) {
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, R.string.no_activity, Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Throwable) {
                        Toast.makeText(context, R.string.no_activity, Toast.LENGTH_SHORT).show()
                    }

                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}
