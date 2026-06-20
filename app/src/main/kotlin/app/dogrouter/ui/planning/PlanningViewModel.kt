package app.dogrouter.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.AppointmentDao
import app.dogrouter.data.entity.Appointment
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.BreakLocation
import app.dogrouter.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Backs the Planning screen: the mid-day break window, duration and break
 * locations (in [AppSettings]), plus one-off [Appointment]s (dog-free
 * commitments the planner schedules the day around).
 */
class PlanningViewModel(
    private val settingsRepo: SettingsRepository,
    private val appointmentDao: AppointmentDao,
) : ViewModel() {

    /** A half-built appointment; the address is filled by the map picker. */
    data class AppointmentDraft(
        val date: LocalDate,
        val start: LocalTime = LocalTime.of(14, 0),
        val end: LocalTime = LocalTime.of(15, 0),
        val label: String = "",
        val address: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    ) {
        val isComplete: Boolean
            get() = label.isNotBlank() && latitude != null && longitude != null && end.isAfter(start)
    }

    /** What a returning map-pick should fill: a break location or the draft. */
    private enum class PickTarget { NONE, BREAK_LOCATION, APPOINTMENT }
    private var pickTarget = PickTarget.NONE

    val settings: StateFlow<AppSettings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val appointments: StateFlow<List<Appointment>> = appointmentDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow(AppointmentDraft(date = LocalDate.now()))
    val draft: StateFlow<AppointmentDraft> = _draft.asStateFlow()

    fun setBreakWindow(start: LocalTime, end: LocalTime) {
        viewModelScope.launch { settingsRepo.setBreakWindow(start, end) }
    }

    fun setBreakDuration(minutes: Int) {
        viewModelScope.launch { settingsRepo.setBreakDuration(minutes.coerceIn(5, 180)) }
    }

    fun setHomeLunchMinFree(minutes: Int) {
        viewModelScope.launch { settingsRepo.setHomeLunchMinFreeMinutes(minutes.coerceIn(30, 300)) }
    }

    /** Called right before navigating to the address picker, so the returning
     *  address is routed to the right place. */
    fun pickForBreakLocation() { pickTarget = PickTarget.BREAK_LOCATION }
    fun pickForAppointment() { pickTarget = PickTarget.APPOINTMENT }

    fun onAddressPicked(label: String, latitude: Double, longitude: Double) {
        when (pickTarget) {
            PickTarget.BREAK_LOCATION -> addLocation(label, latitude, longitude)
            PickTarget.APPOINTMENT ->
                _draft.update { it.copy(address = label, latitude = latitude, longitude = longitude) }
            PickTarget.NONE -> Unit
        }
        pickTarget = PickTarget.NONE
    }

    fun setDraftDate(date: LocalDate) = _draft.update { it.copy(date = date) }
    fun setDraftStart(time: LocalTime) = _draft.update { it.copy(start = time) }
    fun setDraftEnd(time: LocalTime) = _draft.update { it.copy(end = time) }
    fun setDraftLabel(label: String) = _draft.update { it.copy(label = label) }

    fun addAppointment() {
        val d = _draft.value
        if (!d.isComplete) return
        viewModelScope.launch {
            appointmentDao.upsert(
                Appointment(
                    id = UUID.randomUUID().toString(),
                    date = d.date, startTime = d.start, endTime = d.end,
                    label = d.label.trim(), address = d.address.orEmpty(),
                    latitude = d.latitude!!, longitude = d.longitude!!,
                ),
            )
            _draft.value = AppointmentDraft(date = d.date)
        }
    }

    fun removeAppointment(appointment: Appointment) {
        viewModelScope.launch { appointmentDao.delete(appointment) }
    }

    private fun addLocation(label: String, latitude: Double, longitude: Double) {
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
