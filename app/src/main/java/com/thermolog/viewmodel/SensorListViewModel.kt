package com.thermolog.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thermolog.ble.FoundDevice
import com.thermolog.data.SensorRepository
import com.thermolog.data.SyncState
import com.thermolog.data.entity.Sensor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface TempProbe {
    data object Loading : TempProbe
    data class Value(val celsius: Float) : TempProbe
    data object Failed : TempProbe
}

data class SensorListUiState(
    val sensors: List<Sensor> = emptyList(),
    val scanResults: List<FoundDevice> = emptyList(),
    val isScanning: Boolean = false,
    val syncStates: Map<String, SyncState> = emptyMap(),
    val liveTemps: Map<String, TempProbe> = emptyMap(),
    val errorMessage: String? = null
)

class SensorListViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SensorRepository(app)

    private val _uiState = MutableStateFlow(SensorListUiState())
    val uiState: StateFlow<SensorListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.sensorsFlow().collect { sensors ->
                _uiState.update { it.copy(sensors = sensors) }
            }
        }
    }

    // Serialise temperature probes so we never open two BLE connections at once
    private val probeMutex = Mutex()

    fun scanForDevices() {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanResults = emptyList(), liveTemps = emptyMap()) }
            repo.scanForDevices()
                .catch { e -> _uiState.update { it.copy(errorMessage = e.message) } }
                .onCompletion { _uiState.update { it.copy(isScanning = false) } }
                .collect { device ->
                    _uiState.update { state ->
                        if (state.scanResults.none { it.address == device.address })
                            state.copy(scanResults = state.scanResults + device)
                        else state
                    }
                    if (device.isLikelySensor) probeTemp(device.address)
                }
        }
    }

    /** Connect briefly and read the live temperature so the user can tell sensors apart. */
    private fun probeTemp(address: String) {
        if (_uiState.value.liveTemps.containsKey(address)) return
        _uiState.update { it.copy(liveTemps = it.liveTemps + (address to TempProbe.Loading)) }
        viewModelScope.launch {
            val temp = probeMutex.withLock { repo.readCurrentTemp(address) }
            val result = temp?.let { TempProbe.Value(it) } ?: TempProbe.Failed
            _uiState.update { it.copy(liveTemps = it.liveTemps + (address to result)) }
        }
    }

    fun addSensor(device: FoundDevice) {
        viewModelScope.launch {
            repo.addSensor(device)
            _uiState.update { it.copy(scanResults = emptyList()) }
        }
    }

    fun removeSensor(sensor: Sensor) {
        viewModelScope.launch { repo.removeSensor(sensor) }
    }

    fun renameSensor(address: String, alias: String?) {
        viewModelScope.launch { repo.renameSensor(address, alias) }
    }

    fun syncSensor(address: String) {
        if (_uiState.value.syncStates[address] is SyncState.Connecting ||
            _uiState.value.syncStates[address] is SyncState.Progress) return

        viewModelScope.launch {
            repo.syncSensor(address).collect { state ->
                _uiState.update { it.copy(syncStates = it.syncStates + (address to state)) }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
}
