package app.dogrouter.domain.planner

import java.time.LocalDate

/**
 * The packed plan for one working day: which trips, in which order.
 * Trip ordering is currently insertion order from the bin-packer; manual
 * reordering and time-aware sequencing land in later rounds.
 */
data class DayPlan(
    val date: LocalDate,
    val trips: List<Trip>,
)
