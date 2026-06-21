package app.dogrouter.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.data.prefs.SettingsRepository
import app.dogrouter.domain.dayplan.DayPlanService
import app.dogrouter.domain.dayplan.PlanPhase
import app.dogrouter.domain.dayplan.PlanState
import app.dogrouter.domain.dayplan.RouteEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val dayPlanService: DayPlanService,
    settingsRepository: SettingsRepository,
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
        _removingDogIds.update { it + dogId } // immediate visual feedback
        viewModelScope.launch {
            dayPlanService.markDogNotToday(_selectedDate.value, route, dogId)
        }
    }

    /** Discard the hand-edited plan and go back to the solver's plan. */
    fun revertPlan() {
        viewModelScope.launch { dayPlanService.discardSavedPlan(_selectedDate.value) }
    }

    fun setBreakRequested(requested: Boolean) {
        dayPlanService.setBreakRequested(_selectedDate.value, requested)
    }

    fun goToPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun goToNextDay() {
        _selectedDate.value = _selectedDate.value.plusDays(1)
    }

    fun goToToday() {
        _selectedDate.value = LocalDate.now()
    }

    fun setDate(date: LocalDate) {
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
