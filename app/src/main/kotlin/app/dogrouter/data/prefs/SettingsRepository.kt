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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalTime

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            bikeCapacityKg = prefs[BIKE_CAPACITY_KG] ?: AppSettings.DEFAULTS.bikeCapacityKg,
            stopBufferMinutes = prefs[STOP_BUFFER_MINUTES] ?: AppSettings.DEFAULTS.stopBufferMinutes,
            cyclingSpeedKmh = prefs[CYCLING_SPEED_KMH] ?: AppSettings.DEFAULTS.cyclingSpeedKmh,
            walkingSpeedKmh = prefs[WALKING_SPEED_KMH] ?: AppSettings.DEFAULTS.walkingSpeedKmh,
            bikeOverheadMinutes = prefs[BIKE_OVERHEAD_MINUTES] ?: AppSettings.DEFAULTS.bikeOverheadMinutes,
            homeAddress = prefs[HOME_ADDRESS] ?: AppSettings.DEFAULTS.homeAddress,
            homeLatitude = prefs[HOME_LATITUDE],
            homeLongitude = prefs[HOME_LONGITUDE],
            breakWindowStart = prefs[BREAK_WINDOW_START]?.let { LocalTime.ofSecondOfDay(it * 60L) }
                ?: AppSettings.DEFAULTS.breakWindowStart,
            breakWindowEnd = prefs[BREAK_WINDOW_END]?.let { LocalTime.ofSecondOfDay(it * 60L) }
                ?: AppSettings.DEFAULTS.breakWindowEnd,
            breakDurationMinutes = prefs[BREAK_DURATION_MINUTES] ?: AppSettings.DEFAULTS.breakDurationMinutes,
            breakLocations = prefs[BREAK_LOCATIONS]?.let {
                runCatching { json.decodeFromString<List<BreakLocation>>(it) }.getOrNull()
            } ?: AppSettings.DEFAULTS.breakLocations,
            homeLunchMinFreeMinutes = prefs[HOME_LUNCH_MIN_FREE_MINUTES]
                ?: AppSettings.DEFAULTS.homeLunchMinFreeMinutes,
        )
    }

    suspend fun setHomeLunchMinFreeMinutes(minutes: Int) {
        dataStore.edit { it[HOME_LUNCH_MIN_FREE_MINUTES] = minutes.coerceAtLeast(0) }
    }

    suspend fun setBreakWindow(start: LocalTime, end: LocalTime) {
        dataStore.edit {
            it[BREAK_WINDOW_START] = start.toSecondOfDay() / 60
            it[BREAK_WINDOW_END] = end.toSecondOfDay() / 60
        }
    }

    suspend fun setBreakDuration(minutes: Int) {
        dataStore.edit { it[BREAK_DURATION_MINUTES] = minutes }
    }

    suspend fun setBreakLocations(locations: List<BreakLocation>) {
        dataStore.edit { it[BREAK_LOCATIONS] = json.encodeToString(locations) }
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

    suspend fun setWalkingSpeed(kmh: Float) {
        dataStore.edit { it[WALKING_SPEED_KMH] = kmh }
    }

    suspend fun setBikeOverheadMinutes(minutes: Int) {
        dataStore.edit { it[BIKE_OVERHEAD_MINUTES] = minutes }
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
            prefs[WALKING_SPEED_KMH] = settings.walkingSpeedKmh
            prefs[BIKE_OVERHEAD_MINUTES] = settings.bikeOverheadMinutes
            prefs[HOME_ADDRESS] = settings.homeAddress
            settings.homeLatitude?.let { prefs[HOME_LATITUDE] = it } ?: prefs.remove(HOME_LATITUDE)
            settings.homeLongitude?.let { prefs[HOME_LONGITUDE] = it } ?: prefs.remove(HOME_LONGITUDE)
            prefs[BREAK_WINDOW_START] = settings.breakWindowStart.toSecondOfDay() / 60
            prefs[BREAK_WINDOW_END] = settings.breakWindowEnd.toSecondOfDay() / 60
            prefs[BREAK_DURATION_MINUTES] = settings.breakDurationMinutes
            prefs[BREAK_LOCATIONS] = json.encodeToString(settings.breakLocations)
            prefs[HOME_LUNCH_MIN_FREE_MINUTES] = settings.homeLunchMinFreeMinutes
        }
    }

    private companion object {
        val BIKE_CAPACITY_KG = floatPreferencesKey("bike_capacity_kg")
        val STOP_BUFFER_MINUTES = intPreferencesKey("stop_buffer_minutes")
        val WALKING_SPEED_KMH = floatPreferencesKey("walking_speed_kmh")
        val BIKE_OVERHEAD_MINUTES = intPreferencesKey("bike_overhead_minutes")
        val HOME_ADDRESS = stringPreferencesKey("home_address")
        val HOME_LATITUDE = doublePreferencesKey("home_latitude")
        val HOME_LONGITUDE = doublePreferencesKey("home_longitude")
        val CYCLING_SPEED_KMH = floatPreferencesKey("cycling_speed_kmh")
        val BREAK_WINDOW_START = intPreferencesKey("break_window_start_min")
        val BREAK_WINDOW_END = intPreferencesKey("break_window_end_min")
        val BREAK_DURATION_MINUTES = intPreferencesKey("break_duration_minutes")
        val BREAK_LOCATIONS = stringPreferencesKey("break_locations_json")
        val HOME_LUNCH_MIN_FREE_MINUTES = intPreferencesKey("home_lunch_min_free_minutes")
    }
}
