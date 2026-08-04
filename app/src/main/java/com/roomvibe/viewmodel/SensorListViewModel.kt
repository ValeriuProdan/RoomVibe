package com.roomvibe.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomvibe.ble.FoundDevice
import com.roomvibe.data.SensorRepository
import com.roomvibe.data.SyncState
import com.roomvibe.data.entity.Sensor
import kotlinx.coroutines.Job
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
    /** A "sync all" run is in progress; [syncAllDone] of [syncAllTotal] finished. */
    val isSyncingAll: Boolean = false,
    val syncAllDone: Int = 0,
    val syncAllTotal: Int = 0,
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

    /**
     * One BLE conversation at a time, across probes and syncs alike.
     *
     * These sensors accept a single connection — that's why the Xiaomi app has to
     * be force-stopped before this one can reach them — and Android's stack is
     * unreliable with concurrent GATT sessions. Anything that opens a connection
     * takes this first, so a queued sensor waits rather than fighting for the radio.
     */
    private val bleMutex = Mutex()

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
            val temp = bleMutex.withLock { repo.readCurrentTemp(address) }
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
    private var syncAllJob: Job? = null

    private fun isBusy(address: String): Boolean =
        _uiState.value.syncStates[address].let {
            it is SyncState.Connecting || it is SyncState.Progress
        }

    fun syncSensor(address: String) {
        if (isBusy(address)) return
        syncJobs[address] = viewModelScope.launch { runSync(address) }
    }

    /**
     * Sync one sensor, waiting for the radio if something else is using it.
     *
     * The wait is shown on the card rather than hidden, so a queued sensor reads
     * as pending instead of looking like nothing happened.
     */
    private suspend fun runSync(address: String) {
        _uiState.update { it.copy(syncStates = it.syncStates + (address to SyncState.Progress("Waiting…"))) }
        bleMutex.withLock {
            repo.syncSensor(address).collect { state ->
                _uiState.update { it.copy(syncStates = it.syncStates + (address to state)) }
            }
        }
    }

    /**
     * Sync every sensor, one after another.
     *
     * Sequential by necessity, not preference — see [bleMutex]. A sensor that
     * fails doesn't stop the rest: the repository reports errors as a state rather
     * than throwing, so the loop moves on to the next one.
     */
    fun syncAll() {
        if (_uiState.value.isSyncingAll) return
        val addresses = _uiState.value.sensors.map { it.address }
        if (addresses.isEmpty()) return

        syncAllJob = viewModelScope.launch {
            // Mark the whole set pending up front. Besides reading correctly, it
            // makes every card busy, so a tap on one sensor's "Sync now" can't
            // queue a second sync of a sensor this run is already going to reach.
            _uiState.update { s ->
                s.copy(
                    isSyncingAll = true, syncAllDone = 0, syncAllTotal = addresses.size,
                    syncStates = s.syncStates + addresses.associateWith { SyncState.Progress("Waiting…") }
                )
            }
            try {
                addresses.forEach { address ->
                    runSync(address)
                    _uiState.update { it.copy(syncAllDone = it.syncAllDone + 1) }
                }
            } finally {
                // Runs on cancellation too, so the button can't be left spinning.
                _uiState.update { it.copy(isSyncingAll = false) }
            }
        }
    }

    fun cancelSyncAll() {
        syncAllJob?.cancel()
        syncAllJob = null
        _uiState.update { s ->
            s.copy(
                isSyncingAll = false,
                // Keep what finished; drop the sensors left mid-flight or queued.
                syncStates = s.syncStates.filterValues { it is SyncState.Done || it is SyncState.Error }
            )
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
