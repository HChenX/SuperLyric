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
package com.hchen.superlyric.ui.screen.settings

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.edit
import com.hchen.hooktool.utils.PrefsTool
import com.hchen.superlyric.R
import com.hchen.superlyric.data.PrefsKey
import com.hchen.superlyric.ui.Application
import com.hchen.superlyric.utils.LauncherIconController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HomeSettingsState(
    val logLevel: Int,
    val launcherHidden: Boolean,
)

object HomeSettingsController {

    fun launcherLabelRes(hidden: Boolean): Int =
        if (hidden) R.string.show_icon else R.string.hide_icon

    suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        val remotePrefs = Application.getRemotePreferences() ?: return@withContext false
        val version = remotePrefs.getInt(PrefsKey.DEXKIT_CACHE_VERSION, 0) + 1
        // 远端 prefs 的 apply 不返回结果；commit=true 的 KTX 扩展写完后回读确认。
        // 该调用可能走 Binder/磁盘，放在 IO 上，UI 由调用方协程更新。
        runCatching {
            remotePrefs.edit(commit = true) {
                putInt(PrefsKey.DEXKIT_CACHE_VERSION, version)
            }
            remotePrefs.getInt(PrefsKey.DEXKIT_CACHE_VERSION, 0) == version
        }.getOrDefault(false)
    }

    fun setLauncherHidden(context: Context, hidden: Boolean): Boolean {
        if (!LauncherIconController.setHidden(context, hidden)) return false
        if (hidden) finishAllTasks(context)
        return true
    }

    fun setLogLevel(context: Context, index: Int) {
        PrefsTool.prefs(context).edit { putInt(PrefsKey.LOG_LEVEL, index) }
    }

    private fun finishAllTasks(context: Context) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager?.appTasks?.forEach { it.finishAndRemoveTask() }
    }
}
