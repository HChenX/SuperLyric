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
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.hchen.superlyric.BuildConfig
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.effect.BgEffectBackground
import com.hchen.superlyric.ui.effect.BlurredBar
import com.hchen.superlyric.ui.effect.blend.ColorBlendToken
import com.hchen.superlyric.ui.effect.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun AboutLayout(
    paddingValues: PaddingValues,
    isWideScreen: Boolean = false
) {
    val context = LocalContext.current
    val icon = remember {
        context.packageManager.getApplicationIcon(context.packageName)
    }

    val contributor = remember {
        listOf(
            "ghhccghk",
            "YifePlayte",
            "YuKongA",
            "AnserJim"
        )
    }
    val contributorUri = remember {
        listOf(
            "https://github.com/ghhccghk",
            "https://github.com/YifePlayte",
            "https://github.com/YuKongA",
            "https://github.com/killerprojecte"
        )
    }
    val contributorIcon = remember {
        listOf(
            R.drawable.ghhccghk,
            R.drawable.yifeplayte,
            R.drawable.yukonga,
            R.drawable.anserjim
        )
    }

    var isContributorsDialogShowing by remember { mutableStateOf(false) }

    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f

                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / (spacer.size)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val isInDark = isSystemInDarkTheme()
    val cardBlend = if (isInDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
    val logoBlend = remember(isInDark) {
        if (isInDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val backdrop = rememberBlurBackdrop()
    val barBackdrop = rememberBlurBackdrop()
    // Defer the frame-rate scroll read out of composition: these booleans only flip at the
    // single 1f threshold, so derivedStateOf recomposes the bar on flip rather than every frame.
    val collapsed by remember { derivedStateOf { scrollProgress == 1f } }
    val blurActive by remember(barBackdrop) { derivedStateOf { barBackdrop != null && scrollProgress == 1f } }

    val barColor = if (blurActive) {
        Color.Transparent
    } else {
        if (collapsed) MiuixTheme.colorScheme.surface else Color.Transparent
    }
    val titleColor = MiuixTheme.colorScheme.onSurface.copy(
        alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
    )

    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(300.dp) }

    val versionCodeProgress = ((scrollProgress - 0.05f) / 0.15f).coerceIn(0f, 1f)
    val projectNameProgress = ((scrollProgress - 0.20f) / 0.15f).coerceIn(0f, 1f)
    val iconProgress = ((scrollProgress - 0.35f) / 0.15f).coerceIn(0f, 1f)

    Scaffold(
        topBar = {
            BlurredBar(backdrop = barBackdrop, blurEnabled = blurActive) {
                SmallTopAppBar(
                    title = stringResource(R.string.about),
                    scrollBehavior = scrollBehavior,
                    color = barColor,
                    titleColor = titleColor,
                    defaultWindowInsetsPadding = false
                )
            }
        }
    ) { pv ->
        Box(modifier = if (barBackdrop != null) Modifier.layerBackdrop(barBackdrop) else Modifier) {
            BgEffectBackground(
                dynamicBackground = true,
                isOs3Effect = true,
                isFullSize = false,
                modifier = Modifier.fillMaxSize(),
                bgModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
                alpha = { 1f - scrollProgress }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = pv.calculateTopPadding() + 80.dp)
                        .onSizeChanged { size ->
                            with(density) { logoHeightDp = size.height.toDp() }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(88.dp)
                            .graphicsLayer {
                                clip = true
                                shape = RoundedCornerShape(16.dp)
                                alpha = 1 - iconProgress
                                scaleX = 1 - (iconProgress * 0.05f)
                                scaleY = 1 - (iconProgress * 0.05f)
                            }
                            .background(Color.White),
                    ) {
                        Image(
                            modifier = Modifier.size(88.dp),
                            painter = BitmapPainter(icon.toBitmap().asImageBitmap()),
                            contentDescription = null,
                        )
                    }
                    Text(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 5.dp)
                            .graphicsLayer {
                                alpha = 1 - projectNameProgress
                                scaleX = 1 - (projectNameProgress * 0.05f)
                                scaleY = 1 - (projectNameProgress * 0.05f)
                            }
                            .then(
                                if (backdrop != null) {
                                    Modifier
                                        .textureBlur(
                                            backdrop = backdrop,
                                            shape = RoundedCornerShape(16.dp),
                                            blurRadius = 150f,
                                            noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                            colors = BlurDefaults.blurColors(
                                                blendColors = logoBlend,
                                            ),
                                            contentBlendMode = BlendMode.DstIn,
                                        )
                                } else {
                                    Modifier
                                },
                            ),
                        text = "AppRetention",
                        color = MiuixTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 35.sp,
                    )
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = 1 - versionCodeProgress
                                scaleX = 1 - (versionCodeProgress * 0.05f)
                                scaleY = 1 - (versionCodeProgress * 0.05f)
                            },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        text = "${BuildConfig.VERSION_NAME}_${BuildConfig.VERSION_CODE} | ${BuildConfig.BUILD_TYPE.replaceFirstChar { it.uppercase() }}",
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical(),
                    contentPadding = PaddingValues(bottom = paddingValues.calculateBottomPadding()),
                    overscrollEffect = null
                ) {
                    item(key = "logoSpacer") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(
                                    logoHeightDp + 200.dp,
                                ),
                            contentAlignment = Alignment.TopCenter,
                            content = { },
                        )
                    }

                    item("barPadding") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(pv.calculateTopPadding()),
                            contentAlignment = Alignment.TopCenter,
                            content = { },
                        )
                    }

                    item("about") {
                        Column(
                            modifier = Modifier.fillParentMaxHeight()
                        ) {
                            SmallTitle(text = stringResource(R.string.developer))
                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp)
                                    .cardBlur(backdrop = backdrop, cardBlend = cardBlend),
                                colors = CardDefaults.defaultColors(
                                    if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                                    Color.Transparent,
                                )
                            ) {
                                ArrowPreference(
                                    title = "焕晨HChen",
                                    summary = "Github | Developer",
                                    startAction = {
                                        Box(Modifier.padding(end = 8.dp)) {
                                            Icon(
                                                painter = painterResource(R.drawable.hchen),
                                                contentDescription = "HChen",
                                                tint = Color.Unspecified,
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                            )
                                        }
                                    },
                                    onClick = {
                                        openUrl(context, "https://github.com/HChenX")
                                    }
                                )
                                ArrowPreference(
                                    title = stringResource(R.string.contributors),
                                    onClick = {
                                        isContributorsDialogShowing = true
                                    }
                                )
                            }

                            SmallTitle(text = stringResource(R.string.discussion))
                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp)
                                    .cardBlur(backdrop = backdrop, cardBlend = cardBlend),
                                colors = CardDefaults.defaultColors(
                                    if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                                    Color.Transparent,
                                )
                            ) {
                                ArrowPreference(
                                    title = stringResource(R.string.telegram_group),
                                    onClick = {
                                        openUrl(context, "https://t.me/HChenX_Chat")
                                    }
                                )
                                ArrowPreference(
                                    title = stringResource(R.string.telegram_channel),
                                    onClick = {
                                        openUrl(context, "https://t.me/HChen_Module")
                                    }
                                )
                            }

                            SmallTitle(text = stringResource(R.string.others))
                            Card(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 12.dp)
                                    .cardBlur(backdrop = backdrop, cardBlend = cardBlend),
                                colors = CardDefaults.defaultColors(
                                    if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                                    Color.Transparent,
                                )
                            ) {
                                ArrowPreference(
                                    title = stringResource(R.string.project_url),
                                    onClick = {
                                        openUrl(context, "https://github.com/HChenX/SuperLyric")
                                    }
                                )
                            }
                        }
                    }
                }
            }

            WindowDialog(
                show = isContributorsDialogShowing,
                title = stringResource(R.string.contributors),
                onDismissRequest = {
                    isContributorsDialogShowing = false
                }
            ) {
                Card(
                    colors = CardColors(
                        color = MiuixTheme.colorScheme.surface,
                        contentColor = MiuixTheme.colorScheme.surfaceContainer,
                    )
                ) {
                    contributor.forEachIndexed { index, name ->
                        ArrowPreference(
                            title = name,
                            summary = "Github | Contributor",
                            startAction = {
                                Box(Modifier.padding(end = 8.dp)) {
                                    Icon(
                                        painter = painterResource(contributorIcon[index]),
                                        contentDescription = name,
                                        tint = Color.Unspecified,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                }
                            },
                            onClick = {
                                openUrl(context, contributorUri[index])
                            }
                        )
                    }
                }

                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .size(12.dp)
                )

                Row(horizontalArrangement = Arrangement.Absolute.SpaceBetween) {
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            isContributorsDialogShowing = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.cardBlur(
    backdrop: LayerBackdrop?,
    cardBlend: List<BlendColorEntry>
): Modifier = this
    .then(
        if (backdrop != null) {
            Modifier
                .textureBlur(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(16.dp),
                    blurRadius = 60f,
                    noiseCoefficient = BlurDefaults.NoiseCoefficient,
                    colors = BlurDefaults.blurColors(
                        blendColors = cardBlend,
                        brightness = 0f,
                        contrast = 1f,
                        saturation = 1f,
                    ),
                )
        } else {
            Modifier
        },
    )

private fun openUrl(context: Context, url: String) {
    try {
        val uri = url.toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}