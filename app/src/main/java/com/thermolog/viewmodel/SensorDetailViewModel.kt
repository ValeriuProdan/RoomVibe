package com.thermolog.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.thermolog.data.SensorRepository
import com.thermolog.data.entity.Reading
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SensorDetailUiState(
    val sensorAddress: String = "",
    val sensorDisplayName: String = "",
    val readings: List<Reading> = emptyList(),
    val isLoading: Boolean = true,
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

    fun exploreGatt() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExploring = true) }
            val services = repo.exploreGatt(address)
            _uiState.update { it.copy(gattServices = services, isExploring = false) }
        }
    }
}
