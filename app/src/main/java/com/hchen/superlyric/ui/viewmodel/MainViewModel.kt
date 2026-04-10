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
package com.hchen.superlyric.ui.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hchen.hooktool.data.AppData
import com.hchen.superlyric.data.ApiAppData
import com.hchen.superlyric.utils.PackageUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private lateinit var prefs: SharedPreferences

    // ---------- 原始数据 ----------

    // 内置应用列表
    private val _hookApps = MutableStateFlow<List<AppData>>(emptyList())
    val hookApps: StateFlow<List<AppData>> = _hookApps.asStateFlow()

    private val _apiApps = MutableStateFlow<List<ApiAppData>>(emptyList())
    val apiApps: StateFlow<List<ApiAppData>> = _apiApps.asStateFlow()

    private val _currentApp = MutableStateFlow(AppData())
    val currentApp: StateFlow<AppData> = _currentApp.asStateFlow()

    // 刷新相关
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // ---------- 初始化 ----------
    init {
        // Application.addPrefsReadyListener {
        //     prefs = it
        //     loadData()
        // }
        PackageUtils.addAppLoadedListener {
            loadData()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            _hookApps.value = PackageUtils.getMediaAppHookList().toList()
            _apiApps.value = PackageUtils.getMediaAppApiList().toList()
        }
    }

    // ---------- 事件处理 ----------
    fun handleAction(action: MainUiAction) {
        when (action) {
            is MainUiAction.Refresh -> {
                refreshData()
            }

            is MainUiAction.Searching -> {
                _isSearching.value = action.isSearching
            }

            is MainUiAction.CurrentApp -> {
                _currentApp.value = action.appData
            }
        }
    }

    private fun refreshData() {
        viewModelScope.launch {
            _isRefreshing.value = true
            delay(500)

            loadData()

            _isRefreshing.value = false
        }
    }
}

sealed class MainUiAction {
    data object Refresh : MainUiAction()
    data class Searching(val isSearching: Boolean) : MainUiAction()
    data class CurrentApp(val appData: AppData) : MainUiAction()
}

class MainViewModelFactory() : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel() as T
    }
}