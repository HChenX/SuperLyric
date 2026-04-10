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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.hchen.hooktool.utils.InvokeTool
import com.hchen.superlyric.BuildConfig
import com.hchen.superlyric.R
import com.hchen.superlyric.ui.Application
import com.hchen.superlyric.ui.data.LocalMiuixScrollBehavior
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun AboutLayout(
    isWideScreen: Boolean = false
) {
    val context = LocalContext.current
    val scrollBehavior = LocalMiuixScrollBehavior.current
    val icon = remember {
        context.packageManager.getApplicationIcon(context.packageName)
    }

    val contributor = remember {
        listOf(
            "ghhccghk",
            "YifePlayte",
            "YuKongA"
        )
    }
    val contributorUri = remember {
        listOf(
            "https://github.com/ghhccghk",
            "https://github.com/YifePlayte",
            "https://github.com/YuKongA"
        )
    }
    val contributorIcon = remember {
        listOf(
            R.drawable.ghhccghk,
            R.drawable.yifeplayte,
            R.drawable.yukonga
        )
    }

    val state by AnalogReceiver.receiverFlow.collectAsState()
    val registered by AnalogReceiver.registeredFlow.collectAsState()
    val paused by AnalogReceiver.pausedFlow.collectAsState()
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (!show) {
            if (SuperLyricHelper.isReceiverRegistered(AnalogReceiver.mReceiver)) {
                SuperLyricHelper.unregisterReceiver(AnalogReceiver.mReceiver)
                AnalogReceiver.registeredFlow.value = false
                AnalogReceiver.receiverFlow.value = ReceiverState()
            }
        }
    }

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
                .padding(top = it.calculateTopPadding()),
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
                            text = "${BuildConfig.VERSION_NAME}_${BuildConfig.VERSION_CODE} | ${BuildConfig.BUILD_TYPE.replaceFirstChar(Char::titlecase)}",
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
                }

                SmallTitle(text = stringResource(R.string.contributors))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
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

                SmallTitle(text = stringResource(R.string.debug))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    ArrowPreference(
                        title = "API 测试",
                        summary = "测试 SuperLyricApi 状态",
                        onClick = {
                            show = true
                        }
                    )
                    ArrowPreference(
                        title = stringResource(R.string.clear_dexkit_cache),
                        summary = stringResource(R.string.clear_dexkit_cache_summary),
                        onClick = {
                            var version = Application.getRemotePreferences().getInt("super_lyric_dexkit_cache_version", 0)
                            Application.getRemotePreferences().edit { putInt("super_lyric_dexkit_cache_version", ++version) }

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

        WindowBottomSheet(
            show = show,
            title = "API 测试",
            allowDismiss = false,
            startAction = {
                IconButton(
                    onClick = { show = false },
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
                    onClick = { show = false },
                ) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = "Confirm",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            },
            onDismissRequest = {
                show = false
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