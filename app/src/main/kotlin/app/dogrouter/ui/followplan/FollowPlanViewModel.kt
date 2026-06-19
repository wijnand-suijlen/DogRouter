package app.dogrouter.ui.followplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dogrouter.domain.dayplan.DayPlanService
import app.dogrouter.domain.dayplan.DayRoute
import app.dogrouter.domain.routing.GeoPoint
import app.dogrouter.domain.routing.RoutingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
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
@OptIn(ExperimentalCoroutinesApi::class)
class FollowPlanViewModel(
    dayPlanService: DayPlanService,
    private val routingProvider: RoutingProvider,
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

    /**
     * The cycling leg the walker rides to reach the current stop, or null
     * when there is none (start of day, a walk in place, or two stops that
     * share an address). Recomputed whenever the step or plan changes; the
     * BRouter geometry lookup runs off the main thread inside the provider.
     */
    val currentLeg: StateFlow<RouteLeg?> = combine(dayRoute, _currentStep, ::Pair)
        .mapLatest { (route, step) -> legTo(route, step) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /** Advance to the next step, clamped to "all done". */
    fun advance() {
        val total = dayRoute.value?.events?.size ?: return
        _currentStep.update { (it + 1).coerceAtMost(total) }
    }

    /** Step back to the previous stop (e.g. a mis-tap). */
    fun goBack() {
        _currentStep.update { (it - 1).coerceAtLeast(0) }
    }

    private suspend fun legTo(route: DayRoute?, step: Int): RouteLeg? {
        if (route == null) return null
        val events = route.events
        if (step <= 0 || step >= events.size) return null
        val from = events[step - 1].location
        val to = events[step].location
        if (from == to) return null // walk in place / same address: no ride
        val geometry = routingProvider.routeGeometry(
            from.latitude, from.longitude, to.latitude, to.longitude,
        )
        // Fall back to a straight line so the card still shows where the
        // leg goes even when BRouter cannot trace it.
        val track = geometry?.takeIf { it.size >= 2 } ?: listOf(from, to)
        return RouteLeg(track)
    }
}

/** A cycling leg to draw on the map: an ordered start-to-end polyline. */
data class RouteLeg(val track: List<GeoPoint>)
