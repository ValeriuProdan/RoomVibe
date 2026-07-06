package com.roomvibe.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small persisted app-wide preferences (currently just the temperature unit). */
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("roomvibe_settings", Context.MODE_PRIVATE)

    private val _fahrenheit = MutableStateFlow(prefs.getBoolean(KEY_FAHRENHEIT, false))
    val fahrenheit: StateFlow<Boolean> = _fahrenheit.asStateFlow()

    fun setFahrenheit(value: Boolean) {
        prefs.edit().putBoolean(KEY_FAHRENHEIT, value).apply()
        _fahrenheit.value = value
    }

    companion object {
        private const val KEY_FAHRENHEIT = "fahrenheit"

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
