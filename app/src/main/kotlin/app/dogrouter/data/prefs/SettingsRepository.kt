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
            cyclingSpeedKmh = prefs[CYCLING_SPEED_KMH] ?: AppSettings.DEFAULTS.cyclingSpeedKmh,
            homeAddress = prefs[HOME_ADDRESS] ?: AppSettings.DEFAULTS.homeAddress,
            homeLatitude = prefs[HOME_LATITUDE],
            homeLongitude = prefs[HOME_LONGITUDE],
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

    suspend fun setHomeAddress(address: String, latitude: Double?, longitude: Double?) {
        dataStore.edit { prefs ->
            prefs[HOME_ADDRESS] = address
            if (latitude != null) prefs[HOME_LATITUDE] = latitude else prefs.remove(HOME_LATITUDE)
            if (longitude != null) prefs[HOME_LONGITUDE] = longitude else prefs.remove(HOME_LONGITUDE)
        }
    }

    /** Overwrite every setting at once (used when importing a backup). */
    suspend fun replaceAll(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[BIKE_CAPACITY_KG] = settings.bikeCapacityKg
            prefs[STOP_BUFFER_MINUTES] = settings.stopBufferMinutes
            prefs[CYCLING_SPEED_KMH] = settings.cyclingSpeedKmh
            prefs[HOME_ADDRESS] = settings.homeAddress
            settings.homeLatitude?.let { prefs[HOME_LATITUDE] = it } ?: prefs.remove(HOME_LATITUDE)
            settings.homeLongitude?.let { prefs[HOME_LONGITUDE] = it } ?: prefs.remove(HOME_LONGITUDE)
        }
    }

    private companion object {
        val BIKE_CAPACITY_KG = floatPreferencesKey("bike_capacity_kg")
        val STOP_BUFFER_MINUTES = intPreferencesKey("stop_buffer_minutes")
        val HOME_ADDRESS = stringPreferencesKey("home_address")
        val HOME_LATITUDE = doublePreferencesKey("home_latitude")
        val HOME_LONGITUDE = doublePreferencesKey("home_longitude")
        val CYCLING_SPEED_KMH = floatPreferencesKey("cycling_speed_kmh")
    }
}
