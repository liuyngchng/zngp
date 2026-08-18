package com.voicenote.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicenote.app.core.di.AppSettings
import com.voicenote.app.core.di.SettingsDataStore
import com.voicenote.app.core.network.ServerClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TestResult(
    val name: String,
    val success: Boolean,
    val message: String
)

data class SettingsUiState(
    val isLoading: Boolean = true,
    val serverUrl: String = "http://192.168.1.1:8080",
    val serverApiKey: String = "",
    val isTesting: Boolean = false,
    val testResults: List<TestResult> = emptyList(),
    val showResults: Boolean = false,
    val saveCount: Int = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.settingsFlow.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    serverUrl = settings.serverUrl,
                    serverApiKey = settings.serverApiKey
                )
            }
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(serverUrl = url)
    }

    fun updateServerApiKey(apiKey: String) {
        _uiState.value = _uiState.value.copy(serverApiKey = apiKey)
    }

    fun save() {
        viewModelScope.launch {
            settingsDataStore.updateServerConfig(
                url = _uiState.value.serverUrl,
                apiKey = _uiState.value.serverApiKey
            )
            _uiState.value = _uiState.value.copy(saveCount = _uiState.value.saveCount + 1)
        }
    }

    fun buildSaveSummary(): String {
        val s = _uiState.value
        return "已保存 · 服务器: ${s.serverUrl}"
    }

    fun testConnection() {
        val state = _uiState.value
        _uiState.value = state.copy(isTesting = true, testResults = emptyList(), showResults = false)

        viewModelScope.launch {
            val results = mutableListOf<TestResult>()
            val client = ServerClient(state.serverUrl, state.serverApiKey)

            val result = client.testConnection()
            result.onSuccess { msg ->
                results.add(TestResult("服务器连接", true, msg))
            }.onFailure { e ->
                results.add(TestResult("服务器连接", false, e.message ?: "连接失败"))
            }

            _uiState.value = _uiState.value.copy(
                isTesting = false,
                testResults = results,
                showResults = true
            )
        }
    }

    fun dismissResults() {
        _uiState.value = _uiState.value.copy(showResults = false, testResults = emptyList())
    }
}