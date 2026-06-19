package app.dogrouter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.data.routing.RoutingDataInstaller
import app.dogrouter.data.routing.SegmentDownloadState
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Form-state mirror of the persisted settings. Kept as strings because the
 * text fields can be mid-edit ("1" on the way to "15"); per-field validity
 * flags drive the red-outline error state on each TextField, and the
 * repository is updated whenever a typed value parses cleanly.
 */
data class SettingsFormState(
    val cyclingSpeedText: String,
    val cyclingSpeedValid: Boolean,
    val bikeCapacityText: String,
    val bikeCapacityValid: Boolean,
    val stopBufferText: String,
    val stopBufferValid: Boolean,
) {
    companion object {
        fun from(settings: AppSettings) = SettingsFormState(
            cyclingSpeedText = settings.cyclingSpeedKmh.formatLight(),
            cyclingSpeedValid = true,
            bikeCapacityText = settings.bikeCapacityKg.formatLight(),
            bikeCapacityValid = true,
            stopBufferText = settings.stopBufferMinutes.toString(),
            stopBufferValid = true,
        )
    }
}

sealed interface RoutingTestEvent {
    data class Result(val estimate: RouteEstimate) : RoutingTestEvent
    data class Failed(val message: String) : RoutingTestEvent
}

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val routingInstaller: RoutingDataInstaller,
    private val routingProvider: RoutingProvider,
) : ViewModel() {

    private val _form = MutableStateFlow<SettingsFormState?>(null)
    val form: StateFlow<SettingsFormState?> = _form.asStateFlow()

    val downloadState: StateFlow<SegmentDownloadState> = routingInstaller.downloadState

    private val _routingTestEvents = Channel<RoutingTestEvent>(Channel.BUFFERED)
    val routingTestEvents: Flow<RoutingTestEvent> = _routingTestEvents.receiveAsFlow()

    private val _testInProgress = MutableStateFlow(false)
    val testInProgress: StateFlow<Boolean> = _testInProgress.asStateFlow()

    init {
        viewModelScope.launch {
            _form.value = SettingsFormState.from(repo.settings.first())
        }
        viewModelScope.launch { routingInstaller.installProfileIfMissing() }
    }

    fun onCyclingSpeedTextChange(text: String) {
        val parsed = parsePositiveFloat(text)
        _form.update { it?.copy(cyclingSpeedText = text, cyclingSpeedValid = parsed != null) }
        parsed?.let { kmh -> viewModelScope.launch { repo.setCyclingSpeed(kmh) } }
    }

    fun onBikeCapacityTextChange(text: String) {
        val parsed = parsePositiveFloat(text)
        _form.update { it?.copy(bikeCapacityText = text, bikeCapacityValid = parsed != null) }
        parsed?.let { kg -> viewModelScope.launch { repo.setBikeCapacity(kg) } }
    }

    fun onStopBufferTextChange(text: String) {
        val parsed = text.toIntOrNull()?.takeIf { it >= 0 }
        _form.update { it?.copy(stopBufferText = text, stopBufferValid = parsed != null) }
        parsed?.let { minutes -> viewModelScope.launch { repo.setStopBufferMinutes(minutes) } }
    }

    fun downloadRoutingData() {
        viewModelScope.launch { routingInstaller.downloadSegment() }
    }

    fun deleteRoutingData() {
        viewModelScope.launch { routingInstaller.deleteSegment() }
    }

    /**
     * Hard-coded sanity check: a short route in Meudon. Pure diagnostic so
     * the walker can confirm the engine is wired up before any planner
     * integration shows distances.
     */
    fun runRoutingSelfTest() {
        if (_testInProgress.value) return
        viewModelScope.launch {
            _testInProgress.value = true
            try {
                val estimate = routingProvider.route(
                    fromLatitude = 48.8137,
                    fromLongitude = 2.2390,
                    toLatitude = 48.8154,
                    toLongitude = 2.2270,
                )
                if (estimate != null) {
                    _routingTestEvents.send(RoutingTestEvent.Result(estimate))
                } else {
                    _routingTestEvents.send(
                        RoutingTestEvent.Failed(routingProvider.lastError ?: "No route returned"),
                    )
                }
            } finally {
                _testInProgress.value = false
            }
        }
    }

    private fun parsePositiveFloat(text: String): Float? =
        text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
}

/** Trim trailing ".0" on whole-number floats so the field reads "70" not "70.0". */
private fun Float.formatLight(): String {
    val rounded = this.toInt().toFloat()
    return if (rounded == this) rounded.toInt().toString() else this.toString()
}
