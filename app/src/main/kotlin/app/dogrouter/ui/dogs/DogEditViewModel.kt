package app.dogrouter.ui.dogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.db.DogDao
import app.dogrouter.data.db.DogIncompatibilityDao
import app.dogrouter.data.db.DogScheduleDao
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.TransportState
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.data.remote.BanApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/** Lightweight projection of another dog so the form can show pickable peers. */
data class IncompatibilityCandidate(
    val id: String,
    val name: String,
)

data class DogFormState(
    val name: String = "",
    val breed: String = "",
    val weightKg: String = "",
    val photoUri: String? = null,
    val ownerName: String = "",
    val ownerPhone: String = "",
    val address: String = "",
    val addressLatitude: Double? = null,
    val addressLongitude: Double? = null,
    val stopNotes: String = "",
    val stopAdjustmentMinutes: String = "0",
    val inCargoBike: TransportState = TransportState.NotTested,
    val inBackpack: TransportState = TransportState.NotTested,
    val allowLongerWalk: Boolean = true,
    val notes: String = "",
    val scheduleRules: List<ScheduleRuleDraft> = emptyList(),
    val incompatibleDogIds: Set<String> = emptySet(),
    val incompatibilityCandidates: List<IncompatibilityCandidate> = emptyList(),
    val loading: Boolean = false,
)

sealed interface DogEditEvent {
    data object Closed : DogEditEvent
    data class ValidationError(val message: String) : DogEditEvent
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DogEditViewModel(
    private val dogDao: DogDao,
    private val dogScheduleDao: DogScheduleDao,
    private val incompatibilityDao: DogIncompatibilityDao,
    private val banApi: BanApi,
    private val dogId: String?,
) : ViewModel() {

    val isNew: Boolean = dogId == null

    private val _state = MutableStateFlow(DogFormState(loading = !isNew))
    val state: StateFlow<DogFormState> = _state.asStateFlow()

    private val _events = Channel<DogEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _addressQuery = MutableStateFlow("")

    val addressSuggestions: StateFlow<List<AddressSuggestion>> = _addressQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .filter { it.length >= 3 }
        .distinctUntilChanged()
        .mapLatest { query -> banApi.search(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { loadCandidates() }
        if (dogId != null) loadExisting(dogId)
    }

    private suspend fun loadCandidates() {
        val candidates = dogDao.observeAll().first()
            .filter { it.id != dogId }
            .map { IncompatibilityCandidate(id = it.id, name = it.name) }
            .sortedBy { it.name.lowercase() }
        _state.update { it.copy(incompatibilityCandidates = candidates) }
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val existing = dogDao.findById(id) ?: run {
                _events.send(DogEditEvent.Closed)
                return@launch
            }
            val rules = dogScheduleDao.findForDog(id).map { it.toDraft() }
            val incompatibilities = incompatibilityDao.findForDog(id)
                .map { pair -> if (pair.dogIdA == id) pair.dogIdB else pair.dogIdA }
                .toSet()
            _state.update { current ->
                current.copy(
                    name = existing.name,
                    breed = existing.breed.orEmpty(),
                    weightKg = existing.weightKg.toString(),
                    photoUri = existing.photoUri,
                    ownerName = existing.ownerName,
                    ownerPhone = existing.ownerPhone.orEmpty(),
                    address = existing.address,
                    addressLatitude = existing.latitude,
                    addressLongitude = existing.longitude,
                    stopNotes = existing.stopNotes.orEmpty(),
                    stopAdjustmentMinutes = existing.stopAdjustmentMinutes.toString(),
                    inCargoBike = existing.inCargoBike,
                    inBackpack = existing.inBackpack,
                    allowLongerWalk = existing.allowLongerWalk,
                    notes = existing.notes.orEmpty(),
                    scheduleRules = rules,
                    incompatibleDogIds = incompatibilities,
                    loading = false,
                )
            }
        }
    }

    fun update(transform: DogFormState.() -> DogFormState) {
        _state.update(transform)
    }

    fun onAddressTextChange(text: String) {
        _state.update { it.copy(address = text, addressLatitude = null, addressLongitude = null) }
        _addressQuery.value = text
    }

    fun pickAddressSuggestion(suggestion: AddressSuggestion) {
        _state.update {
            it.copy(
                address = suggestion.label,
                addressLatitude = suggestion.latitude,
                addressLongitude = suggestion.longitude,
            )
        }
        _addressQuery.value = suggestion.label
    }

    fun toggleIncompatibility(otherDogId: String) {
        _state.update {
            val next = if (otherDogId in it.incompatibleDogIds) {
                it.incompatibleDogIds - otherDogId
            } else {
                it.incompatibleDogIds + otherDogId
            }
            it.copy(incompatibleDogIds = next)
        }
    }

    fun setAllowLongerWalk(value: Boolean) {
        _state.update { it.copy(allowLongerWalk = value) }
    }

    fun addScheduleRule() {
        _state.update {
            it.copy(scheduleRules = it.scheduleRules + ScheduleRuleDraft(weekdays = DEFAULT_WEEKDAYS))
        }
    }

    fun removeScheduleRule(ruleId: String) {
        _state.update { it.copy(scheduleRules = it.scheduleRules.filterNot { r -> r.id == ruleId }) }
    }

    private fun updateRule(ruleId: String, transform: ScheduleRuleDraft.() -> ScheduleRuleDraft) {
        _state.update { state ->
            state.copy(
                scheduleRules = state.scheduleRules.map { r ->
                    if (r.id == ruleId) r.transform() else r
                },
            )
        }
    }

    fun toggleWeekday(ruleId: String, day: DayOfWeek) = updateRule(ruleId) {
        val next = if (day in weekdays) weekdays - day else weekdays + day
        copy(weekdays = next)
    }

    fun setEarliestStart(ruleId: String, time: LocalTime?) =
        updateRule(ruleId) { copy(earliestStart = time) }

    fun setLatestEnd(ruleId: String, time: LocalTime?) =
        updateRule(ruleId) { copy(latestEnd = time) }

    fun setDurationMinutes(ruleId: String, minutes: Int) =
        updateRule(ruleId) { copy(durationMinutes = minutes) }

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
            val invalidRule = s.scheduleRules.firstOrNull { rule ->
                rule.weekdays.isEmpty() || rule.durationMinutes <= 0
            }
            if (invalidRule != null) {
                _events.send(
                    DogEditEvent.ValidationError(
                        "Each walk rule needs at least one weekday and a positive duration",
                    ),
                )
                return@launch
            }

            val adjustment = s.stopAdjustmentMinutes.toIntOrNull() ?: 0
            val effectiveDogId = dogId ?: UUID.randomUUID().toString()

            val dog = Dog(
                id = effectiveDogId,
                name = s.name.trim(),
                breed = s.breed.trim().ifBlank { null },
                weightKg = weight,
                photoUri = s.photoUri,
                ownerName = s.ownerName.trim(),
                ownerPhone = s.ownerPhone.trim().ifBlank { null },
                address = s.address.trim(),
                latitude = s.addressLatitude,
                longitude = s.addressLongitude,
                stopNotes = s.stopNotes.trim().ifBlank { null },
                stopAdjustmentMinutes = adjustment,
                inCargoBike = s.inCargoBike,
                inBackpack = s.inBackpack,
                allowLongerWalk = s.allowLongerWalk,
                notes = s.notes.trim().ifBlank { null },
            )
            if (isNew) dogDao.insert(dog) else dogDao.update(dog)
            dogScheduleDao.replaceForDog(
                dogId = effectiveDogId,
                rules = s.scheduleRules.map { it.toEntity(effectiveDogId) },
            )
            incompatibilityDao.replaceForDog(
                dogId = effectiveDogId,
                partnerIds = s.incompatibleDogIds.toList(),
            )
            _events.send(DogEditEvent.Closed)
        }
    }

    fun delete() {
        val id = dogId ?: return
        viewModelScope.launch {
            // Schedule rules + incompatibility pairs cascade via FK ON DELETE.
            dogDao.findById(id)?.let { dogDao.delete(it) }
            _events.send(DogEditEvent.Closed)
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        val DEFAULT_WEEKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
    }
}
