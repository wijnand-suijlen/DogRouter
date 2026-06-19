package app.dogrouter.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            cyclingSpeedKmh = prefs[CYCLING_SPEED_KMH] ?: AppSettings.DEFAULTS.cyclingSpeedKmh,
            bikeCapacityKg = prefs[BIKE_CAPACITY_KG] ?: AppSettings.DEFAULTS.bikeCapacityKg,
            stopBufferMinutes = prefs[STOP_BUFFER_MINUTES] ?: AppSettings.DEFAULTS.stopBufferMinutes,
        )
    }

    suspend fun setCyclingSpeed(kmh: Float) {
        dataStore.edit { it[CYCLING_SPEED_KMH] = kmh }
    }

    suspend fun setBikeCapacity(kg: Float) {
        dataStore.edit { it[BIKE_CAPACITY_KG] = kg }
    }

    suspend fun setStopBufferMinutes(minutes: Int) {
        dataStore.edit { it[STOP_BUFFER_MINUTES] = minutes }
    }

    private companion object {
        val CYCLING_SPEED_KMH = floatPreferencesKey("cycling_speed_kmh")
        val BIKE_CAPACITY_KG = floatPreferencesKey("bike_capacity_kg")
        val STOP_BUFFER_MINUTES = intPreferencesKey("stop_buffer_minutes")
    }
}
