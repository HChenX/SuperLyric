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

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.hchen.superlyric.BuildConfig
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.Application
import com.hchen.superlyric.ui.data.LocalMiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.DialogDefaults
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun AboutLayout(
    isWideScreen: Boolean = false
) {
    val context = LocalContext.current
    val scrollBehavior = LocalMiuixScrollBehavior.current
    val icon = remember {
        context.packageManager.getApplicationIcon(context.packageName)
    }

    val contributor = listOf(
        "ghhccghk",
        "YifePlayte",
        "YuKongA"
    )
    val contributorUri = listOf(
        "https://github.com/ghhccghk",
        "https://github.com/YifePlayte",
        "https://github.com/YuKongA"
    )
    val contributorIcon = listOf(
        R.drawable.ghhccghk,
        R.drawable.yifeplayte,
        R.drawable.yukonga
    )

    Scaffold(
        topBar = {
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
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(top = it.calculateTopPadding(), bottom = it.calculateBottomPadding()),
            contentPadding = PaddingValues(bottom = 12.dp),
            overscrollEffect = null
        ) {
            item(key = "logo") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp, bottom = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.height(192.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = BitmapPainter(icon.toBitmap().asImageBitmap()),
                            contentDescription = "App Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .padding(bottom = 6.dp)
                        )

                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 3.dp),
                            text = stringResource(R.string.app_name),
                            fontSize = MiuixTheme.textStyles.title4.fontSize,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = DialogDefaults.titleColor(),
                        )

                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            text = "${BuildConfig.VERSION_NAME}_${BuildConfig.VERSION_CODE}",
                            fontSize = MiuixTheme.textStyles.body1.fontSize,
                            textAlign = TextAlign.Center,
                            color = DialogDefaults.summaryColor(),
                        )
                    }
                }
            }

            item("item") {
                SmallTitle(text = stringResource(R.string.developer))

                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    SuperArrow(
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
                }

                SmallTitle(text = stringResource(R.string.contributors))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    contributor.forEachIndexed { index, name ->
                        SuperArrow(
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

                SmallTitle(text = stringResource(R.string.debug))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    SuperArrow(
                        title = stringResource(R.string.clear_dexkit_cache),
                        summary = stringResource(R.string.clear_dexkit_cache_summary),
                        onClick = {
                            var version = Application.getRemotePreferences().getInt("super_lyric_dexkit_cache_version", 0)
                            version = if (version > 0) 0 else 1
                            Application.getRemotePreferences().edit { putInt("super_lyric_dexkit_cache_version", version) }

                            Toast.makeText(context, context.getString(R.string.cleared), Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                SmallTitle(text = stringResource(R.string.discussion))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    SuperArrow(
                        title = stringResource(R.string.telegram_group),
                        onClick = {
                            openUrl(context, "https://t.me/HChenX_Chat")
                        }
                    )
                    SuperArrow(
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
                ) {
                    SuperArrow(
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

fun openUrl(context: Context, url: String) {
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