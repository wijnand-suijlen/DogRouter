package app.dogrouter.ui.dogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.TransportState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DogFormState(
    val name: String = "",
    val breed: String = "",
    val weightKg: String = "",
    val photoUri: String? = null,
    val ownerName: String = "",
    val ownerPhone: String = "",
    val address: String = "",
    val stopNotes: String = "",
    val stopAdjustmentMinutes: String = "0",
    val inCargoBike: TransportState = TransportState.NotTested,
    val inBackpack: TransportState = TransportState.NotTested,
    val notes: String = "",
    val loading: Boolean = false,
)

sealed interface DogEditEvent {
    data object Closed : DogEditEvent
    data class ValidationError(val message: String) : DogEditEvent
}

class DogEditViewModel(
    private val dogDao: DogDao,
    private val dogId: String?,
) : ViewModel() {

    val isNew: Boolean = dogId == null

    private val _state = MutableStateFlow(DogFormState(loading = !isNew))
    val state: StateFlow<DogFormState> = _state.asStateFlow()

    private val _events = Channel<DogEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        if (dogId != null) loadExisting(dogId)
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val existing = dogDao.findById(id) ?: run {
                _events.send(DogEditEvent.Closed)
                return@launch
            }
            _state.value = DogFormState(
                name = existing.name,
                breed = existing.breed.orEmpty(),
                weightKg = existing.weightKg.toString(),
                photoUri = existing.photoUri,
                ownerName = existing.ownerName,
                ownerPhone = existing.ownerPhone.orEmpty(),
                address = existing.address,
                stopNotes = existing.stopNotes.orEmpty(),
                stopAdjustmentMinutes = existing.stopAdjustmentMinutes.toString(),
                inCargoBike = existing.inCargoBike,
                inBackpack = existing.inBackpack,
                notes = existing.notes.orEmpty(),
                loading = false,
            )
        }
    }

    fun update(transform: DogFormState.() -> DogFormState) {
        _state.update(transform)
    }

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            if (s.name.isBlank()) {
                _events.send(DogEditEvent.ValidationError("Name is required"))
                return@launch
            }
            val weight = s.weightKg.replace(',', '.').toFloatOrNull()
            if (weight == null || weight <= 0f) {
                _events.send(DogEditEvent.ValidationError("Weight must be a positive number"))
                return@launch
            }
            val adjustment = s.stopAdjustmentMinutes.toIntOrNull() ?: 0

            val dog = Dog(
                id = dogId ?: UUID.randomUUID().toString(),
                name = s.name.trim(),
                breed = s.breed.trim().ifBlank { null },
                weightKg = weight,
                photoUri = s.photoUri,
                ownerName = s.ownerName.trim(),
                ownerPhone = s.ownerPhone.trim().ifBlank { null },
                address = s.address.trim(),
                stopNotes = s.stopNotes.trim().ifBlank { null },
                stopAdjustmentMinutes = adjustment,
                inCargoBike = s.inCargoBike,
                inBackpack = s.inBackpack,
                notes = s.notes.trim().ifBlank { null },
            )
            if (isNew) dogDao.insert(dog) else dogDao.update(dog)
            _events.send(DogEditEvent.Closed)
        }
    }

    fun delete() {
        val id = dogId ?: return
        viewModelScope.launch {
            dogDao.findById(id)?.let { dogDao.delete(it) }
            _events.send(DogEditEvent.Closed)
        }
    }
}
