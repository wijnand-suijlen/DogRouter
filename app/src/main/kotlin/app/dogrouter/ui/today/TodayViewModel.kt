package app.dogrouter.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.data.remote.BanApi
import app.dogrouter.domain.dayplan.DayPlanService
import app.dogrouter.domain.dayplan.DayRoute
import app.dogrouter.domain.dayplan.PlanPhase
import app.dogrouter.domain.dayplan.PlanState
import app.dogrouter.domain.dayplan.RouteEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class TodayViewModel(
    private val dayPlanService: DayPlanService,
    settingsRepository: SettingsRepository,
    private val banApi: BanApi,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /**
     * Per-stop buffer in seconds, used by the timeline to tell travel time
     * apart from time spent waiting for a window to open (the wait is the gap
     * left after the previous stop's service and the leg's travel).
     */
    val stopBufferSeconds: StateFlow<Int> = settingsRepository.settings
        .map { it.stopBufferMinutes * 60 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The plan for the selected date, recomputed whenever the date or any
     * underlying data changes. flatMapLatest cancels an in-flight plan
     * when the date changes, so we never show yesterday's events for today.
     */
    val planState: StateFlow<PlanState> = _selectedDate
        .flatMapLatest { dayPlanService.observePlan(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlanState.Loading(0f, PlanPhase.ROUTING),
        )

    /** Whether the day in view should include a mid-day break. */
    val breakRequested: StateFlow<Boolean> = _selectedDate
        .flatMapLatest { dayPlanService.observeBreakRequested(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether the day in view shows a hand-edited (pinned) plan. */
    val isPlanEdited: StateFlow<Boolean> = _selectedDate
        .flatMapLatest { dayPlanService.observeIsEdited(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Constraint warnings for the shown plan (empty for a feasible/solver plan).
     *  Shown but not enforced — the walker may keep an edit that bends a rule. */
    val planWarnings: StateFlow<List<String>> = planState
        .mapLatest { state ->
            if (state is PlanState.Ready) dayPlanService.warningsFor(state.route) else emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isApplyingEdit = MutableStateFlow(false)

    /** True while an edit is being re-timed and saved, for a progress hint. */
    val isApplyingEdit: StateFlow<Boolean> = _isApplyingEdit.asStateFlow()

    // Snapshots of the plan before each edit, so the last edit can be undone.
    // wasEdited tells undo whether to restore a pinned plan or revert to solver.
    private data class UndoEntry(val route: DayRoute, val wasEdited: Boolean)
    private val _undo = MutableStateFlow<List<UndoEntry>>(emptyList())

    /** Whether there is an edit on this date that can be undone. */
    val canUndo: StateFlow<Boolean> = _undo
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private fun pushUndo() {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        _undo.update { it + UndoEntry(route, isPlanEdited.value) }
    }

    /** Undo the last edit on this date: restore the previous plan (or the
     *  solver plan if that was the state before the first edit). */
    fun undo() {
        val entry = _undo.value.lastOrNull() ?: return
        _undo.update { it.dropLast(1) }
        applyEdit {
            if (entry.wasEdited) {
                dayPlanService.pinPlan(_selectedDate.value, entry.route)
            } else {
                dayPlanService.discardSavedPlan(_selectedDate.value)
            }
        }
    }

    private fun applyEdit(block: suspend () -> Unit) {
        _isApplyingEdit.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _isApplyingEdit.value = false
            }
        }
    }

    private val _removingDogIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Dogs the walker just tapped "not today" for but that the (re-timed) plan
     * still shows — used to strike them through and spin a button immediately,
     * so the tap is acknowledged before the slower re-time lands. An id is
     * pruned once the dog is gone from the plan (or the date changes).
     */
    val removingDogIds: StateFlow<Set<String>> = _removingDogIds.asStateFlow()

    init {
        // Drop pending ids once the dog no longer appears in the shown plan.
        viewModelScope.launch {
            planState.collect { state ->
                val present = (state as? PlanState.Ready)?.route?.events.orEmpty()
                    .filterIsInstance<RouteEvent.Pickup>().mapTo(HashSet()) { it.dog.id }
                _removingDogIds.update { it intersect present }
            }
        }
    }

    /** Mark a dog as not walked today: drop it from the plan and pin the rest. */
    fun markDogNotToday(dogId: String) {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        pushUndo()
        _removingDogIds.update { it + dogId } // immediate visual feedback
        applyEdit { dayPlanService.markDogNotToday(_selectedDate.value, route, dogId) }
    }

    /** Set how long the walk at [eventIndex] lasts (minutes), then pin. */
    fun setWalkDuration(eventIndex: Int, minutes: Int) {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        pushUndo()
        applyEdit { dayPlanService.setWalkDuration(_selectedDate.value, route, eventIndex, minutes) }
    }

    /** Pin the start time (seconds since midnight) of the pickup at [eventIndex]. */
    fun setStopTime(eventIndex: Int, secondsOfDay: Int) {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        pushUndo()
        applyEdit { dayPlanService.setStopTime(_selectedDate.value, route, eventIndex, secondsOfDay) }
    }

    /** Move the standalone walk at [eventIndex] one step earlier/later. */
    fun moveWalk(eventIndex: Int, earlier: Boolean) {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        pushUndo()
        applyEdit { dayPlanService.moveWalk(_selectedDate.value, route, eventIndex, earlier) }
    }

    /** Split [dogId] out of its group into its own walk. */
    fun splitDogAlone(dogId: String) {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        pushUndo()
        applyEdit { dayPlanService.splitDogAlone(_selectedDate.value, route, dogId) }
    }

    /** Move [dogId] into the walk at [targetWalkEventIndex] so they walk together. */
    fun groupDogWith(dogId: String, targetWalkEventIndex: Int) {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        pushUndo()
        applyEdit { dayPlanService.groupDogWith(_selectedDate.value, route, dogId, targetWalkEventIndex) }
    }

    /** Active dogs with coordinates — candidates for a hand-added walk. */
    val addableDogs: StateFlow<List<Dog>> = dayPlanService.observeAddableDogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Add an extra walk of [minutes] for [dogId] on the day in view. */
    fun addWalk(dogId: String, minutes: Int) {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        pushUndo()
        applyEdit { dayPlanService.addWalk(_selectedDate.value, route, dogId, minutes) }
    }

    // --- Appointment address autocomplete (BAN) for "add appointment" ---------
    private val _apptAddressQuery = MutableStateFlow("")
    private val _apptPicked = MutableStateFlow<AddressSuggestion?>(null)

    /** Current text in the appointment address field. */
    val apptAddressText: StateFlow<String> = _apptAddressQuery.asStateFlow()

    /** Whether a real address (with coordinates) is picked for the appointment. */
    val apptAddressValidated: StateFlow<Boolean> = _apptPicked
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Live BAN suggestions for the appointment address field. */
    val apptAddressSuggestions: StateFlow<List<AddressSuggestion>> = _apptAddressQuery
        .debounce(300)
        .filter { it.length >= 3 }
        .distinctUntilChanged()
        .mapLatest { query -> banApi.search(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onApptAddressChange(text: String) {
        _apptAddressQuery.value = text
        _apptPicked.value = null
    }

    fun pickApptAddress(suggestion: AddressSuggestion) {
        _apptAddressQuery.value = suggestion.label
        _apptPicked.value = suggestion
    }

    /** Force a dog-free appointment (label, window) at the picked address. */
    fun addAppointment(label: String, startSeconds: Int, endSeconds: Int) {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        val picked = _apptPicked.value ?: return
        pushUndo()
        applyEdit {
            dayPlanService.addAppointment(
                _selectedDate.value, route, label, startSeconds, endSeconds,
                picked.latitude, picked.longitude,
            )
        }
        _apptAddressQuery.value = ""
        _apptPicked.value = null
    }

    /** Discard the hand-edited plan and go back to the solver's plan. */
    fun revertPlan() {
        viewModelScope.launch { dayPlanService.discardSavedPlan(_selectedDate.value) }
    }

    fun setBreakRequested(requested: Boolean) {
        dayPlanService.setBreakRequested(_selectedDate.value, requested)
    }

    fun goToPreviousDay() = changeDate(_selectedDate.value.minusDays(1))

    fun goToNextDay() = changeDate(_selectedDate.value.plusDays(1))

    fun goToToday() = changeDate(LocalDate.now())

    fun setDate(date: LocalDate) = changeDate(date)

    /** Switch the day in view and drop the (date-scoped) undo history. */
    private fun changeDate(date: LocalDate) {
        _undo.value = emptyList()
        _selectedDate.value = date
    }

    /** Ask the solver for a different plan for the day in view. Discards any
     *  hand-edited plan first, so a fresh solver plan actually takes effect. */
    fun refresh() {
        val date = _selectedDate.value
        viewModelScope.launch {
            dayPlanService.discardSavedPlan(date)
            dayPlanService.refresh(date)
        }
    }
}
