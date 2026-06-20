package app.dogrouter.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.BreakLocation
import app.dogrouter.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Backs the Planning screen: the mid-day break window, duration and the
 * list of break locations (all in [AppSettings] via [SettingsRepository]).
 * Later this screen also hosts one-off appointments and bike-shop days.
 */
class PlanningViewModel(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setBreakWindow(start: LocalTime, end: LocalTime) {
        viewModelScope.launch { settingsRepo.setBreakWindow(start, end) }
    }

    fun setBreakDuration(minutes: Int) {
        viewModelScope.launch { settingsRepo.setBreakDuration(minutes.coerceIn(5, 180)) }
    }

    fun addLocation(label: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val current = settingsRepo.settings.first().breakLocations
            val location = BreakLocation(label, latitude, longitude)
            if (current.none { it.latitude == latitude && it.longitude == longitude }) {
                settingsRepo.setBreakLocations(current + location)
            }
        }
    }

    fun removeLocation(location: BreakLocation) {
        viewModelScope.launch {
            val current = settingsRepo.settings.first().breakLocations
            settingsRepo.setBreakLocations(current.filterNot { it == location })
        }
    }
}
