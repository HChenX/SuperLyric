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
package com.hchen.superlyric.ui.viewmodel

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.compose.ui.util.fastFirstOrNull
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hchen.hooktool.data.AppData
import com.hchen.superlyric.data.PrefsKey
import com.hchen.superlyric.data.apps.ApiAppData
import com.hchen.superlyric.ui.Application
import com.hchen.superlyric.utils.PackageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainViewModel(
    private val addPrefsReadyListener: (Consumer<SharedPreferences>) -> Unit,
    private val removePrefsReadyListener: (Consumer<SharedPreferences>) -> Unit,
    private val addAppLoadedListener: (Runnable) -> Unit,
    private val removeAppLoadedListener: (Runnable) -> Unit,
    private val reloadApps: () -> CompletableFuture<Void>
) : ViewModel() {
    @Volatile
    private var prefs: SharedPreferences? = null

    private val prefsReadyListener = Consumer<SharedPreferences> { sharedPreferences ->
        prefs = sharedPreferences
        loadPrefs(sharedPreferences)
    }
    private val appLoadedListener = Runnable { loadApps() }

    private val _logLevel = MutableStateFlow(0)
    val logLevel = _logLevel.asStateFlow()

    private val _hookApps = MutableStateFlow<List<AppData>>(emptyList())
    val hookApps: StateFlow<List<AppData>> = _hookApps.asStateFlow()

    private val _apiApps = MutableStateFlow<List<ApiAppData>>(emptyList())
    val apiApps: StateFlow<List<ApiAppData>> = _apiApps.asStateFlow()

    private val _currentApp = MutableStateFlow(AppData())
    val currentApp: StateFlow<AppData> = _currentApp.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        addPrefsReadyListener(prefsReadyListener)
        addAppLoadedListener(appLoadedListener)
        loadApps()
        Application.getRemotePreferences()?.let { sp ->
            prefs = sp
            loadPrefs(sp)
        }
    }

    private fun loadApps() {
        _hookApps.value = PackageLoader.getMediaApps().toList()
        _apiApps.value = PackageLoader.getMediaApiApps().toList()
    }

    private fun loadPrefs(sharedPreferences: SharedPreferences) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val level = sharedPreferences.getInt(PrefsKey.LOG_LEVEL, 0)
                _logLevel.value = level
            }
        }
    }

    fun handleAction(action: MainUiAction) {
        when (action) {
            is MainUiAction.UpdateLogLevel -> {
                val currentPrefs = Application.getRemotePreferences() ?: prefs
                if (currentPrefs != null) {
                    runCatching {
                        currentPrefs.edit { putInt(PrefsKey.LOG_LEVEL, action.value) }
                    }
                    _logLevel.value = action.value
                }
            }

            is MainUiAction.Refresh -> refreshData()
            is MainUiAction.CurrentApp -> _currentApp.value = action.appData
        }
    }

    private fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            try {
                runCatching {
                    reloadApps().awaitCompletion()
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("MainViewModel", "Failed to reload apps", e)
                }
                loadApps()

                delay(300)
                val targetPkg = _currentApp.value.packageName
                if (!targetPkg.isNullOrEmpty()) {
                    _currentApp.value = _hookApps.value.fastFirstOrNull { it.packageName == targetPkg }
                        ?: _apiApps.value.fastFirstOrNull { it.packageName == targetPkg }
                                ?: AppData()
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    @SuppressLint("EmptySuperCall")
    override fun onCleared() {
        super.onCleared()
        removePrefsReadyListener(prefsReadyListener)
        removeAppLoadedListener(appLoadedListener)
        prefs = null
    }

    private suspend fun CompletableFuture<Void>.awaitCompletion() {
        suspendCancellableCoroutine { continuation ->
            whenComplete { _, throwable ->
                if (throwable == null) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(throwable)
                }
            }
            continuation.invokeOnCancellation { cancel(true) }
        }
    }
}

sealed class MainUiAction {
    data object Refresh : MainUiAction()
    data class CurrentApp(val appData: AppData) : MainUiAction()
    data class UpdateLogLevel(val value: Int) : MainUiAction()
}

class MainViewModelFactory(
    private val addPrefsReadyListener: (Consumer<SharedPreferences>) -> Unit,
    private val removePrefsReadyListener: (Consumer<SharedPreferences>) -> Unit,
    private val addAppLoadedListener: (Runnable) -> Unit,
    private val removeAppLoadedListener: (Runnable) -> Unit,
    private val reloadApps: () -> CompletableFuture<Void>
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(
            addPrefsReadyListener,
            removePrefsReadyListener,
            addAppLoadedListener,
            removeAppLoadedListener,
            reloadApps
        ) as T
    }
}
