package com.roomvibe.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.roomvibe.data.SensorRepository
import com.roomvibe.data.SyncState
import com.roomvibe.data.entity.Reading
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SensorDetailUiState(
    val sensorAddress: String = "",
    val sensorDisplayName: String = "",
    val readings: List<Reading> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val refreshProgress: String? = null,
    val refreshMessage: String? = null,
    val gattServices: List<Pair<String, List<String>>> = emptyList(),
    val isExploring: Boolean = false
) {
    val latest: Reading? get() = readings.lastOrNull()
    val oldestMs: Long? get() = readings.firstOrNull()?.timestampMs
    val newestMs: Long? get() = readings.lastOrNull()?.timestampMs
}

class SensorDetailViewModel(
    app: Application,
    savedState: SavedStateHandle
) : AndroidViewModel(app) {

    private val repo = SensorRepository(app)
    private val address: String = checkNotNull(savedState["address"])

    private val _uiState = MutableStateFlow(SensorDetailUiState(sensorAddress = address))
    val uiState: StateFlow<SensorDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.sensorsFlow()
                .map { list -> list.find { it.address == address } }
                .filterNotNull()
                .collect { sensor ->
                    _uiState.update { it.copy(sensorDisplayName = sensor.alias ?: sensor.name) }
                }
        }
        viewModelScope.launch {
            repo.allReadings(address).collect { readings ->
                _uiState.update { it.copy(readings = readings, isLoading = false) }
            }
        }
    }

    private var syncJob: Job? = null

    /** Connect to the sensor and download any new history. */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        syncJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, refreshMessage = null, refreshProgress = "Connecting…") }
            repo.syncSensor(address).collect { s ->
                when (s) {
                    is SyncState.Connecting -> _uiState.update { it.copy(refreshProgress = "Connecting…") }
                    is SyncState.Progress -> _uiState.update { it.copy(refreshProgress = s.step) }
                    is SyncState.Done -> _uiState.update {
                        it.copy(isRefreshing = false, refreshProgress = null,
                            refreshMessage = "Updated — ${s.newReadings} new reading(s)")
                    }
                    is SyncState.Error -> _uiState.update {
                        it.copy(isRefreshing = false, refreshProgress = null, refreshMessage = s.message)
                    }
                    is SyncState.Idle -> { /* no-op */ }
                }
            }
        }
    }

    fun cancelRefresh() {
        syncJob?.cancel()
        syncJob = null
        _uiState.update {
            it.copy(isRefreshing = false, refreshProgress = null, refreshMessage = "Sync canceled")
        }
    }

    fun showMessage(msg: String) = _uiState.update { it.copy(refreshMessage = msg) }

    fun clearRefreshMessage() = _uiState.update { it.copy(refreshMessage = null) }

    fun exploreGatt() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExploring = true) }
            val services = repo.exploreGatt(address)
            _uiState.update { it.copy(gattServices = services, isExploring = false) }
        }
    }
}
