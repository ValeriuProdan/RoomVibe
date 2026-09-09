package com.roomvibe.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.roomvibe.ui.chart.RangeStyle
import com.roomvibe.ui.chart.SeriesColoring

/**
 * Small persisted app-wide preferences (temperature unit, how zoomed-out charts
 * draw a bucket, compare-chart state).
 */
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("roomvibe_settings", Context.MODE_PRIVATE)

    private val _fahrenheit = MutableStateFlow(prefs.getBoolean(KEY_FAHRENHEIT, false))
    val fahrenheit: StateFlow<Boolean> = _fahrenheit.asStateFlow()

    fun setFahrenheit(value: Boolean) {
        prefs.edit().putBoolean(KEY_FAHRENHEIT, value).apply()
        _fahrenheit.value = value
    }

    // ── Charts ───────────────────────────────────────────────────────────────

    /**
     * What every chart draws for a day or month bucket. One app-wide choice: the
     * single-sensor and compare charts answering the same question differently is
     * what made them hard to read side by side.
     */
    private val _rangeStyle = MutableStateFlow(readRangeStyle())
    val rangeStyle: StateFlow<RangeStyle> = _rangeStyle.asStateFlow()

    fun setRangeStyle(value: RangeStyle) {
        prefs.edit().putString(KEY_RANGE_STYLE, value.name).apply()
        _rangeStyle.value = value
    }

    /** Falls back to the default for a missing value, and for one a downgrade wrote. */
    private fun readRangeStyle(): RangeStyle {
        val stored = prefs.getString(KEY_RANGE_STYLE, null) ?: return RangeStyle.MIN_MAX
        return RangeStyle.values().firstOrNull { it.name == stored } ?: RangeStyle.MIN_MAX
    }

    // ── Compare chart ────────────────────────────────────────────────────────

    /**
     * What a compare line's colour means. Compare-only: the single-sensor charts
     * have one line, so there is no identity for a hue to carry there.
     */
    private val _seriesColoring = MutableStateFlow(readSeriesColoring())
    val seriesColoring: StateFlow<SeriesColoring> = _seriesColoring.asStateFlow()

    fun setSeriesColoring(value: SeriesColoring) {
        prefs.edit().putString(KEY_SERIES_COLORING, value.name).apply()
        _seriesColoring.value = value
    }

    private fun readSeriesColoring(): SeriesColoring {
        val stored = prefs.getString(KEY_SERIES_COLORING, null) ?: return SeriesColoring.BY_VALUE
        return SeriesColoring.values().firstOrNull { it.name == stored } ?: SeriesColoring.BY_VALUE
    }

    /** Devices currently plotted on the compare chart, or null if never chosen. */
    private val _compareSelection = MutableStateFlow(
        if (prefs.contains(KEY_COMPARE_SELECTION))
            prefs.getStringSet(KEY_COMPARE_SELECTION, emptySet())!!.toSet()
        else null
    )
    val compareSelection: StateFlow<Set<String>?> = _compareSelection.asStateFlow()

    fun setCompareSelection(addresses: Set<String>) {
        prefs.edit().putStringSet(KEY_COMPARE_SELECTION, addresses).apply()
        _compareSelection.value = addresses
    }

    /** Address → colour slot on the compare chart. Assigned once, then stable. */
    private val _seriesSlots = MutableStateFlow(SeriesSlots.decode(prefs.getString(KEY_SERIES_SLOTS, null)))
    val seriesSlots: StateFlow<Map<String, Int>> = _seriesSlots.asStateFlow()

    /** Give every known device a slot, keeping the ones already handed out. */
    fun syncSeriesSlots(addresses: List<String>) {
        val next = SeriesSlots.assign(_seriesSlots.value, addresses)
        if (next == _seriesSlots.value) return
        prefs.edit().putString(KEY_SERIES_SLOTS, SeriesSlots.encode(next)).apply()
        _seriesSlots.value = next
    }

    companion object {
        private const val KEY_FAHRENHEIT = "fahrenheit"
        private const val KEY_RANGE_STYLE = "range_style"
        private const val KEY_COMPARE_SELECTION = "compare_selection"
        private const val KEY_SERIES_COLORING = "series_coloring"
        private const val KEY_SERIES_SLOTS = "series_slots"

        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}

/** °C → °F. */
fun celsiusToF(celsius: Float): Float = celsius * 9f / 5f + 32f

/** Format a Celsius value in the user's chosen unit, e.g. "23.1°C" or "73.6°F". */
fun formatTemp(celsius: Float, fahrenheit: Boolean, decimals: Int = 1): String {
    val v = if (fahrenheit) celsiusToF(celsius) else celsius
    val suffix = if (fahrenheit) "°F" else "°C"
    return "%.${decimals}f%s".format(v, suffix)
}
