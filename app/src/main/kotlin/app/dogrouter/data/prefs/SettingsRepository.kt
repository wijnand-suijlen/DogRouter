package app.dogrouter.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            bikeCapacityKg = prefs[BIKE_CAPACITY_KG] ?: AppSettings.DEFAULTS.bikeCapacityKg,
            stopBufferMinutes = prefs[STOP_BUFFER_MINUTES] ?: AppSettings.DEFAULTS.stopBufferMinutes,
            homeAddress = prefs[HOME_ADDRESS] ?: AppSettings.DEFAULTS.homeAddress,
            homeLatitude = prefs[HOME_LATITUDE],
            homeLongitude = prefs[HOME_LONGITUDE],
        )
    }

    suspend fun setBikeCapacity(kg: Float) {
        dataStore.edit { it[BIKE_CAPACITY_KG] = kg }
    }

    suspend fun setStopBufferMinutes(minutes: Int) {
        dataStore.edit { it[STOP_BUFFER_MINUTES] = minutes }
    }

    suspend fun setHomeAddress(address: String, latitude: Double?, longitude: Double?) {
        dataStore.edit { prefs ->
            prefs[HOME_ADDRESS] = address
            if (latitude != null) prefs[HOME_LATITUDE] = latitude else prefs.remove(HOME_LATITUDE)
            if (longitude != null) prefs[HOME_LONGITUDE] = longitude else prefs.remove(HOME_LONGITUDE)
        }
    }

    private companion object {
        val BIKE_CAPACITY_KG = floatPreferencesKey("bike_capacity_kg")
        val STOP_BUFFER_MINUTES = intPreferencesKey("stop_buffer_minutes")
        val HOME_ADDRESS = stringPreferencesKey("home_address")
        val HOME_LATITUDE = doublePreferencesKey("home_latitude")
        val HOME_LONGITUDE = doublePreferencesKey("home_longitude")
        // Legacy "cycling_speed_kmh" key intentionally left orphan; the
        // value is unused and DataStore tolerates stale entries.
    }
}
