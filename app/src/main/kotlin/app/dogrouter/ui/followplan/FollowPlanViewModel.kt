package app.dogrouter.ui.followplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.domain.dayplan.DayPlanService
import app.dogrouter.domain.dayplan.DayRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * Drives the on-the-bike execution screen for one day's plan. Holds which
 * step the walker is on; the plan itself comes from the shared
 * [DayPlanService] for [date].
 *
 * Progress is kept in memory only: it survives configuration changes but
 * not leaving and re-entering the screen. Persisting a resumable trip
 * across exits is a later step (see docs/STATUS.md).
 */
class FollowPlanViewModel(
    dayPlanService: DayPlanService,
    val date: LocalDate,
) : ViewModel() {

    val dayRoute: StateFlow<DayRoute?> = dayPlanService.observePlan(date)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * Index of the current step into the route's events. Equal to the
     * event count once every step is done (the "trip complete" state).
     */
    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    /** Advance to the next step, clamped to "all done". */
    fun advance() {
        val total = dayRoute.value?.events?.size ?: return
        _currentStep.update { (it + 1).coerceAtMost(total) }
    }

    /** Step back to the previous stop (e.g. a mis-tap). */
    fun goBack() {
        _currentStep.update { (it - 1).coerceAtLeast(0) }
    }
}
