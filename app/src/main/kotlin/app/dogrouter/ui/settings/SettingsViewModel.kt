package app.dogrouter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.prefs.AppSettings
import app.dogrouter.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

class SettingsViewModel(
    private val repo: SettingsRepository,
) : ViewModel() {

    private val _form = MutableStateFlow<SettingsFormState?>(null)
    val form: StateFlow<SettingsFormState?> = _form.asStateFlow()

    init {
        viewModelScope.launch {
            _form.value = SettingsFormState.from(repo.settings.first())
        }
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

    private fun parsePositiveFloat(text: String): Float? =
        text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
}

/** Trim trailing ".0" on whole-number floats so the field reads "70" not "70.0". */
private fun Float.formatLight(): String {
    val rounded = this.toInt().toFloat()
    return if (rounded == this) rounded.toInt().toString() else this.toString()
}
