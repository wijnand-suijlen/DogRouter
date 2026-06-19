package app.dogrouter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.backup.BackupRepository
import app.dogrouter.data.backup.BackupSummary
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.data.remote.BanApi
import app.dogrouter.data.routing.RoutingDataInstaller
import app.dogrouter.data.routing.SegmentDownloadState
import app.dogrouter.domain.routing.RouteEstimate
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Form-state mirror of the persisted settings. Held as strings for
 * mid-edit values; per-field validity flags drive the red OutlinedTextField
 * error state.
 */
data class SettingsFormState(
    val bikeCapacityText: String,
    val bikeCapacityValid: Boolean,
    val stopBufferText: String,
    val stopBufferValid: Boolean,
    val cyclingSpeedText: String,
    val cyclingSpeedValid: Boolean,
    val walkingSpeedText: String,
    val walkingSpeedValid: Boolean,
    val bikeOverheadText: String,
    val bikeOverheadValid: Boolean,
    val homeAddress: String,
    val homeLatitude: Double?,
    val homeLongitude: Double?,
) {
    companion object {
        fun from(settings: AppSettings) = SettingsFormState(
            bikeCapacityText = settings.bikeCapacityKg.formatLight(),
            bikeCapacityValid = true,
            stopBufferText = settings.stopBufferMinutes.toString(),
            stopBufferValid = true,
            cyclingSpeedText = settings.cyclingSpeedKmh.formatLight(),
            cyclingSpeedValid = true,
            walkingSpeedText = settings.walkingSpeedKmh.formatLight(),
            walkingSpeedValid = true,
            bikeOverheadText = settings.bikeOverheadMinutes.toString(),
            bikeOverheadValid = true,
            homeAddress = settings.homeAddress,
            homeLatitude = settings.homeLatitude,
            homeLongitude = settings.homeLongitude,
        )
    }
}

sealed interface RoutingTestEvent {
    data class Result(val estimate: RouteEstimate) : RoutingTestEvent
    data class Failed(val message: String) : RoutingTestEvent
}

sealed interface BackupEvent {
    data object Exported : BackupEvent
    data class Imported(val summary: BackupSummary) : BackupEvent
    data class Failed(val message: String) : BackupEvent
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val repo: SettingsRepository,
    private val banApi: BanApi,
    private val routingInstaller: RoutingDataInstaller,
    private val routingProvider: RoutingProvider,
    private val backupRepo: BackupRepository,
) : ViewModel() {

    private val _form = MutableStateFlow<SettingsFormState?>(null)
    val form: StateFlow<SettingsFormState?> = _form.asStateFlow()

    val downloadState: StateFlow<SegmentDownloadState> = routingInstaller.downloadState

    private val _routingTestEvents = Channel<RoutingTestEvent>(Channel.BUFFERED)
    val routingTestEvents: Flow<RoutingTestEvent> = _routingTestEvents.receiveAsFlow()

    private val _testInProgress = MutableStateFlow(false)
    val testInProgress: StateFlow<Boolean> = _testInProgress.asStateFlow()

    private val _backupEvents = Channel<BackupEvent>(Channel.BUFFERED)
    val backupEvents: Flow<BackupEvent> = _backupEvents.receiveAsFlow()

    private val _homeAddressQuery = MutableStateFlow("")
    val homeAddressSuggestions: StateFlow<List<AddressSuggestion>> = _homeAddressQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .filter { it.length >= 3 }
        .distinctUntilChanged()
        .mapLatest { query -> banApi.search(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _form.value = SettingsFormState.from(repo.settings.first())
        }
        viewModelScope.launch { routingInstaller.installProfileIfMissing() }
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

    fun onCyclingSpeedTextChange(text: String) {
        val parsed = parsePositiveFloat(text)
        _form.update { it?.copy(cyclingSpeedText = text, cyclingSpeedValid = parsed != null) }
        parsed?.let { kmh -> viewModelScope.launch { repo.setCyclingSpeed(kmh) } }
    }

    fun onWalkingSpeedTextChange(text: String) {
        val parsed = parsePositiveFloat(text)
        _form.update { it?.copy(walkingSpeedText = text, walkingSpeedValid = parsed != null) }
        parsed?.let { kmh -> viewModelScope.launch { repo.setWalkingSpeed(kmh) } }
    }

    fun onBikeOverheadTextChange(text: String) {
        val parsed = text.toIntOrNull()?.takeIf { it >= 0 }
        _form.update { it?.copy(bikeOverheadText = text, bikeOverheadValid = parsed != null) }
        parsed?.let { minutes -> viewModelScope.launch { repo.setBikeOverheadMinutes(minutes) } }
    }

    fun onHomeAddressTextChange(text: String) {
        _form.update { it?.copy(homeAddress = text, homeLatitude = null, homeLongitude = null) }
        _homeAddressQuery.value = text
        viewModelScope.launch { repo.setHomeAddress(text, latitude = null, longitude = null) }
    }

    fun pickHomeAddressSuggestion(suggestion: AddressSuggestion) {
        _form.update {
            it?.copy(
                homeAddress = suggestion.label,
                homeLatitude = suggestion.latitude,
                homeLongitude = suggestion.longitude,
            )
        }
        _homeAddressQuery.value = suggestion.label
        viewModelScope.launch {
            repo.setHomeAddress(suggestion.label, suggestion.latitude, suggestion.longitude)
        }
    }

    /** Called from AppNavigation when the address picker returns a result. */
    fun applyPickedHomeAddress(suggestion: AddressSuggestion) =
        pickHomeAddressSuggestion(suggestion)

    fun downloadRoutingData() {
        viewModelScope.launch { routingInstaller.downloadSegment() }
    }

    fun deleteRoutingData() {
        viewModelScope.launch { routingInstaller.deleteSegment() }
    }

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

    /**
     * Export all data, handing the JSON to [write] (which the screen backs
     * with the chosen file URI). IO runs off the main thread.
     */
    fun exportData(write: suspend (text: String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = backupRepo.exportToJson()
                    write(text)
                }
            }.onSuccess { _backupEvents.send(BackupEvent.Exported) }
                .onFailure { _backupEvents.send(BackupEvent.Failed(it.message ?: "Export failed")) }
        }
    }

    /**
     * Import data read by [read] (the screen reads the chosen file URI),
     * replacing all current data. Confirmation is handled in the UI.
     */
    fun importData(read: suspend () -> String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    backupRepo.importFromJson(read())
                }
            }.onSuccess { _backupEvents.send(BackupEvent.Imported(it)) }
                .onFailure { _backupEvents.send(BackupEvent.Failed(it.message ?: "Import failed")) }
        }
    }

    private fun parsePositiveFloat(text: String): Float? =
        text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/** Trim trailing ".0" on whole-number floats so the field reads "70" not "70.0". */
private fun Float.formatLight(): String {
    val rounded = this.toInt().toFloat()
    return if (rounded == this) rounded.toInt().toString() else this.toString()
}
