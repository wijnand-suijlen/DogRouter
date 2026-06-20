package app.dogrouter.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.domain.dayplan.DayPlanService
import app.dogrouter.domain.dayplan.PlanPhase
import app.dogrouter.domain.dayplan.PlanState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val dayPlanService: DayPlanService,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

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

    /** Ask the solver for a different plan for the day in view. */
    fun refresh() {
        dayPlanService.refresh(_selectedDate.value)
    }
}
