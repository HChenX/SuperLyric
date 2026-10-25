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

data class HomeSettingsState(
    val logLevel: Int,
    val launcherHidden: Boolean,
)

object HomeSettingsController {

    fun launcherLabelRes(hidden: Boolean): Int =
        if (hidden) R.string.show_icon else R.string.hide_icon

    fun clearCache(context: Context): Boolean {
        val remotePrefs = Application.getRemotePreferences() ?: return false
        var version = remotePrefs.getInt("super_lyric_dexkit_cache_version", 0)
        remotePrefs.edit { putInt("super_lyric_dexkit_cache_version", ++version) }
        return true
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
