package app.dogrouter.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.entity.Dog
import app.dogrouter.data.entity.DogScheduleRule
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.data.remote.AddressSuggestion
import app.dogrouter.data.remote.BanApi
import app.dogrouter.domain.dayplan.DayPlanService
import app.dogrouter.domain.dayplan.LegMode
import app.dogrouter.domain.dayplan.PlanPhase
import app.dogrouter.domain.dayplan.PlanState
import app.dogrouter.domain.dayplan.RouteEvent
import app.dogrouter.domain.dayplan.explodeForEditing
import app.dogrouter.domain.dayplan.isValidOrder
import app.dogrouter.domain.dayplan.mergeWalkWithNeighbor
import app.dogrouter.domain.dayplan.removeDogChips
import app.dogrouter.domain.dayplan.setLegMode
import app.dogrouter.domain.dayplan.splitWalkInTwo
import app.dogrouter.domain.routing.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
import java.time.LocalTime

/** A draggable chip in the editor: the event plus a stable id so the
 *  reorderable list keeps each item's identity across a drag. */
data class EditItem(val id: Long, val event: RouteEvent)

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
     * apart from time spent waiting for a window to open.
     */
    val stopBufferSeconds: StateFlow<Int> = settingsRepository.settings
        .map { it.stopBufferMinutes * 60 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The plan for the selected date, recomputed whenever the date or any
     *  underlying data changes. */
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

    /** Constraint warnings for the shown plan (empty for a feasible plan).
     *  Shown but not enforced — the walker may keep an edit that bends a rule. */
    val planWarnings: StateFlow<List<String>> = planState
        .mapLatest { state ->
            if (state is PlanState.Ready) dayPlanService.warningsFor(state.route) else emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isApplyingEdit = MutableStateFlow(false)

    /** True while an edit is being re-timed and saved, for a progress hint. */
    val isApplyingEdit: StateFlow<Boolean> = _isApplyingEdit.asStateFlow()

    // --- The drag-and-drop editor's working list ------------------------------

    private var nextItemId = 0L
    private val _editItems = MutableStateFlow<List<EditItem>?>(null)

    /** The chips shown while editing (FetchBike stripped, shared walks split per
     *  dog), or null when not in edit mode. Position = execution order. */
    val editItems: StateFlow<List<EditItem>?> = _editItems.asStateFlow()

    private fun toItems(events: List<RouteEvent>): List<EditItem> =
        explodeForEditing(events).map { EditItem(nextItemId++, it) }

    private fun currentEvents(): List<RouteEvent>? = _editItems.value?.map { it.event }

    /** Enter edit mode: explode the shown plan into draggable chips. */
    fun beginEdit() {
        val route = (planState.value as? PlanState.Ready)?.route ?: return
        _undo.value = emptyList()
        _editItems.value = toItems(route.events)
    }

    /** Leave edit mode. The last commit already pinned the merged plan. */
    fun endEdit() {
        _editItems.value = null
        _undo.value = emptyList()
        _pendingCommit.value = null
    }

    // Snapshots of the chip events before each edit, for undo.
    private val _undo = MutableStateFlow<List<List<RouteEvent>>>(emptyList())

    /** Whether there is an edit on this date that can be undone. */
    val canUndo: StateFlow<Boolean> = _undo
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private fun pushUndo(events: List<RouteEvent>) {
        _undo.update { it + listOf(events) }
    }

    /** Undo the last edit: restore the previous chip order and re-commit it. */
    fun undo() {
        val prev = _undo.value.lastOrNull() ?: return
        _undo.update { it.dropLast(1) }
        _editItems.value = toItems(prev)
        commit(prev, pushUndo = false)
    }

    // The latest chip list awaiting a re-time. A new edit replaces it and the
    // collector below cancels any in-flight re-time, so the walker can keep
    // editing without waiting and only the most recent state is timed/pinned.
    private val _pendingCommit = MutableStateFlow<Pair<LocalDate, List<RouteEvent>>?>(null)

    init {
        viewModelScope.launch {
            _pendingCommit.collectLatest { pending ->
                pending ?: return@collectLatest
                val (date, events) = pending
                _isApplyingEdit.value = true
                try {
                    val retimed = dayPlanService.commitEdit(date, events)
                    if (retimed != null && date == _selectedDate.value && _editItems.value != null) {
                        _editItems.value = toItems(retimed.events)
                    }
                } finally {
                    _isApplyingEdit.value = false
                }
            }
        }
    }

    /** Queue [events] for a (cancellable) re-time + pin; the chips refresh from
     *  the result. The UI already shows the edit optimistically. */
    private fun commit(events: List<RouteEvent>, pushUndo: Boolean) {
        if (pushUndo) currentEvents()?.let(::pushUndo)
        _pendingCommit.value = _selectedDate.value to events
    }

    /** Apply a pure chip transform and commit it; no-op if [transform] returns null. */
    private fun edit(transform: (List<RouteEvent>) -> List<RouteEvent>?) {
        val events = currentEvents() ?: return
        val next = transform(events) ?: return
        pushUndo(events)
        _editItems.value = toItems(next) // optimistic; refreshed after re-time
        commit(next, pushUndo = false)
    }

    // --- Drag reorder ---------------------------------------------------------

    /** Snapshot the order before a drag begins, so the whole drag is one undo. */
    fun onReorderStart() {
        currentEvents()?.let(::pushUndo)
    }

    /** Move the chip at [from] to [to], rejecting (clamping) a move that would
     *  break the `pickup ≤ walk ≤ dropoff` invariant. Local only — snappy. */
    fun onMove(from: Int, to: Int) {
        _editItems.update { items ->
            if (items == null || from !in items.indices) return@update items
            val reordered = items.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) }
            if (isValidOrder(reordered.map { it.event })) reordered else items
        }
    }

    /** Re-time + pin after a drag settles. */
    fun onReorderStop() {
        val events = currentEvents() ?: return
        commit(events, pushUndo = false)
    }

    // --- Tap edits ------------------------------------------------------------

    /** Set the duration (minutes) of the walk chip at [index]. */
    fun setWalkDuration(index: Int, minutes: Int) = edit { events ->
        val walk = events.getOrNull(index) as? RouteEvent.Walk ?: return@edit null
        events.toMutableList().also { it[index] = walk.copy(durationSeconds = minutes.coerceAtLeast(0) * 60) }
    }

    /** Set the start time (seconds) and duration (minutes) of the appointment
     *  chip at [index]. */
    fun setAppointment(index: Int, startSeconds: Int, minutes: Int) = edit { events ->
        val appt = events.getOrNull(index) as? RouteEvent.Appointment ?: return@edit null
        val start = startSeconds.coerceIn(0, 24 * 3600 - 1)
        events.toMutableList().also {
            it[index] = appt.copy(startSeconds = start, durationSeconds = minutes.coerceAtLeast(0) * 60)
        }
    }

    /** Set the earliest start (seconds) and duration (minutes) of the break
     *  chip at [index]. */
    fun setBreakTimes(index: Int, startSeconds: Int, minutes: Int) = edit { events ->
        val br = events.getOrNull(index) as? RouteEvent.Break ?: return@edit null
        val start = startSeconds.coerceIn(0, 24 * 3600 - 1)
        events.toMutableList().also {
            it[index] = br.copy(earliestStartSeconds = start, durationSeconds = minutes.coerceAtLeast(0) * 60)
        }
    }

    /** Pin the earliest start time (seconds) of the pickup chip at [index]. */
    fun setStopTime(index: Int, secondsOfDay: Int) = edit { events ->
        val pickup = events.getOrNull(index) as? RouteEvent.Pickup ?: return@edit null
        val clamped = secondsOfDay.coerceIn(0, 24 * 3600 - 1)
        val newRule = pickup.rule.copy(earliestStart = LocalTime.ofSecondOfDay(clamped.toLong()))
        events.toMutableList().also { it[index] = pickup.copy(rule = newRule) }
    }

    /** Split the single-dog walk chip at [index] into two, or merge it with an
     *  adjacent walk of the same dog if one exists. */
    fun splitOrMergeWalk(index: Int) = edit { events ->
        mergeWalkWithNeighbor(events, index) ?: splitWalkInTwo(events, index)
    }

    /** Toggle the leg reaching the chip at [index] between foot and bike. */
    fun toggleLeg(index: Int) = edit { events ->
        val e = events.getOrNull(index) ?: return@edit null
        setLegMode(events, index, if (e.arrivedByFoot) LegMode.BIKE else LegMode.FOOT)
    }

    /** Drop a dog from today's plan (its pickup, walk(s) and dropoff). */
    fun markDogNotToday(dogId: String) {
        _removingDogIds.update { it + dogId } // immediate strike-through
        edit { events -> removeDogChips(events, dogId) }
    }

    /** Add an extra walk of [minutes] for [dog] just before returning home. */
    fun addWalk(dog: Dog, minutes: Int) {
        val lat = dog.latitude ?: return
        val lon = dog.longitude ?: return
        val loc = GeoPoint(lat, lon)
        val dur = minutes.coerceAtLeast(1)
        val rule = DogScheduleRule(
            id = "adhoc-${dog.id}-${System.currentTimeMillis()}",
            dogId = dog.id, weekdaysMask = 0,
            earliestStart = null, latestStart = null, latestEnd = null,
            durationMinutes = dur, isAlternative = false,
        )
        edit { events ->
            val list = events.toMutableList()
            val at = (list.size - 1).coerceAtLeast(1) // just before HomeEnd
            list.add(at, RouteEvent.Dropoff(0, loc, dog))
            list.add(at, RouteEvent.Walk(0, loc, listOf(dog), dur * 60))
            list.add(at, RouteEvent.Pickup(0, loc, dog, rule))
            list
        }
    }

    /** Force a dog-free appointment (label, window) at the picked address. */
    fun addAppointment(label: String, startSeconds: Int, endSeconds: Int) {
        val picked = _apptPicked.value ?: return
        edit { events ->
            val appt = RouteEvent.Appointment(
                timeSeconds = 0,
                location = GeoPoint(picked.latitude, picked.longitude),
                durationSeconds = (endSeconds - startSeconds).coerceAtLeast(0),
                startSeconds = startSeconds,
                label = label,
            )
            val list = events.toMutableList()
            val idx = list.indexOfFirst { it !is RouteEvent.HomeStart && it.timeSeconds >= startSeconds }
            val at = if (idx < 1) (list.size - 1).coerceAtLeast(1) else idx
            list.add(at, appt)
            list
        }
        _apptAddressQuery.value = ""
        _apptPicked.value = null
    }

    // --- "Not today" optimistic feedback --------------------------------------

    private val _removingDogIds = MutableStateFlow<Set<String>>(emptySet())

    /** Dogs the walker just tapped "not today" for but still shown — struck
     *  through until the re-time drops them. */
    val removingDogIds: StateFlow<Set<String>> = _removingDogIds.asStateFlow()

    init {
        // Drop pending ids once the dog no longer appears in the editor's chips.
        viewModelScope.launch {
            _editItems.collect { items ->
                if (items == null) {
                    _removingDogIds.value = emptySet()
                    return@collect
                }
                val present = items.mapNotNull { (it.event as? RouteEvent.Pickup)?.dog?.id }.toHashSet()
                _removingDogIds.update { it intersect present }
            }
        }
    }

    /** Active dogs with coordinates — candidates for a hand-added walk. */
    val addableDogs: StateFlow<List<Dog>> = dayPlanService.observeAddableDogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Appointment address autocomplete (BAN) for "add appointment" ---------
    private val _apptAddressQuery = MutableStateFlow("")
    private val _apptPicked = MutableStateFlow<AddressSuggestion?>(null)

    val apptAddressText: StateFlow<String> = _apptAddressQuery.asStateFlow()

    val apptAddressValidated: StateFlow<Boolean> = _apptPicked
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    /** Discard the hand-edited plan and go back to the solver's plan. */
    fun revertPlan() {
        viewModelScope.launch { dayPlanService.discardSavedPlan(_selectedDate.value) }
        _editItems.value = null
        _undo.value = emptyList()
        _pendingCommit.value = null
    }

    fun setBreakRequested(requested: Boolean) {
        dayPlanService.setBreakRequested(_selectedDate.value, requested)
    }

    fun goToPreviousDay() = changeDate(_selectedDate.value.minusDays(1))
    fun goToNextDay() = changeDate(_selectedDate.value.plusDays(1))
    fun goToToday() = changeDate(LocalDate.now())
    fun setDate(date: LocalDate) = changeDate(date)

    /** Switch the day in view; leave edit mode and drop its undo history. */
    private fun changeDate(date: LocalDate) {
        _editItems.value = null
        _undo.value = emptyList()
        _pendingCommit.value = null
        _selectedDate.value = date
    }

    /** Ask the solver for a different plan; discards any hand-edited plan first. */
    fun refresh() {
        val date = _selectedDate.value
        _editItems.value = null
        _undo.value = emptyList()
        _pendingCommit.value = null
        viewModelScope.launch {
            dayPlanService.discardSavedPlan(date)
            dayPlanService.refresh(date)
        }
    }
}
