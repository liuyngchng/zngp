package com.voicenote.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicenote.app.core.di.SettingsDataStore
import com.voicenote.app.domain.model.VoiceRecord
import com.voicenote.app.domain.repository.VoiceRecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class HomeUiState(
    val todayRecordCount: Int = 0,
    val totalRecordCount: Int = 0,
    val recentRecords: List<VoiceRecord> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recordRepository: VoiceRecordRepository,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            recordRepository.getAllRecordsFlow().collect { records ->
                val todayStart = Instant.now().atZone(ZoneId.systemDefault())
                    .truncatedTo(ChronoUnit.DAYS).toInstant()
                val todayRecords = records.filter { it.startTime >= todayStart }
                _uiState.value = _uiState.value.copy(
                    todayRecordCount = todayRecords.size,
                    totalRecordCount = records.size,
                    recentRecords = records.take(2),
                    isLoading = false
                )
            }
        }
    }
}