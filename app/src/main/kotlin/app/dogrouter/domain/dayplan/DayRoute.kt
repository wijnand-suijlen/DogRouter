package app.dogrouter.domain.dayplan

import java.time.LocalDate

/**
 * Full plan for one working day. [events] is chronological from
 * [RouteEvent.HomeStart] to [RouteEvent.HomeEnd]; [conflicts] holds
 * walks the planner could not place.
 */
data class DayRoute(
    val date: LocalDate,
    val events: List<RouteEvent>,
    val totalCyclingSeconds: Int,
    val totalWalkingSeconds: Int,
    val conflicts: List<PlanConflict>,
)
