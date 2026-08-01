package com.roomvibe.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomvibe.data.AppSettings
import com.roomvibe.data.SensorRepository
import com.roomvibe.data.entity.Reading
import com.roomvibe.data.entity.Sensor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** How many devices are pre-selected the very first time the compare screen opens. */
private const val DEFAULT_SELECTION_SIZE = 4

/**
 * Marked immutable so Compose can skip re-running the screen's chip list and
 * readout when only the chart's viewport moved — its collections are replaced
 * wholesale, never mutated in place.
 */
@Immutable
data class CompareUiState(
    val sensors: List<Sensor> = emptyList(),
    val selected: Set<String> = emptySet(),
    /** Address → colour slot, stable for the life of the device. */
    val slots: Map<String, Int> = emptyMap(),
    val readingsBySensor: Map<String, List<Reading>> = emptyMap(),
    val isLoading: Boolean = true
) {
    /** Oldest/newest reading across the selected devices — the pannable range. */
    val oldestMs: Long? get() = readingsBySensor.values.mapNotNull { it.firstOrNull()?.timestampMs }.minOrNull()
    val newestMs: Long? get() = readingsBySensor.values.mapNotNull { it.lastOrNull()?.timestampMs }.maxOrNull()

    fun displayName(address: String): String =
        sensors.find { it.address == address }?.let { it.alias ?: it.name } ?: address
}

@OptIn(ExperimentalCoroutinesApi::class)
class CompareViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SensorRepository(app)
    private val settings = AppSettings.get(app)

    private val _uiState = MutableStateFlow(CompareUiState())
    val uiState: StateFlow<CompareUiState> = _uiState.asStateFlow()

    init {
        // Every device gets a colour slot as soon as we know it exists, so the
        // colour is the same whether or not it's currently plotted.
        viewModelScope.launch {
            repo.sensorsFlow().collect { sensors ->
                settings.syncSeriesSlots(sensors.map { it.address })
                _uiState.update { it.copy(sensors = sensors) }
                seedSelectionIfUnset(sensors)
            }
        }
        viewModelScope.launch {
            settings.seriesSlots.collect { slots -> _uiState.update { it.copy(slots = slots) } }
        }
        viewModelScope.launch {
            settings.compareSelection.collect { sel ->
                _uiState.update { it.copy(selected = sel ?: emptySet()) }
            }
        }
        // Reload whenever the selection changes; only selected devices are queried.
        // A null selection means "not chosen yet" — stay loading rather than
        // flashing the empty state before the first-run seed lands.
        viewModelScope.launch {
            settings.compareSelection
                .flatMapLatest { sel ->
                    if (sel == null) flowOf(null) else repo.readingsBySensor(sel)
                }
                .collect { byAddress ->
                    if (byAddress == null) return@collect
                    _uiState.update { it.copy(readingsBySensor = byAddress, isLoading = false) }
                }
        }
    }

    /** First run: plot the first few devices so the screen isn't blank. */
    private fun seedSelectionIfUnset(sensors: List<Sensor>) {
        if (settings.compareSelection.value != null || sensors.isEmpty()) return
        settings.setCompareSelection(sensors.take(DEFAULT_SELECTION_SIZE).map { it.address }.toSet())
    }

    fun toggle(address: String) {
        val current = _uiState.value.selected
        settings.setCompareSelection(
            if (address in current) current - address else current + address
        )
    }

    fun selectAll() = settings.setCompareSelection(_uiState.value.sensors.map { it.address }.toSet())

    fun selectNone() = settings.setCompareSelection(emptySet())
}
