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
    val backupBusy: Boolean = false,
    val infoMessage: String? = null,
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

    private val syncJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun syncSensor(address: String) {
        if (_uiState.value.syncStates[address] is SyncState.Connecting ||
            _uiState.value.syncStates[address] is SyncState.Progress) return

        syncJobs[address] = viewModelScope.launch {
            repo.syncSensor(address).collect { state ->
                _uiState.update { it.copy(syncStates = it.syncStates + (address to state)) }
            }
        }
    }

    fun cancelSync(address: String) {
        syncJobs.remove(address)?.cancel()
        _uiState.update { it.copy(syncStates = it.syncStates - address) }
    }

    fun backupTo(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true) }
            try {
                val n = repo.backupTo(uri)
                _uiState.update { it.copy(infoMessage = "Backed up $n readings. Choose Google Drive in the save dialog to store it there.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Backup failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(backupBusy = false) }
            }
        }
    }

    fun restoreFrom(uri: android.net.Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true) }
            try {
                val s = repo.restoreFrom(uri)
                _uiState.update {
                    it.copy(infoMessage = "Restored: ${s.readingsAdded} new readings added" +
                        (if (s.sensorsAdded > 0) ", ${s.sensorsAdded} new sensor(s)" else "") +
                        " (${s.readingsTotal} in backup).")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Restore failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(backupBusy = false) }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
    fun clearInfo() = _uiState.update { it.copy(infoMessage = null) }
}
